# 미국 대전환 아키텍처 설계 (2단계)

작성일: 2026-07-09 · 브랜치: `us-pivot` · 입력: `B_토스실사` · `C_무료재무데이터` · `D_재사용맵`
목표: KIS 국내 → 토스(주문·시세) + SEC EDGAR(재무·섹터) 하이브리드 미국주식 CANSLIM 자동매매로 이관. **CANSLIM 엔진 보존, 외부연동·식별·시간 껍데기 교체.**

---

## 0. 설계 원칙

1. **엔진 불변**: 분석기 8개·게이트·사이징·관측성·알림은 손대지 않는다(도메인 엔티티가 표준 필드명이라 가능).
2. **어댑터 격리**: 모든 외부연동을 인터페이스 뒤로. 지금은 구현 1개(Toss/EDGAR)여도 인터페이스를 둬 테스트·모킹·향후 교체를 연다.
3. **소수점 우선**: 주문의 1차 경로는 금액기반(notional) 시장가 매수. 정수·지정가는 부차.
4. **무손실 정밀도**: 가격·금액은 전 경로 `BigDecimal`. `int`/`long` 통화 표현 전면 제거.
5. **단계별 선적**: 각 이관 단계가 독립적으로 컴파일·테스트·(모의)동작하도록 순서화.

---

## 1. 목표 아키텍처 (레이어)

```
┌─────────────────────────────────────────────────────────────┐
│ WORKER   IntradayMonitorWorker · AutoTradingWorker           │
│          DataSyncScheduler · PortfolioScheduler              │  ← 골격 재사용, 시간 ET화
├─────────────────────────────────────────────────────────────┤
│ STRATEGY TradingStrategyService · planPurchases()            │  ← 재사용, 사이징 notional화
│          CanSlimAnalysisService + 분석기 8개 (불변)          │
├─────────────────────────────────────────────────────────────┤
│ PORT     BrokerPort · MarketDataPort · FinancialsPort        │  ← ★신규 인터페이스 seam
│          (interface)   MarketCalendarPort                    │
├─────────────────────────────────────────────────────────────┤
│ ADAPTER  TossBrokerAdapter    TossMarketDataAdapter          │  ← ★신규 구현
│          EdgarFinancialsAdapter  TossCalendarAdapter         │
│          TossTokenProvider(←KIS TokenProvider 캐시패턴 이식) │
├─────────────────────────────────────────────────────────────┤
│ DOMAIN   Stock(+cik/ticker/exchange/ccy) · StockPrice        │  ← 스키마 소폭 확장
│          FinancialStatement(+periodType) · Portfolio · Trade │
│          AnalysisScore · CupAndHandlePattern · MonitorCheckLog│  ← 불변
├─────────────────────────────────────────────────────────────┤
│ PERSIST  JPA Repository (재사용) · PostgreSQL (유지)         │
└─────────────────────────────────────────────────────────────┘
   NOTIFY  Router·Console·Telegram (재사용) / Kakao (폐기)
```

### 1.1 포트(인터페이스) 정의 — 신규 seam

```java
interface BrokerPort {
    OrderResult placeBuy(String symbol, OrderSpec spec);   // spec: NOTIONAL(orderAmount) | LIMIT(qty,price)
    OrderResult placeSell(String symbol, OrderSpec spec);  // 소수 수량 매도(시장가)
    OrderResult modify(String orderId, OrderSpec spec);    // 미체결 정정
    OrderResult cancel(String orderId);
    BrokerBalance getBalance();                             // USD 예수금·평가·보유
    OrderStatus  getOrder(String orderId);                 // 체결/부분체결 조회
    BigDecimal   getCurrentPrice(String symbol);           // ★int 아님
}
interface MarketDataPort {                                 // ← KrxApiClient 대체
    List<Candle> getDailyCandles(String symbol, int count, LocalDate before); // 200개+페이지네이션
}
interface FinancialsPort {                                 // ← DartApiClient 대체
    List<FinancialFact> getFinancials(String cik);         // XBRL companyfacts 파싱 결과
}
interface MarketCalendarPort {                             // ★신규 (미국 휴일·개장)
    boolean isTradingDay(LocalDate d);                     // 토스 US market-calendar 활용
    boolean isMarketOpen(Instant now);                     // ET 9:30~16:00
}
```

