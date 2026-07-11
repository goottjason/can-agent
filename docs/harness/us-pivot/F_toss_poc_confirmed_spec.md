# 토스 Open API 실확정 스펙 (PoC 라이브 검증, 2026-07-11)

실 라이브 키(tsck_live)로 서버(168.107.31.154, 허용IP 등록됨)에서 실호출해 확정. openapi.json(340KB) 교차 확인.
**모든 성공 응답은 최상위 `{"result": ...}`로 래핑, 에러는 `{"error":{code,message,requestId,data}}`.** 어댑터는 `result`를 벗겨야 한다.

## 1. 인증 — ✅ 코드 일치
`POST /oauth2/token` (form): `grant_type=client_credentials`,`client_id`,`client_secret` → `access_token`,`token_type`,`expires_in`(=86399, ~24h). TossTokenProvider 정확(주석의 "1시간 만료"만 24h로).

## 2. 계좌 — 계좌콜 헤더 확정
`GET /api/v1/accounts` → `result[]` of `{accountNo, accountSeq, accountType}`. 실계좌: accountNo=10701020640, **accountSeq=1**.
**계좌콜 헤더 `X-Tossinvest-Account: <accountSeq>`** (openapi 확정: accounts 응답의 accountSeq값). buying-power/holdings/orders에 필요.

## 3. 매수가능현금(USD) — ❌ 경로·필드 둘 다 틀렸음
- 우리 코드: `GET /api/v1/assets` → **404 (그런 경로 없음)**, 필드 `availableCash` 가정.
- **실제**: `GET /api/v1/buying-power?currency=USD` (currency 필수) + 계좌헤더 → `result.cashBuyingPower`(string), `result.currency`. (라이브값 "0" = 빈 계좌)
- → USD 예수금 = **`cashBuyingPower`** (buying-power 엔드포인트). `availableCash` 아님.

## 4. 보유/평가 — ❌ 구조 다름(통화별 중첩)
`GET /api/v1/holdings` + 계좌헤더 → `result`(HoldingsOverview):
- `totalPurchaseAmount:{krw,usd}` · `marketValue.amount:{krw,usd}`(+amountAfterCost) · `profitLoss:{amount:{krw,usd},amountAfterCost,rate,rateAfterCost}` · `dailyProfitLoss` · `items:[]`
- `items[]`(HoldingsItem): `symbol,name,marketCountry,currency,quantity,lastPrice,averagePurchasePrice,marketValue,profitLoss,dailyProfitLoss,cost`
- ★금액은 **`{krw,usd}` 중첩 객체**(우리 BrokerBalance는 flat). 매핑: 총평가 USD = `marketValue.amount.usd`, 보유 평가액 = item.marketValue(.amount.usd), 평균가 = `averagePurchasePrice`.

## 5. 현재가 — 우리 미구현(0) → 구현 가능
`GET /api/v1/prices?symbols=AAPL` (symbols 필수, 배치) → `result[]` of `{symbol,timestamp,lastPrice,currency}`. → **getCurrentPrice = `result[0].lastPrice`**. (TossBrokerAdapter.getCurrentPrice 0 반환 → 실구현)

## 6. 환율 — ⚠️ 파라미터 필요(필드명은 맞음)
`GET /api/v1/exchange-rate?baseCurrency=USD&quoteCurrency=KRW` (둘 다 필수) → `result.rate`(="1506.9"),`midRate`,`basisPoint`,`rateChangeType`,`validFrom`,`validUntil`. → `rate` 맞음, **파라미터 2개 전송 + result 언랩** 필요.

## 7. 캔들 — 🔴 조용한 0건 버그 확정
`GET /api/v1/candles?symbol=AAPL&interval=1d&count=3[&before=&adjusted=true]` → **`{"result":{"candles":[...],"nextBefore":...}}`**.
- 우리 TossCandleClient는 `candles`/`nextBefore`를 **루트에서** 찾음 → 실제는 `result.candles`/`result.nextBefore` → **빈배열→시세동기화 0건 조용한 실패**. **result 언랩 추가 필수.**
- 캔들 필드 `timestamp(ISO8601 +09:00),openPrice,highPrice,lowPrice,closePrice,volume,currency` — 필드명 일치. `adjusted`(수정주가, 기본 true) 파라미터 존재.

