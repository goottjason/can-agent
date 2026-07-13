# 복권 자동구매 부가기능 설계

- **작성일:** 2026-07-13
- **상태:** 설계 승인됨 (구현 계획 작성 대기)
- **범위:** can-agent 프로젝트 내 부가기능. 프론트엔드 페이지 없음. 텔레그램 알림으로 충분.

## 1. 목표

동행복권(dhlottery.co.kr)에서 **로또6/45**와 **연금복권720+**를 매주 자동으로 1게임/1조씩(합 2,000원) 구매하고, 추첨 직후 당첨 결과를 텔레그램으로 알린다. 예치금이 3,000원 아래로 떨어지면 알린다. 예치금 충전은 사용자가 직접 한다(자동충전 없음).

### 확정된 결정사항

| 항목 | 결정 |
|------|------|
| 구매 실행 주체 | 파이썬 사이드카 (dhapi 로또 + Playwright 연금) |
| Java(can-agent) 역할 | 스케줄링 · DB 저장 · 당첨확인 · 예치금 알림 · 텔레그램 발송 |
| 번호 선택 방식 | 로또·연금 모두 **자동(매주 랜덤)** |
| 구매 시각 | **매주 화요일 오전 (KST)** — 로또 1게임 + 연금 1조 = 2,000원 |
| 예치금 알림 | 잔액 < 3,000원 → 텔레그램 (충전은 사용자 직접) |
| 당첨 알림 | 연금 목 19:15 / 로또 토 20:45 (추첨 직후, KST) |

## 2. 배경 조사 (근거)