- **OrderSpec**(sealed): `Notional(BigDecimal orderAmount)` = 소수점 시장가 매수 1차 경로 / `Limit(BigDecimal qty, BigDecimal price)` = 정수·지정가.
- 포트 도입으로 **호출부 4곳**(TradingStrategyService·IntradayMonitorWorker·PortfolioScheduler·DashboardController)이 구현 아닌 인터페이스에 의존 → 어댑터 교체·모킹 가능.

---

## 2. 도메인 모델 변경

### 2.1 Stock — 식별 확장 (필수)
| 필드 | 변경 | 사유 |
|---|---|---|
| `ticker` | 신규(또는 code 재해석) | 주문·시세 심볼 |
| `cik` | **신규 필수** | EDGAR 재무조회 조인키(없으면 재무수집 불가) |
| `exchange` | 신규 | NYSE/NASDAQ(티커 거래소간 중복 대비) |
| `currency` | 신규(기본 USD) | ADR·다중상장 대비 |
| `sicCode` | 신규 | SEC SIC(섹터 분류) — `sector`는 사람이 읽는 업종명 유지 |
| 생성자 | 확장 | 4인자→식별필드 포함 |

### 2.2 FinancialStatement — SEC 적재 (격전지)
| 항목 | 변경 |
|---|---|
| `periodType` enum(QUARTER/ANNUAL) | **신규** — `fiscalQuarter==4=연간` 하드가정 제거. 10-Q=QUARTER, 10-K=ANNUAL |
| `fiscalQuarter` `nullable=false` | 연간행 수용(ANNUAL은 quarter=null 허용) |
| `eps` scale | 2→4 (미국 EPS 소수 정밀) |
| `debtRatio` precision | 5→상향(자본잠식·고부채 오버플로 방지) |
| `roe` | SEC 미제공 → `NetIncomeLoss / StockholdersEquity` **계산 적재** |
| `currency` | 신규 |
| 매핑 | 한글계정 → US-GAAP 태그(§4.2) |

### 2.3 그대로 (스키마 무변경)
StockPrice(scale2=USD센트 적합), AnalysisScore, CupAndHandlePattern, MonitorCheckLog, PatternStatus, TradeType.
**Portfolio.quantity·Trade.quantity = BigDecimal(19,4) 이미 소수 대응** → 무변경. `profitRate/commission` precision=5만 상향 검토.

---

## 3. 주문·사이징 모델 (정수→notional 전환)

### 3.1 현재(KIS) vs 목표(Toss)
| 단계 | 현재 | 목표 |
|---|---|---|
| 사이징 | `qty = maxInvest / price` **FLOOR 정수** | **금액 그대로**: `orderAmount = availableCash × positionRate/100` |
| 주문 | `buy(code, int qty, int price)` | `placeBuy(symbol, Notional(orderAmount))` 소수점 시장가 |
| 가격 | `price.intValue()` 절삭 | 전달 안 함(시장가) 또는 BigDecimal(지정가) |

- **planPurchases() 재사용**: 슬롯선정·positionRate 상한·라운드로빈 이월 알고리즘 유지. **출력만 "정수 수량"→"주문 금액(orderAmount)"** 으로. 정수화(FLOOR)·최소주문 정수주 로직 제거 → 오히려 **단순화**(소액계좌 분산 3버그의 정수 반올림 이슈가 근본 해소됨).
- **매도**: 소수 수량 시장가(`placeSell(symbol, ...)`), 전량/부분 청산.
- **환전(open question)**: 토스 USD/KRW 환율 엔드포인트 존재. 주문시 자동환전 vs 사전환전/통합증거금 여부는 **PoC 확인 필요**(§7). 설계는 `BrokerBalance.availableUsd` 기준으로 두고, 환전 실행은 어댑터 내부에 캡슐화.

