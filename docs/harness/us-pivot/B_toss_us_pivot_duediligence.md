# 미국 대전환 실사 — 토스증권 공식 Open API 정밀 검증

조사일: 2026-07-09 · 방식: deep-research(5각도 병렬검색 → 18소스 페치 → 25주장 3표 적대검증, 21확정/4반증) · 선행: `A_broker_fractional_research.md`

---

## 0. 최종 판단: **부분 전환 가능 (하이브리드 필수)**

**주문·시세 실행 레이어는 토스 공식 Open API로 충분하나, CANSLIM 종목선정의 재무입력(C·A)은 외부 미국 재무 API로 새로 구축해야 한다.** 즉 "전환 가능 여부"의 답은 *가능*이되, 단일 API로는 불가능하고 **토스(주문/시세) + 외부 재무소스(펀더멘털)** 2계층 구조가 전제다.

---

## 1. 주문 스펙 (질문 1) — **문제 없음, 재사용 가능**

- 전 주문 라이프사이클 REST 제공: `POST /api/v1/orders`(생성) · `.../{id}/modify`(미체결 가격·수량 정정) · `.../{id}/cancel`(취소) · `GET /api/v1/orders`(목록) · `GET /api/v1/orders/{id}`(상세). KR/US 통합 엔드포인트. **[confidence: high, 3-0]**
- `orderType` enum에 LIMIT/MARKET. **미국주식 소수점 매수 = 금액기반 `orderAmount`, 시장가 자동 라우팅.** 지정가는 정수주식에만. **[high]**
- 체결상태 관측 가능: status(PENDING/PARTIAL_FILLED/PENDING_CANCEL/PENDING_REPLACE/CLOSED), 상세응답에 체결수량·평균체결가·수수료·세금·결제일·부분체결 포함 → **기존 주문추적·재시도 로직 이식 가능.** **[high]**
- ⚠️ 미확정: 소수점 주문의 정확한 필드(orderAmount 외 소수 *수량* 필드 존재 여부, 최소주문금액·소수 자릿수)는 "orderAmount만 가능·수량필드 전무"라는 강한 주장이 **1-2로 반증**되어 미세하게 불확실. PoC 시 실측 필요.

## 2. 데이터 커버리지 (질문 2) — **핵심 갭. 여기가 전환의 관건**

| 입력 | 토스 API | 판정 |
|---|---|---|
| (a) 일봉 65주+ 히스토리 | `GET /api/v1/candles` interval=1d, **요청당 max 200**, `before` 커서 페이지네이션 | ⚠️ 가능하나 백필 페이지네이션 구현 필요(~455거래일=여러 페이지) |
| 50/200일 이동평균 | API 직접 미제공 | ⚠️ 캔들로 **자체 계산** |
| (b) 분기/연간 EPS·순이익 (CANSLIM C·A) | **필드 전무** | ❌ **불가 → 외부소스 필수** |
| (c) 거래량 | 캔들에 포함 | ✅ |
| (d) 업종/섹터 분류 | **필드 전무**(GICS/섹터 없음) | ❌ **외부소스 필요** |

- 근거: `/api/v1/stocks` 마스터 필드 = symbol,name,englishName,isinCode,market,securityType,isCommonShare,status,currency,listDate,delistDate,sharesOutstanding,leverageFactor,koreanMarketDetail **뿐.** 전 카테고리(Auth/Market Data/Stock Info/Market Info/Account/Order)에 fundamentals 엔드포인트 없음. **[high, 3-0]**
- 2차 블로그(Pulse-Know)의 "섹터 제공" 주장은 1차 openapi.json으로 **직접 반박됨** → 신뢰도 낮게 취급.

### 외부 재무 소스 후보 (CANSLIM C·A 갭 충전)

| 소스 | 분기/연간 EPS·순이익 | 히스토리 | 무료 한도 | 비고 |
|---|---|---|---|---|
| **FMP** | ✅ 표준화 손익+Earnings(EPS actual/date) | 30년+ 일별, 10년+ 실적 | ~5년/~5분기 | 올인원 적합 |
| **EODHD** | ✅ Q1-Q4 분리 라벨 | US 1985년~, 11,000티커, 40만+ 레코드 | 제한 | 최장 히스토리 |
| **Alpha Vantage** | ✅ INCOME_STATEMENT/EARNINGS + 50+ 기술지표(SMA/EMA) | 20년+(full=프리미엄) | **25 req/day** | 무료 한도 너무 낮음(하루 6-8종목) |
| **SimFin** | ✅ 무료 다운로드 P&L·EPS·순이익 | — | ~12개월 지연 | C-score 신선도 저하 |

