# CAN-Agent 아키텍처 맵 (하네스 공용 참조)

> 2026-07-07 전수 탐색 결과 요약. 에이전트는 작업 전 이 문서로 전체 구조를 파악하고, 세부는 해당 소스를 직접 읽는다.
> 코드가 변경되면 이 문서도 갱신할 것 (특히 설정 키·임계값·게이트 목록).

## 1. 시스템 개요

- **도메인**: CANSLIM + 컵앤핸들 기반 한국주식(KOSPI/KOSDAQ) 자동매매. Java 17 / Spring Boot 3.3.2 / Thymeleaf / JPA.
- **운영**: Oracle Cloud ARM 서버(168.107.31.154), Docker Compose(nginx + PostgreSQL + can-agent). 배포는 `deploy.sh`(로컬 bootJar → scp → `docker compose up -d --build`).
- **프로필**: dev=H2 인메모리, prod=PostgreSQL(`SPRING_PROFILES_ACTIVE=prod`). `.env`를 `DotenvConfig`가 로드.
- **액추에이터 없음**. 로그는 컨테이너 stdout만 (`docker logs`) — 재배포 시 소실.

## 2. 스케줄 흐름 (모두 Asia/Seoul)

| 워커 | 크론 (설정 키) | 역할 |
|------|---------------|------|
| `IntradayMonitorWorker.monitorTrading()` | `trading.scheduler.monitor-cron` = `0 */5 9-15 * * MON-FRI` | **핵심 매매 루프**: 후보 선정→현재가→분석→신호→매수 |
| `AutoTradingWorker.executeAnalysis()` | `trading.scheduler.cron` = `0 0 19 * * MON-FRI` | 야간 전종목 점수 계산·저장 (매매 안 함) |
| `DataSyncScheduler.syncDailyData()` | `trading.scheduler.sync-cron` = `0 30 15 * * MON-FRI` | KRX 일별 주가 동기화 |
| `DataSyncScheduler` 분기 재무 | `trading.scheduler.quarterly-cron` = `0 0 10 1 1,4,7,10 *` | DART 재무제표 동기화 |

**장중 루프 상세** (`IntradayMonitorWorker`):
1. `isTradingHours()` 09:00–15:30 월–금 아니면 **무로그 return** (:93-95)
2. `getTargetStocks()` — **어제 날짜** 기준 거래량 상위 200 + 등락률 상위 200 (:233-250). 어제 주가 데이터 없으면 빈 목록.
3. 종목별: 현재가 조회(실패 시 무로그 skip :270) → 보유중이면 skip(:287) → CANSLIM+컵 분석 → 신호 판정 → 500ms sleep
4. 신호 종목 점수비례 배분 → 매수 주문(3회 재시도)

## 3. 점수 체계

| 요소 | 분석기 | 만점 | 필요 데이터 | 데이터 없을 때 |
|------|--------|------|------------|---------------|
| C 분기실적 | QuarterlyEarningsAnalyzer | 20 | 재무제표 당분기+전년동기 (EPS/순이익) | **0점** "재무제표 데이터 부족" |
| A 연간실적 | AnnualEarningsAnalyzer | 20 | Q4 재무 2개년 | **0점** |
| S 수급 | SupplyDemandAnalyzer | 15 | 주가 4주 + 20일 평균거래량 | **0점** |
| L 업종선도 | IndustryLeaderAnalyzer | 15 | 13주 주가 + sector + ROE | **0점** (업종 없음/동종 2개 미만) |
| I 기관 | InstitutionalInvestorAnalyzer | 15 | 거래량 4주+20일 (※실제 기관 데이터 아닌 거래량 프록시) | **0점** |
| M 시장방향 | MarketDirectionAnalyzer | 15 | 주가 50일(MA50)+200일(MA200) | **0점** |
| 컵앤핸들 | CupAndHandleAnalyzer | 30–90 | 주가 65주+, 최소 20개 포인트 | 패턴 없음(0) |

- CANSLIM 만점 100, 컵 최대 90(base30 +깊이/기간 각10 → 컵단독 50, 핸들+돌파 포함 풀패턴 최대 90), 총점 최대 **190**.
- `CanSlimResult.isBuySignal()` = 총점 ≥ **40** / `isStrongBuy()` ≥ 60.
- **매수 게이트** (IntradayMonitorWorker :314-321): `(canSlimBuy || cupBuy)` **AND** `CANSLIM+컵 총점 ≥ trading.min-score(기본 120)`. 두 조건 모두 무로그 reject.
- ⚠️ **불일치**: `TradingStrategyService`의 min-score 기본값은 60(:50), 워커 경로는 120 — 두 경로 상이.
- 사유 텍스트("재무제표 데이터 부족" 등)는 DTO에만 있고 **DB 미저장**. `AnalysisScore`에 요소별 점수 컬럼은 있음(quarterly/annual/supply_demand/market_direction/industry_leader/institutional/cup/can_slim/total).
- `AnalysisScoreRepository`에 ORDER BY score DESC(Top-N) 쿼리 **없음**.