## 8. 주문 생성 — 필드명 맞음, 소수매도 방식 교정
`POST /api/v1/orders`, 바디 oneOf:
- **수량기반**(required `symbol,side,orderType,quantity`): `side`(BUY/SELL),`orderType`(LIMIT/MARKET),`quantity`(string decimal — 기본 정수; **소수는 US MARKET SELL만**),`price`(string; LIMIT 필수·MARKET 전달불가),`timeInForce`(DAY/CLS),`clientOrderId`,`confirmHighValueOrder`.
- **금액기반**(US MARKET 전용, required `symbol,side,orderType,orderAmount`): `orderType=MARKET`,`orderAmount`(string 달러). 정규장만.
- 응답 → `result.orderId`(+`clientOrderId`).
- 규칙: **소수 매수 = orderAmount+MARKET**(✓우리 설계), **소수 매도 = quantity(소수)+MARKET+SELL**(❌우리 설계는 Limit — 교정), LIMIT은 price 필수/MARKET은 price 금지, US 가격 정밀: <$1 4자리·≥$1 2자리.
- `clientOrderId` = **멱등성 키(10분)** — 중복주문 방지에 사용 권장.

## 9. 주문 상세/상태 — 체결정보 execution 중첩, enum 교정
`GET /api/v1/orders/{orderId}` → `result`(Order): `orderId,symbol,side,orderType,timeInForce,status,price,quantity,orderAmount,currency,orderedAt,canceledAt,execution`.
- `execution`(OrderExecution): `filledQuantity,averageFilledPrice,filledAmount,commission,tax,filledAt,settlementDate`. → 우리 filledQty=`execution.filledQuantity`, avgFillPrice=`execution.averageFilledPrice`, commission/tax=`execution.commission/tax`.
- **OrderStatus enum(실제)**: `PENDING,PENDING_CANCEL,PENDING_REPLACE,PARTIAL_FILLED,FILLED,CANCELED,REJECTED,CANCEL_REJECTED,REPLACE_REJECTED,REPLACED`. → 우리 `CLOSED` **없음**(→`FILLED`), `CANCELED/REJECTED` 등 추가.
- 정정 `POST /api/v1/orders/{orderId}/modify`, 취소 `POST /api/v1/orders/{orderId}/cancel`.

## 10. 부가(선택 활용)
- `/api/v1/sellable-quantity`(매도가능수량), `/api/v1/commissions`(수수료), `/api/v1/market-calendar/US`(미국 휴장일 — P7 정적폴백 대체 가능), `/api/v1/conditional-orders`(조건부).

---
## 코드 교정 요약(우리 가정 vs 실제)
| 대상 | 우리 코드 | 실제 | 조치 |
|---|---|---|---|
| 응답 래핑 | 루트 직접 | 전부 `result` 래핑 | **언랩 추가(전 어댑터)** |
| 캔들 | root.candles | result.candles | 🔴 버그수정(0건) |
| 잔고현금 | /assets availableCash | /buying-power?currency=USD cashBuyingPower | 경로·필드 교체 |
| 보유 | flat holdings | /holdings result.items(+{krw,usd} 중첩) | 매핑 재작성 |
| 현재가 | 0(미구현) | /prices?symbols lastPrice | 구현 |
| 환율 | 파라미터無 | ?baseCurrency&quoteCurrency | 파라미터 추가 |
| 소수매도 | Limit(qty,price) | quantity(소수)+MARKET+SELL | 교정 |
| 주문상태 | CLOSED | FILLED(+CANCELED/REJECTED) | enum 교정 |
| 체결필드 | 평면 | execution.* | 매핑 |
| 계좌지정 | 헤더명만 가정 | X-Tossinvest-Account=accountSeq | 확정·accountSeq 조회 |
| 토큰만료 | 1h 주석 | 24h | 주석 |