- **로또6/45 자동구매는 순수 HTTP로 검증됨.** `roeniss/dhlottery-api`(dhapi)가 로그인→예치금조회→구매를 HTTP로 구현하며, 보안 키패드 OCR 없이 로그인·구매가 가능하다. 온라인 주간 최대 5게임, 추첨 당일 판매 마감, 통합 1일 15만원 한도.
- **연금복권720+ 자동구매는 순수 HTTP 미검증.** dhapi는 미지원(issue #5 "not planned"). 검증된 유일한 자동구매 경로는 `techinpark/lottery-bot`의 **Playwright 브라우저 자동화 + Tesseract OCR**(보안 키패드) 방식이다.
- **당첨 확인은 공개 데이터로 가능.** 로또는 공개 JSON API(`common.do?method=getLottoNumber&drwNo=회차`), 연금은 공개 당첨 조/번호 조회(read-only).
- **예치금은 조회만 필요.** 충전은 사용자가 직접 하므로 읽기 전용 → 위험 낮음.

**결론:** 구매(인증 필요·취약한 부분)는 검증된 파이썬 도구에 위임하고, 나머지(스케줄·저장·당첨확인·알림)는 Java가 담당한다.

### 출처
- https://github.com/roeniss/dhlottery-api
- https://github.com/roeniss/dhlottery-api/issues/5
- https://github.com/techinpark/lottery-bot
- https://www.clien.net/service/board/use/19123662

## 3. 아키텍처

주 1회 실행이므로 상주 서비스(FastAPI 등)는 두지 않는다. Java 스케줄러가 `ProcessBuilder`로 파이썬 사이드카를 실행하고 **stdout JSON**을 파싱한다.

```
[Spring @Scheduled, zone=Asia/Seoul]
        │ (화 09:00)
        ▼
LotteryPurchaseService ──ProcessBuilder──▶ python sidecar
        │                                    · dhlottery 로그인
        │                                    · 로또 자동 1게임 구매 (dhapi)
        │                                    · 연금 자동 1조 구매 (Playwright)
        │                                    · 예치금 조회
        │              ◀──── stdout JSON ────  {tickets:[…], balance:N, errors:[]}
        ├─ DB 저장(LotteryTicket)
        ├─ 텔레그램 "구매완료"
        └─ balance < 3000 → 텔레그램 "예치금 부족"
```

### 경계 원칙
- **dhlottery 계정 크리덴셜은 파이썬 사이드카 자체 `.env`에만 존재.** Java는 크리덴셜을 절대 다루지 않는다 → 시크릿 경계가 깔끔하다.
- Java는 `LotterySidecarPort` 인터페이스에만 의존한다. 실어댑터는 파이썬을 shell-out 하고, **테스트는 Fake로 대체**한다 → CI에 파이썬/크로미움 불필요.
- 사이드카는 `--dry-run` 모드(로그인 + 잔액 조회만, 구매 안 함)를 지원한다 → 배포 전 스모크 테스트에 사용.

### 배포 전제 (신규)
can-agent가 실행되는 서버에 python venv + `dhapi` + Playwright(Chromium) + Tesseract(연금 로그인 보안키패드 OCR)를 설치해야 한다.

## 4. 컴포넌트 (Java, 포트/어댑터 준수)

```
port/
  LotterySidecarPort.java          # purchaseWeekly(spec) → SidecarResult, getBalance()
  dto/ SidecarResult, PurchasedTicket, BalanceInfo
domain/lottery/
  LotteryTicket.java (JPA)         # gameType, roundNo(회차), numbers, amount,
  GameType.java (LOTTO645/WIN720)  #   purchasedAt, checked, rank, prize, winner
repository/ LotteryTicketRepository
service/lottery/
  PythonLotterySidecarAdapter      # @Primary, ProcessBuilder + JSON  (prod)
  LotteryPurchaseService           # 구매 오케스트레이션 + 멱등성 가드
  LotteryResultService             # 당첨 비교 · 등수 · 상금 계산
  LottoResultClient                # 로또 공개 API (getLottoNumber)
  Win720ResultClient               # 연금 당첨 조/번호 조회 (공개 read-only)
worker/ LotteryScheduler           # @Scheduled zone=Asia/Seoul
config/ LotteryConfig              # prefix=lottery
notification/ (기존 확장)           # TelegramClient.sendText 추출 (아래)
sidecar/lottery/ (신규 top-level)   # 파이썬 스크립트 + requirements + .env.example
scripts/db/ lottery-migration.sql
```

### 기존 코드 개선 (작업 범위 내)
현재 `TelegramNotificationService`는 HTTP 전송과 매매 포맷팅이 섞여 있고, `NotificationEvent`는 매매 전용(TradeType·stockCode)이다. 복권은 일반 텍스트 알림이 필요하므로 **얇은 `TelegramClient.sendText(String)`을 추출**해 매매·복권이 공유하도록 한다.
- 범위: 전송 로직 분리만. 매매 알림의 기존 동작은 불변.
- `NotificationConfig`(botToken·chatId)와 `@ConditionalOnProperty(notification.telegram.enabled)` 게이트는 그대로 재사용.

## 5. 스케줄 · 멱등성 · 예외처리

### 크론 (모두 `Asia/Seoul`, `lottery.enabled=true`일 때만 등록)

| 작업 | 시각 | 설정키 |
|------|------|--------|
| 주간 구매 | 화 09:00 | `lottery.buy-cron` |
| 연금 당첨확인 | 목 19:15 | `lottery.win720-result-cron` |
| 로또 당첨확인 | 토 20:45 | `lottery.lotto-result-cron` |

크론 존은 기존 미국장 ET 크론과 무관하게 `Asia/Seoul`을 사용한다.

### 멱등성 (중복구매 방지 — 핵심)
구매 전 DB에서 **현재 회차 티켓 존재 여부**를 확인하고, 이미 있으면 스킵한다. 화요일 구매 실패 시 (예: 수요일 오전) 재시도해도 이중구매가 발생하지 않는다.

### 예외처리
- 로그인 실패 · 사이트 장애 · 타임아웃 → 텔레그램 알림 + 로그. 구매기록 없음.
- **예치금 부족으로 구매 실패** → "충전 필요" 알림 (자동충전 하지 않음).
- 부분 성공(예: 로또 성공·연금 실패) → 성공분만 저장, 실패분 사유 알림.
- 당첨확인 API 실패 → 다음 스케줄에 미확인 티켓 재시도.

### 안전 게이트 (중대 등급)
실제 돈으로 구매하므로 `lottery.enabled`는 **기본 false**로 두고, 프로덕션 활성화·배포는 **사용자 승인 필수**(CLAUDE.md 안전규칙). 최초에는 `--dry-run`으로 로그인/잔액만 검증한 뒤 실구매로 전환한다.

## 6. 데이터 모델 · 당첨확인 · 테스트

### LotteryTicket 테이블

| 컬럼 | 설명 |
|------|------|
| id | PK |
| game_type | LOTTO645 / WIN720 |
| round_no | 회차(drwNo) |
| numbers | 구매 번호 (JSON: 로또 6자리 / 연금 조+7자리) |
| amount | 구매 금액 |
| purchased_at | 구매 시각 |
| result_checked | 당첨확인 완료 여부 |
| rank | 등수 (미당첨 null 또는 0) |
| prize | 당첨금 |
| is_winner | 당첨 여부 |

구매 시 저장하고, 당첨확인 시 `rank`/`prize`/`is_winner`/`result_checked`를 갱신한다. 마이그레이션 SQL은 `scripts/db/lottery-migration.sql`.

### 당첨확인
- **로또**: 공개 JSON `common.do?method=getLottoNumber&drwNo=회차` → 저장 번호와 비교, 1~5등 판정.
- **연금**: 공개 당첨 조/번호 조회 → 1등(조+7자리 일치), 2등(7자리 일치), 보너스 등 규칙 판정. read-only이므로 구매보다 저위험. (당첨 데이터 조회 엔드포인트는 구현 계획 단계에서 확정)

### 테스트 전략
- `FakeLotterySidecarPort`로 구매 오케스트레이션·멱등성을 단위테스트한다 (파이썬 불필요).
- **등수/상금 계산은 고정 픽스처로 단위테스트**한다 (알려진 당첨번호 vs 구매번호).
- 텔레그램은 기존 검증된 전송부를 재사용한다.
- 파이썬 사이드카는 `--dry-run` 스모크 + 수동 검증 (사이트 상호작용은 자동테스트가 어렵다).

## 7. 설정 키 요약 (`lottery.*`)

| 키 | 기본값 | 설명 |
|----|--------|------|
| `lottery.enabled` | false | 기능 전체 게이트 (안전) |
| `lottery.buy-cron` | `0 0 9 * * TUE` | 주간 구매 크론 |
| `lottery.win720-result-cron` | `0 15 19 * * THU` | 연금 당첨확인 |
| `lottery.lotto-result-cron` | `0 45 20 * * SAT` | 로또 당첨확인 |
| `lottery.balance-threshold` | 3000 | 예치금 알림 임계 |
| `lottery.sidecar-command` | (경로) | 파이썬 사이드카 실행 명령 |
| `lottery.sidecar-timeout-sec` | (예: 120) | 사이드카 타임아웃 |

dhlottery 계정 크리덴셜은 Java 설정이 아니라 **사이드카 `.env`**에만 둔다. 텔레그램은 기존 `notification.telegram.*`를 재사용한다.

## 8. 범위 밖 (YAGNI)

- 프론트엔드/대시보드 페이지 없음.
- 예치금 자동충전 없음 (사용자 직접).
- 수동/반자동 번호 선택 없음 (자동만).
- 주 5게임 등 다량 구매 없음 (로또 1게임 + 연금 1조 고정).
- 상주 마이크로서비스 없음 (subprocess).
