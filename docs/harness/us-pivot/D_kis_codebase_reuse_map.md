# 기존 KIS 코드베이스 재사용/폐기 구분 맵 (미국 대전환 1단계)

조사일: 2026-07-09 · 브랜치: `us-pivot` · 방식: 4클러스터 병렬 정밀분석(분석기·전략 / 도메인·리포지토리 / 브로커·데이터 / 워커·웹·알림) · 규모: ~6,844 LOC / 68파일

---

## 0. 총평 — **이식성이 매우 높다**

CAN-Agent는 **분석 엔진과 관측성이 시장중립**으로 설계돼 있어, 한국 종속이 **좁고 얕게(표시층) + 좁고 깊게(주문·가격·재무추출 몇 지점)** 분포한다. 도메인 필드명이 이미 `revenue/netIncome/eps/roe`, `open/high/low/close/volume` 같은 표준명이라 **DART 필드명이 코드에 노출돼 있지 않은 것**이 최대 이점 — EDGAR 어댑터가 엔티티만 채우면 분석기 8개가 무수정으로 산다. 그리고 **Portfolio·Trade 수량이 이미 `BigDecimal(19,4)`** 라 소수점 매매에 안 깨진다.

**핵심 리스크는 코드량이 아니라 4가지 횡단 이슈에 집중:**
1. **인터페이스 seam 부재** — 브로커/데이터 클라이언트가 전부 구체클래스 직접주입 → 어댑터 끼울 이음새 0개. 이관 1단계 = 인터페이스 3개 추출.
2. **정수 가격 truncation** — 가격을 `int`로 받고(`getCurrentPrice()`), `price.intValue()`로 절삭. 원화는 무손실이나 **USD $150.25 → 150으로 붕괴.** 전 경로 `BigDecimal`화 필수. (최대 correctness 리스크)
3. **크론 자정교차 + DST** — 미국장 KST 23:30~06:00은 자정을 넘어 hour-range 하나로 불가. `zone="America/New_York"` ET(9:30~16:00)로 재작성해야 DST 자동처리. + 미국 휴일 캘린더.
4. **재무 스키마 매핑** — DART 한글 계정명 추출 → SEC US-GAAP XBRL 태그; `fiscalQuarter nullable=false`가 연간(10-K) 처리 막음; `roe`는 SEC 미제공(계산 필요); `eps/debtRatio` scale/precision 부족.

---

## 1. ♻️ 그대로 재사용 (무수정~경미)

| 자산 | 근거 |
|---|---|
| **CANSLIM 분석기 8개** (Quarterly·Annual·SupplyDemand·MarketDirection·IndustryLeader·Institutional·CupAndHandle·CanSlimAnalysisService) | 통화·시장 무관 순수 로직. O'Neil 임계값(25/10/5%, ROE 20/15%, 컵 12~33%)은 미국 원산이라 그대로. 의존 필드가 표준명 |
| **CanSlimResult / CupAndHandleResult** DTO | `isBuySignal()≥40`·`isStrongBuy()≥60` 중립 |
| **도메인**: StockPrice, AnalysisScore, CupAndHandlePattern, MonitorCheckLog, PatternStatus, TradeType | 스키마 거의 무변경. StockPrice scale2는 USD 센트에 적합 |
| **Portfolio.quantity / Trade.quantity** = `BigDecimal(19,4)` | ★ 이미 소수점 매매 대응 — 안 깨짐 |
| **관측성 퍼널** CheckFunnel/NearMiss/ExecutionResult/SignalStock | 요소명 C·A·S·L·I·M, 시장무관 |
| **매수/매도 게이트 + 재시도(3회)** (IntradayMonitorWorker, TradingStrategyService) | 슬롯·손절 -7%·익절 +20%·점수미달 hold — 규칙이 시장무관 |
| **planPurchases()** 순수 계획함수 | 슬롯선정·positionRate 상한·라운드로빈 이월 알고리즘 재사용(정수 가정만 조정) |
| **TokenProvider 캐시 패턴** | 만료 300초전 갱신·synchronized·invalidate — 토스 OAuth로 경로만 교체 |
| **두 SyncService의 영속화 계층** | Repository 저장·중복체크·REQUIRES_NEW 저장 재사용(fetch 루프만 교체) |
| **골격**: ConfigProperties·DotenvConfig·RestTemplateConfig·SchedulingConfig | 브로커 무관(RestTemplate엔 EDGAR User-Agent 인터셉터만 추가) |
| **설정 `trading.*`** max-positions·position-rate·stop-loss(7%)·take-profit(20%)·min-score·real-trading·secret·scheduler.enabled | CANSLIM 전략 파라미터 = 시장무관 |
| **알림**: NotificationService·Router·Console·**Telegram** | 전면 재사용(Telegram 글로벌) |

