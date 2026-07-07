---
name: stall-diagnosis
description: "CAN-Agent 매매 정지 진단 절차. 매매가 안 될 때, 신호 0건, 주문이 안 나갈 때, '왜 안 사지/왜 멈췄지' 등 원인 규명 요청 시 반드시 사용. 게이트 사슬(스케줄러→데이터→API→점수→실행)을 상류부터 증거 기반으로 판정한다. 재진단·부분 재확인 요청에도 사용. 신규 기능 구현이나 대시보드 수정 요청에는 사용하지 않음(canagent-dev 사용)."
---

# Stall Diagnosis (매매 정지 진단)

매매 0건의 원인을 게이트 사슬 순서로 판정하는 절차. 전체 게이트 목록과 코드 근거는 `.claude/skills/canagent-dev/references/architecture-map.md` §4 참조.

## 원칙

- **상류 우선**: 스케줄러가 안 돌면 점수 진단은 무의미하다. 반드시 순서대로.
- **증거 없이 판정 없음**: 각 게이트는 로그·SQL·설정 값으로 통과/차단/미확인을 기록한다. 미확인은 통과가 아니다.
- **읽기 전용**: 진단 중 서버 상태를 바꾸지 않는다. 조회 명령만.

## 진단 절차 (게이트 사슬)

`scripts/diagnose-funnel.sh`가 아래 조회를 일괄 수행한다 (ssh 접근 가능 시):
```bash
bash .claude/skills/stall-diagnosis/scripts/diagnose-funnel.sh   # 환경변수로 서버/키 지정 가능
```
스크립트 실패 시 아래를 수동 수행.

### G1. 스케줄러 가동
- 로그에서 장중 검사 흔적: `docker logs --tail 2000 <app>` 에서 `장중 모니터링` / `대상 종목 수` 검색.
- 흔적 전무 → `trading.scheduler.enabled` 값, 컨테이너 재시작 시각, `@ConditionalOnProperty` 확인. 프로필(prod) 활성 여부도.
- 판정 팁: 워커 빈 미생성이면 **아무 로그도 없다** — 부재 자체가 증거.

### G2. 후보 종목 (어제 주가 데이터)
```sql
SELECT COUNT(*) FROM stock WHERE active = true;
SELECT date, COUNT(*) FROM stock_price GROUP BY date ORDER BY date DESC LIMIT 5;
```
- 어제(직전 거래일) 행 0 → KRX 동기화 실패가 원인. `getTargetStocks()`가 빈 목록 → 검사할 종목 자체가 없음.

### G3. 한투 API/토큰
- 로그에서 `토큰 발급 실패`, `잔고 조회 실패`, `주문 실패` 검색.
- `.env`/환경변수: `KOREA_INVESTMENT_APP_KEY/SECRET/ACCOUNT_NUMBER` 설정 여부(값은 출력하지 말고 존재·길이만), `KOREA_INVESTMENT_IS_REAL`, `REAL_TRADING`.

### G4. 데이터 완비율 (점수 0점 연쇄의 원인)
```sql
-- 재무제표 보유 종목 수 (C·A 요소의 생사)
SELECT COUNT(DISTINCT stock_id) FROM financial_statement;
-- 종목별 주가 이력 깊이 (M·컵 요소: 200일/65주 필요)
SELECT stock_id, COUNT(*) c FROM stock_price GROUP BY stock_id ORDER BY c DESC LIMIT 5;
SELECT COUNT(*) FROM (SELECT stock_id FROM stock_price GROUP BY stock_id HAVING COUNT(*) >= 200) t;
-- 업종 분류 (L 요소)
SELECT COUNT(*) FROM stock WHERE active = true AND (sector IS NULL OR sector = '');
```

### G5. 점수 분포 vs 임계값
```sql
SELECT MAX(total_score), AVG(total_score), COUNT(*) FROM analysis_score WHERE analysis_date = (SELECT MAX(analysis_date) FROM analysis_score);
SELECT s.name, a.total_score, a.can_slim_score, a.cup_score, a.quarterly_score, a.annual_score
FROM analysis_score a JOIN stock s ON s.id = a.stock_id
WHERE a.analysis_date = (SELECT MAX(analysis_date) FROM analysis_score)
ORDER BY a.total_score DESC LIMIT 20;
```
- 최고점 < min-score(기본 120)이면: 요소별 분해로 어느 요소가 0인지 확인. quarterly+annual이 전반적으로 0 → G4 재무 결핍. cup_score 전반 0 → 이력 부족 또는 패턴 기준 과엄격.
- ⚠️ 테이블명은 실제 스키마 확인 후 사용 (엔티티는 `AnalysisScore` — 네이밍 전략에 따라 `analysis_score`/`analysis_scores`).

### G6. 실행 조건
- 로그: `예수금 부족`, `최대 보유 종목 수 도달`, `최소 주문금액 미달`, `장 마감으로 매수 건너뜀` 검색.
- `SELECT COUNT(*) FROM portfolio WHERE active = true;` — max-positions(10) 도달 여부.

## 보고 형식

게이트별 표(판정/증거) → 최종 원인(복수 가능, 가능성 순) → 권장 조치. 조치는 (a) 설정 변경, (b) 데이터 백필, (c) 코드 수정으로 구분하고, 코드 수정은 canagent-dev 오케스트레이터로 이관한다.

## 흔한 오판 방지

- "가동 중" 표시만 믿지 말 것 — 메모리 상태라 마지막 검사가 몇 시간 전일 수 있다. 반드시 시각을 확인.
- min-score 120은 CANSLIM 만점(100)보다 높다 — 컵 패턴 없이는 구조적으로 도달 불가. "점수 낮음"이 곧 "데이터 결핍"은 아니다.
- silent return 7곳(architecture-map §4)은 로그가 **없는 게 정상 동작**이므로, 로그 부재를 "실행 안 됨"으로 오판하지 말 것.