- **무료 티어는 유량·히스토리 제한이 커 US 유니버스 스크리닝엔 유료 사실상 필수.** Alpha Vantage 무료 25req/day는 수백종목 스크리닝 불가, 유료 최저 $49.99/mo. SimFin 무료는 ~12개월 지연으로 C-score(분기실적 신선도) 부적합 소지. **[high]**

## 3. 운영 제약 (질문 3) — **카테고리 존재 확인, 정량수치는 별도 실사 필요**

- OAuth2(Auth 카테고리), USD/KRW 환율 조회 엔드포인트, KR/US 휴장일 달력 **존재 확인.** **[high]**
- ❗ **이번 라운드 미확정(정량):** OAuth2 토큰 발급/갱신 세부 흐름, 초당·일당 rate limit 구체 숫자, 실전/모의(paper) 구분, API 비용/수수료, **원화→달러 자동환전 실행 방식(주문시 자동 vs 사전환전/통합증거금)**, 미국장 운영시간·서머타임·프리/애프터마켓 주문, **해외주식 양도세(연 250만원 공제·22%) 처리·신고**. → 세무·운영 영역은 스펙문서 밖이라 PoC/직접 문의로 별도 확인.
- (2차 출처 힌트, 미검증: 토큰 1시간 만료·50분 리프레시 권장, 계좌콜은 `X-Tossinvest-Account` 헤더, rate limit 공식 미공개·REST 폴링 ~1초 간격 권장.)

## 4. 재사용 vs 신규구축 구분표 (질문 4)

| 레이어 | 국내(현행) | 미국 전환 시 | 판정 |
|---|---|---|---|
| 주문 실행(생성/정정/취소) | KIS order-cash | 토스 orders API | ♻️ **이식** (필드 매핑만) |
| 체결/포지션 추적 | KIS 잔고조회 | 토스 orders 상세·account | ♻️ **이식** |
| 시세·일봉(차트/컵앤핸들) | KIS 캔들 | 토스 candles(+페이지네이션) | ♻️ **이식(페이지네이션 신규)** |
| 이동평균 50/200 | 계산 | 동일 계산(캔들 입력) | ♻️ **재사용** |
| CANSLIM C·A(분기/연간 EPS·순이익) | 국내 재무 | ❌ 토스 없음 → 외부 API | 🆕 **신규 데이터소싱 레이어** |
| 섹터/업종 그룹핑 | — | ❌ 토스 없음 → 외부 API | 🆕 **신규** |
| 인증 | KIS appkey | OAuth2 client-credentials | 🆕 **신규 인증 어댑터** |
| 환전·세제·장시간 | 해당無 | 환율조회 有, 자동환전·양도세 미확정 | 🆕 **신규 운영로직** |

**한 줄 결론:** CANSLIM 스코어링 *알고리즘*은 그대로, 그 *데이터 공급*과 *브로커 어댑터·환전/세제 운영층*을 새로 짜는 전환. 실행·차트는 저비용 이식, 리스크·공수는 **재무데이터 소싱 레이어**에 집중된다.

---

## 5. 남은 미해결(다음 단계 PoC 항목)

1. 토스 US 소수점 주문 실제 필드 스펙(orderAmount 외 소수수량? 최소금액·자릿수) — 실계좌 소액 왕복 체결.
2. 운영 정량치: rate limit 숫자, 실전/모의 구분, 비용, 자동환전 방식, 통합증거금 여부.
3. 미국장 시간·서머타임·프리/애프터 주문 + 해외 양도세 원천/신고 절차.
4. 외부 재무 API 최종 1개 선정 — 신선도(C-score 분기지연) vs 비용 vs 커버리지. SimFin 무료(~12개월 지연) 부적합 가능, FMP/EODHD 유료 유력.

## 6. 주의(시간민감)

유량·가격 정책은 자주 변동(Alpha Vantage 무료 500→100→25/day로 축소된 이력). 계약 전 각 벤더 pricing 재확인 필수. 토스 주문·데이터 핵심주장은 서버 소유 canonical `openapi.json`(1차) 기준이라 신뢰도 높음. 2차 블로그의 "섹터 제공"은 반박됨.

## 출처(1차 우선)
- 토스 공식: developers.tossinvest.com/docs, /docs/order-history, /llms.txt, openapi.tossinvest.com/openapi-docs/latest/openapi.json
- 커뮤니티: github.com/JungHoonGhae/tossinvest-cli, /BEOKS/tossinvest-skill, /dd3ok/tossinvest-api-skill, /NomaDamas/k-skill
- 재무소스: site.financialmodelingprep.com, eodhd.com, alphavantage.co, simfin.com, finnhub.io