## 2. ✏️ 표시층/얕은 수정

| 자산 | 수정 지점 |
|---|---|
| **dashboard.html** | "원"→"$" 다수, stock.code 표기, KRX/DART 버튼 문구. ★수량은 이미 `formatDecimal(,1,4)` 소수4자리 표시 → 소수점주식 그대로 유리 |
| **monitor.js** | `ko-KR`→`en-US` 로케일, `'원'`→`'$'` 한 곳. 폴링·퍼널·리더보드 로직 무변경 |
| **NotificationEvent** | `formatMessage`의 "원"·"주"·정수포맷(`%,d`) → decimal·`$`. 레코드·fromTrade 재사용 |
| **StockController** | market enum KOSPI/KOSDAQ→NYSE/NASDAQ, 동기화 버튼 문구 |

## 3. 🔧 골격 재사용 + 어댑터/DTO 교체

| 자산 | 재사용 부분 | 교체 부분 |
|---|---|---|
| **IntradayMonitorWorker** (709줄) | 루프 3단계 오케스트레이션·매도게이트·매수게이트·재시도·planPurchases·퍼널·persistCheckLog | `isTradingHours`(재작성, 휴일캘린더), `@Scheduled`(ET존), 가격/잔고 DTO(int→decimal), `getTargetStocks` 데이터출처 |
| **TradingStrategyService** | 게이트체인·스코어합산·매도로직·자본배분식(`cash×rate/100`) | 잔고조회(KIS output2 종속)·주문실행·정수화 3곳(`intValue`/scale0)·"원" 문구 |
| **DataSyncScheduler / AutoTradingWorker / PortfolioScheduler** | try-catch·서비스위임·벌크엔트리·야간 점수 upsert·getQuarter | 크론 자정교차 재작성·KRX/DART 서비스 구현 교체·int→decimal |
| **DashboardController** | 잔고 회복탄력 캐시·퍼널·리더보드 | 잔고 DTO·존·isTradingHours(워커와 공유유틸 추출 권장) |
| **ApiConfig** | `@ConfigurationProperties` 골격·env 폴백·real/mock 분기 | Dart/Krx/KoreaInvestment 내부클래스→Edgar/Toss, 계좌 `-`분할 폐기 |

## 4. 🗑️ 폐기

| 자산 | 사유 |
|---|---|
| **KrxApiClient** + KrxApiResponse·KrxCorpDTO·KrxPriceDTO | KRX "날짜별 전종목" 구조 = 토스 "종목별 캔들"과 반대. 전면 폐기 |
| **KoreaInvestment*Response** 3종 (Balance/Order/Price) | rt_cd/output1/output2/tr_id 종속 → 토스 규격 신규(단 접근자 계약명은 유지해 호출부 변경 최소화) |
| **DART 한글계정 추출** (DartDataSyncService.extractValue, DartCompanyDTO, DartFinancialDTO) | "매출액/영업이익/당기순이익" 하드코딩 → US-GAAP 태그 |
| **corp_code_map.json** (6자리→corp_code) | SEC `company_tickers.json`(ticker→CIK)으로 대체 |
| **KakaoNotificationService** | 한국 전용 메신저, Telegram으로 충분 |
| **KIS tr_id/계좌분할/hashkey/ORD_DVSN** 규약 | 토스에 대응개념 없음 |