---

## 4. 데이터 수집 파이프라인

### 4.1 시세 (Toss 캔들) — MarketDataPort
- 종목별 일봉, 요청당 200 + `before` 커서 페이지네이션 → 65주+(~455거래일) 백필.
- **KrxDataSyncService 영속화 재사용**, fetch 루프만 "날짜별 전종목"→"종목별 캔들"로 역전.
- 50/200MA는 저장 안 함, 캔들에서 자체계산(MarketDirectionAnalyzer 입력).

### 4.2 재무 (SEC EDGAR 나이틀리) — FinancialsPort
- **1차: 벌크 ZIP** `companyfacts.zip`(~03:00 ET 재컴파일) 나이틀리 다운로드·파싱 → 종목별 per-ticker API 유량 무부담. (edgartools 파서 참고)
- XBRL 태그 매핑(한글계정 폐기):

| FinancialStatement | US-GAAP 태그 |
|---|---|
| revenue | `Revenues` ∥ `RevenueFromContractWithCustomerExcludingAssessedTax`(이원화) |
| operatingIncome | `OperatingIncomeLoss` |
| netIncome | `NetIncomeLoss` |
| eps | `EarningsPerShareDiluted`(1차) ∥ `EarningsPerShareBasic` |
| roe | 계산: `NetIncomeLoss / StockholdersEquity` |
| debtRatio | 계산: `Liabilities / StockholdersEquity`(또는 /Assets) |

- **분기 표준화(적재규약 ①)**: 10-Q는 netIncome이 YTD 누적일 수 있음. **standalone 분기값**으로 정규화(Q4 = 연간 − (Q1+Q2+Q3)). EPS는 분기·연간 별도 태그 우선.
- **연간(적재규약 ②)**: 10-K → `periodType=ANNUAL`. AnnualEarningsAnalyzer의 `fiscalQuarter==4` 조회를 `periodType==ANNUAL`로 1줄 수정(§6 마이그레이션).

### 4.3 종목 마스터 & 유니버스
- SEC `company_tickers.json`(ticker→CIK, ~1MB) 로딩 → Stock 마스터 + CIK 매핑 동시 해결(corp_code_map.json·KrxCorpDTO 대체).
- **유니버스: 미국 국내 보통주로 한정**(ADR/foreign private issuer 제외 — EDGAR가 분기 EPS 미제공). submissions 메타의 `securityType`/폼종류로 필터. (추천 기본값; ADR 편입은 별도 유료소스 필요 → 후속 결정)

---

## 5. 시간·스케줄 (ET 재작성)

| 대상 | 변경 |
|---|---|
| 존 | `Asia/Seoul` → `America/New_York`(DST 자동) |
| isTradingHours | 09:30~16:00 ET + **미국 휴일캘린더**(MarketCalendarPort = 토스 US market-calendar). 워커·컨트롤러 3중복 → **공유 유틸**로 추출 |
| 장중 크론 | `@Scheduled(zone="America/New_York", cron="0 */5 9-15 * * MON-FRI")` (ET 표기라 자정교차 문제 소멸) |
| 야간 점수(AutoTradingWorker) | EDGAR 나이틀리 배치 완료 후 시각으로 |
| 재무 크론(DataSyncScheduler) | SEC 10-Q/10-K 제출주기 + 나이틀리 ZIP 재컴파일(03:00 ET) 이후 |

- 서버가 KST/UTC여도 zone 명시라 안전. 운영자 로그·알림 타임스탬프는 ET 병기 권장.

---

## 6. 설정 키

**폐기**: `api.dart.*`·`api.krx.*`·`api.korea-investment.*`, 하드코딩 URL(koreainvestment/opendart), 계좌 `-`분할.
**신규**: `toss.app-key/secret/account`·`toss.base-url`(sandbox/live)·`edgar.user-agent`(필수)·`edgar.bulk-url`.
**유지(시장무관)**: `trading.*` 전부, `trading.scheduler.enabled`, `notification.telegram.*`, datasource/jpa.