## 4. 매매 0건의 게이트 사슬 (진단 우선순위)

가능성 순위 — 상세 SQL·로그 패턴은 `stall-diagnosis` 스킬 참조:

1. **데이터 결핍 → 점수 0점 연쇄** (최유력): DART 재무 없으면 C+A=0 → CANSLIM 최대 60 → min-score 120 도달을 위해선 컵 패턴 필수. 주가 이력 <200일이면 M도 부분/0. IPO·신규 동기화 종목은 전 요소 0.
2. `getTargetStocks()` 빈 목록: 어제 KRX 동기화 실패 → 후보 0.
3. min-score=120 자체가 과도: 정상 데이터에서도 CANSLIM 40+컵 50 = 90으로 미달.
4. 한투 토큰/계좌: `KOREA_INVESTMENT_APP_KEY/SECRET/ACCOUNT_NUMBER` 미설정·오류 → "토큰 발급 실패" 로그 → 현재가·잔고 전부 실패.
5. `REAL_TRADING=false` → executeBuy가 null 반환(시뮬레이션).
6. `trading.scheduler.enabled≠true` → 워커 빈 자체가 미생성 (로그 흔적 없음).
7. 타임존: 컨테이너가 UTC여도 크론 zone·isTradingHours 모두 Asia/Seoul 하드코딩이라 안전하나, 확인 대상.
8. 예수금 0 / 최대보유(10) 도달 / 최소주문 5,000원 미달 (이들은 로그 있음).

**무로그 silent return 지점**: IntradayMonitorWorker :93-95(장외), :270-271(현재가 실패), :275-276(가격≤0), :287(보유중), :314-316(신호 없음), :320-321(총점 미달), :373-375(잔고 null).

## 5. 관측성 현황 (개선 대상)

- 대시보드(`/`)는 SSR 전용, 자동 새로고침 없음. 모니터 섹션: 가동중/대기중, 마지막 검사, 신호 건수, 신호 테이블(있을 때만).
- `GET /api/monitor/status` JSON 존재하나 프론트에서 미사용.
- **없음**: 검사 퍼널 통계(스캔 N → 단계별 탈락 사유 → 신호), Top-N 점수 리더보드, 요소별 점수 분해 UI, 미포착 사유 표시, 다음 검사 카운트다운, 거래 이력 페이지.
- 신호·탈락 기록이 메모리(`getLastSignals()`)에만 있어 재시작 시 소실.

## 6. 주요 설정 키 (application.yml / application-prod.yml)

| 키 | 기본(dev) | prod | 의미 |
|----|----------|------|------|
| `trading.scheduler.enabled` | true | true | 워커 빈 생성 여부 (`@ConditionalOnProperty`) |
| `trading.min-score` | 120 | 120 | 매수 최소 총점 |
| `trading.max-positions` | 20 | 20 | 최대 보유 종목 (분산) |
| `trading.position-rate` | 5 | 5 | 종목당 상한 % — 총자산(현금+보유평가) 기준 |
| `trading.real-trading` | false | `${REAL_TRADING:true}` | 실주문 여부 |
| `trading.secret` | — | `${TRADE_SECRET:}` | `/trade/run` 헤더 인증 |
| 한투 | — | `KOREA_INVESTMENT_APP_KEY/SECRET/ACCOUNT_NUMBER/IS_REAL` | 실전 `openapi.koreainvestment.com:9443` |
| 데이터 | — | `DART_API_KEY`, `KRX_API_KEY` | 재무/주가 동기화 |
| 알림 | 콘솔만 | Telegram+Kakao (`TELEGRAM_BOT_TOKEN/CHAT_ID`, `KAKAO_WEBHOOK_URL`) | 토큰 없으면 조용히 skip |

## 7. 검증 명령

```bash
./gradlew test                 # 전체 테스트 (H2)
./gradlew bootJar -x test      # 배포용 빌드
./deploy.sh                    # 서버 배포 (scp + docker compose) — 사용자 승인 후에만
ssh -i ssh-key-2026-06-25.key ubuntu@168.107.31.154   # 서버 접속 (읽기 진단용)
```

서버 진단(읽기 전용): `docker logs --tail 500 <can-agent 컨테이너>`, `docker exec <pg 컨테이너> psql -U <user> -d canagent -c "..."`.