## 5. 🆕 신규 구축

| 신규 | 내용 |
|---|---|
| **인터페이스 seam 3개** | `BrokerClient`(placeBuy/sell·getBalance·getPrice) / `CandleClient`(종목별 일봉) / `FinancialsProvider`(CIK기반 재무) — 호출부 4곳(TradingStrategyService·IntradayMonitorWorker·PortfolioScheduler·DashboardController) 결합 해소 |
| **TossBrokerClient** | OAuth client_credentials, 소수점 금액기반(orderAmount) 주문, USD 잔고, 소수 가격. 시그니처 `placeBuy(symbol, OrderSpec)`로 재설계 |
| **TossCandleClient** | 종목별 일봉(요청당 200 + before 페이지네이션), 50/200MA 자체계산 입력 |
| **EdgarClient + 나이틀리 배치** | companyfacts.zip(~03:00 ET 재컴파일) 수집, XBRL 태그(EarningsPerShareDiluted/NetIncomeLoss/Revenues) 파싱, `roe`=NetIncome/StockholdersEquity 계산. edgartools 참고 |
| **종목 마스터 로더** | SEC company_tickers.json(ticker→CIK) — CIK 조회 + 유니버스 동시 해결 |
| **Stock 식별 확장** | `cik`(필수, 없으면 재무수집 불가)·`ticker`·`exchange`·`currency` 필드 + 생성자 확장 |
| **FinancialStatement 스키마 조정** | `fiscalQuarter` 연간(10-K) 수용 규약·분기/연간 구분 플래그, `eps/debtRatio` scale·precision 상향, `roe` 계산 소스, currency |
| **isTradingHours(미국)** | ET 9:30~16:00 + 미국 휴일캘린더. 워커·컨트롤러 3중복을 공유유틸로 |
| **CANSLIM "I"(기관수급) 판단** | 현 코드는 거래량 프록시라 이식 무손실이나, 원하면 13F 실데이터로 개선(선택) |

---

## 6. 데이터 적재 규약 — 이관 전 반드시 확정할 2건 (분석기 무수정의 전제)

1. **분기 EPS 누적/개별**: SEC 10-Q EPS가 YTD 누적일 수 있음. `QuarterlyEarningsAnalyzer.findSameQuarterPreviousYear`는 "분기값" 전제 → 적재 시 개별분기로 정규화할지 규약 확정.
2. **연간=Q4 가정**: `AnnualEarningsAnalyzer.findPreviousYearFullData`가 `fiscalQuarter==4`를 연간으로 하드가정. SEC는 연간=10-K 별도 → 연간을 어떤 fiscalQuarter값(0 또는 4=annual)으로 적재할지 결정. **이 한 줄만 맞추면 스코어 로직 재사용.**

---

## 7. 결론 — 2단계(아키텍처 설계) 진입점

**최소변경 이관 경로:** ① 인터페이스 seam 3개 추출(호출부 4곳 디커플) → ② Toss/EDGAR 어댑터 구현 → ③ int→decimal 가격 일괄 전환 → ④ Stock/FinancialStatement 스키마 확장 + 적재규약 2건 확정 → ⑤ 크론/거래시간 ET 재작성 → ⑥ 표시층 통화·로케일 → ⑦ KRX/DART/Kakao 폐기.

**재사용 비중(체감):** 분석·점수·관측성·알림·전략임계값·게이트·사이징 알고리즘 = 대부분 유지. 신규·재작성은 외부연동(어댑터)·식별스키마·시간처리에 국한. **"CANSLIM 엔진은 그대로, 브로커·데이터·시간 껍데기를 갈아끼우는" 이관.**