---

## 7. 단계별 이관 순서 (각 단계 컴파일·테스트 가능)

| # | 단계 | 산출/검증 |
|---|---|---|
| **P1** | **포트 인터페이스 3+1개 추출** + 호출부 4곳을 인터페이스 의존으로. 기존 KIS 구현을 임시 어댑터로 감싸 **그린 유지** | 컴파일·기존 테스트 통과(리팩터링만) |
| **P2** | **int→BigDecimal 가격 전환** (getCurrentPrice·price.intValue·saveCurrentPrice·NotificationEvent) | 단위테스트로 소수 정밀 검증 |
| **P3** | **도메인 스키마 확장** Stock(cik/ticker/exchange/ccy/sic)·FinancialStatement(periodType 등) + AnnualEarningsAnalyzer 1줄 수정 | 마이그레이션·엔티티 테스트 |
| **P4** | **EdgarFinancialsAdapter** + 나이틀리 배치 + XBRL 매핑 + 유니버스 로더(company_tickers.json) | 실제 티커 몇 개로 재무 적재→분석기 스코어 확인 |
| **P5** | **TossMarketDataAdapter**(캔들) + KrxSync 루프 역전 | 캔들 백필→StockPrice→MA/컵 입력 확인 |
| **P6** | **TossBrokerAdapter**(OAuth·notional·잔고) + TossTokenProvider + 사이징 notional화 + MarketCalendar | **모의/소액 PoC**(§8 미확정 실측) |
| **P7** | **시간 ET화** 크론·isTradingHours·공유유틸 | 개장/휴장 판정 테스트 |
| **P8** | **표시층** dashboard.html·monitor.js 통화·로케일 + StockController enum | 대시보드 육안 |
| **P9** | **폐기 정리** KRX/DART 클라이언트·DTO·corp_code_map·Kakao 제거 | 데드코드 제거·테스트 그린 |

- P1~P3는 외부의존 없이 안전(리팩터링·스키마). P4~P6이 핵심 신규. P6에서 실계좌 PoC로 미확정 해소.

---

## 8. 착수 전 PoC로 확정할 미확정 (설계 가정, B실사 open question)
1. 토스 US 소수점 주문 실제 필드(orderAmount 외 소수수량? 최소금액·자릿수) — P6 소액 왕복.
2. rate limit 수치·실전/모의 구분·API 비용.
3. **환전 방식**(주문시 자동 vs 사전환전/통합증거금) — 사이징·잔고 설계에 직접 영향.
4. 미국장 서머타임·프리/애프터마켓 주문 가부.
5. 해외 양도세(250만원 공제·22%) 처리·신고.

## 9. 리스크
- **★가격 정밀도**(P2): int 잔재 하나라도 남으면 USD 값 붕괴 → P2를 조기·전수 처리.
- **환전 미확정**(P6/§8-3): 통합증거금 아니면 사이징에 환전 지연·수수료 반영 필요.
- **CANSLIM "I"(기관수급)**: 현 거래량 프록시로 이식 무손실이나 미국선 13F 실데이터가 더 적합(선택 개선).
- **"M"(시장방향)**: 현재 개별종목 MA(설계상 원 O'Neil과 상이) — 이관과 무관하나 S&P500/QQQ MA로 개선 여지(선택).
- **유니버스 규모**: 수천 티커 나이틀리 캔들 백필 시 토스 유량 — 초기 유니버스 축소(거래량 상위 N) 후 확장.

---

## 10. 한 줄 요약
**포트 3개 뒤로 Toss·EDGAR 어댑터를 끼우고, 가격을 BigDecimal로 올리고, 사이징을 금액기반으로 바꾸고, 시간을 ET로 옮기면 — CANSLIM 엔진·게이트·관측성·알림은 그대로 미국에서 돈다.** P1~P3(안전 리팩터링/스키마) → P4~P6(어댑터+PoC) → P7~P9(시간·표시·정리).
