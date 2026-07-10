# 브로커 마이그레이션 사전조사 — 국내주식 소수점 자동주문 API 존재 여부

조사일: 2026-07-09 · 대상: KOSPI/KOSDAQ CANSLIM 자동매매(CAN-Agent) · 핵심 제약: KIS OpenAPI 국내 order-cash `ORD_QTY` 정수 전용

---

## 1. 결론 요약 (핵심)

1. **국내주식(KR) 소수점을 공개 REST/OpenAPI로 자동 주문할 수 있는 증권사는 조사 범위에서 사실상 확인되지 않음(0곳).** 가설이 맞다.
2. 국내 소수점은 업계 공통으로 **신탁 수익증권(1좌=0.000001주) 방식**이며, 증권사가 고객 주문을 취합해 익일 온주(1주)로 일괄 처리한다. 이 배치 구조상 실시간 개별 API 주문과 근본적으로 맞지 않아 **전 증권사가 앱/MTS 전용**으로만 제공한다.
3. **토스증권만 예외적으로 "해외(미국)주식 소수점"을 공식 Open API로 자동주문 가능** — 단 금액(orderAmount) 기반 시장가에 한함. **국내(원화) 소수점은 토스 공식 API도 미지원**(비공식 웹 API 경로만 존재, 약관 위반 소지).
4. 한국투자(KIS)의 해외 소수점은 **미니스탁 앱/MTS 전용 배치**이며 OpenAPI로는 정수 주문만 가능(현 시스템 제약과 동일).
5. **권고: 국내 소수점 목적의 브로커 마이그레이션은 실효 없음.** 소수점이 반드시 필요하면 전략을 미국주식으로 옮겨 **토스증권 공식 API(해외 소수점)** 또는 현 KIS 정수매매 유지를 택하는 것이 현실적이다.

---

## 2. 비교표

| 증권사 | 국내소수점(앱) | 공개 REST/OpenAPI | API로 **국내** 소수점주문 | 해외(미국) 소수점 API | 근거 |
|---|---|---|---|---|---|
| **한국투자(KIS)** | △ MTS/미니스탁(신탁·배치) | ✅ REST(KIS Developers) | ❌ 불가 (`ORD_QTY` 정수, "향후 소수점 활성화 시" 언급=현재 미지원) | ❌ 미니스탁 앱/MTS 전용 배치, OpenAPI 정수만 | [1][2][3][14][15] |
| **키움증권** | ✅ 소수점매매(신탁 수익증권) | ✅ KIWOOM REST API(2025~) | ❌ 확인 안 됨 (`ord_qty` 문자열·정수 예시, 소수점 주문 문서·언급 없음) | ❌ API 소수점 언급 없음 | [4][5][6][11] |
| **NH투자(나무)** | ✅ (앱) | △ QV Open API = **C++ DLL 전용**(REST 아님) | ❌ (소수점 관련 문서 없음, DLL 배치 구조) | ❌ 확인 안 됨 | [7][8] |
| **KB증권** | ✅/해외 위주(신탁) | △ 핀테크스토어/KBFG API 포털(계좌개설·해외소수점 서비스 중심) | ❌ 국내 소수점 API 명시 없음 | ❌ 서비스는 앱 소수점, 개인 주문 API 미확인 | [9][16] |
| **미래에셋** | ✅ 소수단위매매(신탁 약관 존재) | △ 자동주문시스템(서버형 조건주문, 개방 REST 리테일 주문 제한적) | ❌ 소수점 주문 API 미확인 | ❌ 미확인 | [10][17] |
| **신한투자** | ✅ 소수점 투자(앱) | △ 신한i Indi = **COM/DLL**(REST 일부 미지원) | ❌ 소수점 API 미확인 | ❌ 미확인 | [12][18] |
| **삼성증권** | ✅ 해외 소수점(신탁, 일반주식 전환 기능) | △ POP API 제한적 | ❌ 미확인 | ❌ 해외 소수점은 앱 서비스 | [13][19] |
| **토스증권** | ❌ **공식 API 미지원**(웹 전용, 비공식만) | ✅ 공식 Open API(OAuth2, 2025~) | ❌ 원화(KRW) 소수점 = 공식 API ❌ | ✅ **미국주식 소수점 = 공식 API 지원**(orderAmount 금액기반, US 시장가, v1.1.5~) | [20][21][22][23] |

범례: ✅ 지원/확인 · △ 부분·조건부 · ❌ 미지원/불가 · 미확인=공식 근거 못 찾음(과장 금지)

---

## 3. 상세 판정

### 3.1 국내 소수점 = 전 증권사 앱 전용(신탁 배치)인 이유
- 국내주식 소수점거래는 "회사가 고객의 소수단위 주문을 취합해 온주 단위로 거래하고 취득 주식을 예탁결제원에 신탁, 발행받은 수익증권(1좌=0.000001주)을 고객에 분배"하는 **신탁 수익증권 구조**다. [출처: 미래에셋 약관 [17], 키움 소수점매매 안내 [11]]
- 이 구조는 **실시간 개별 체결이 아니라 일괄(배치) 처리**이므로, 실시간 REST 주문 API로 소수 수량을 그대로 넣는 모델과 맞지 않는다. 그래서 조사한 모든 증권사가 소수점을 MTS/전용앱(미니스탁 등)으로만 노출한다.

### 3.2 한국투자(KIS) — 사용자 현재 제약 재확인
- KIS OpenAPI 튜토리얼/문서에서 "주식은 한 주 단위로 거래, **향후 소수점 거래가 활성화되면** 해결"이라는 서술 → **현재 OpenAPI 국내 주문은 정수 전용**임을 방증. [1][2]
- 해외 소수점은 별도 **미니스탁 앱**(700여 종목, 익일 온주 취합 배치)·MTS 기능이며 OpenAPI 경로가 아니다. [14][15]
- 결론: **KIS를 떠나도 얻을 것이 없다**(다른 증권사도 국내 소수점 API 없음).

### 3.3 키움 REST API
- 2025년 KIWOOM REST API 출시(멀티OS·Python/Java). 국내주식 주문·정정·취소 지원, `ord_qty`는 문자열로 전송되는 **정수 수량 예시**. [4][5][6]
- 다만 **소수점 주문을 API로 지원한다는 공식 문서·언급을 찾지 못함**. 키움 소수점매매 서비스 자체는 신탁 기반 앱 서비스로 존재. → API 국내 소수점 = 근거 없음(미지원으로 판단).

### 3.4 NH(QV) / 신한(i Indi) — REST조차 아님
- NH QV Open API는 **Windows C++ DLL 라이브러리 전용**(REST 아님), 문서 빈약. [7][8]
- 신한 i Indi는 **COM/DLL 기반**, REST는 일부 명령 미지원. [12][18]
- 두 곳 모두 소수점 API 근거 없음 + 배포 형태부터 서버형 자동매매에 불리.

### 3.5 토스증권 — 유일한 "소수점 공식 API" (단, 해외 전용)
- 토스 공식 Open API(2025 정식 공개, OAuth2, 국내 KRX + 미국주식 시세·잔고·주문). 주문 엔드포인트 `POST /v1/orders`. [20][21]
- **미국주식 소수점 주문을 공식 API로 지원**: 금액(orderAmount) 기반, US 시장가로 자동전환, v1.1.5부터 US 시장가 소수점 매도까지. [21][23]
- **원화(국내) 소수점 매수는 공식 API ❌** — 오직 비공식 CLI(tossctl)가 토스 내부 웹 API로 구현(예고없이 변경·약관 위반 소지, 서비스 신뢰성 없음). [22][23]

---

## 4. 대안 경로

### (A) 해외주식 소수점 API 전환
- **토스증권 공식 Open API**가 미국주식 소수점(금액기반 시장가)을 실제로 제공 → 소수점이 목적이면 **전략을 미국주식으로 이식**하는 것이 유일하게 "공식 API + 소수점"이 성립하는 조합.
  - 제약: 시장가·금액기반 위주(지정가 소수 주문 여부는 공식 스펙 추가확인 필요=미확인), 비공식이 아닌 공식 키 사용 전제, 미국장 운영시간·환전·세제(양도세) 고려 필요.
- KIS 해외 소수점은 **API 불가(미니스탁 앱 배치)** → 대안에서 제외.

### (B) 소수점 없이 정수로 우회(현 시스템 유지)
- 현재 CAN-Agent가 이미 하는 **정수 단위 저가주 분산** 방식이 국내 자동매매에서 가장 안정적·합법적 경로. 브로커 교체 없이 유지 권장.
- 포지션 사이징을 "금액→정수 수량 반올림/버림"으로 최적화(잔여 현금 처리·최소주문금액 관리)하는 개선이 마이그레이션보다 리스크·비용이 훨씬 낮음.

---

## 5. 권고

- **(결정) 국내 소수점 목적의 브로커 마이그레이션 = 실효 없음.** 공개 REST API로 국내 소수점 자동주문을 지원하는 증권사가 없다(가설 검증 완료). KIS를 떠날 이유가 이 목적으론 성립하지 않는다.
- **소수점이 전략상 필수라면** → 미국주식으로 전략 전환 후 **토스증권 공식 Open API(해외 소수점)** PoC. 다음 단계:
  1) 토스 developers 포털에서 `openapi.json` 스펙 확보, US 소수점 주문 필드(금액기반)·지정가 가능여부·유량/비용 확인.
  2) 모의/소액 실계좌로 소수점 매수·매도 왕복 체결·정산 검증.
  3) 환전·세제·운영시간을 반영한 백테스트 재설계.
- **소수점이 필수가 아니라면** → **현 KIS 정수매매 유지**가 최선. 정수 사이징 로직 정교화에 리소스를 쓰는 편이 ROI가 높다.

---

## 6. 출처 목록

- [1] 파이썬 한국/미국 주식 자동매매(KIS) — https://wikidocs.net/165185
- [2] KIS Developers 개발자센터 — https://apiportal.koreainvestment.com/intro
- [3] koreainvestment/open-trading-api — https://github.com/koreainvestment/open-trading-api
- [4] KIWOOM REST API — https://openapi.kiwoom.com/
- [5] 키움증권 'KIWOOM REST API' 서비스 출시(한국경제) — https://www.hankyung.com/article/202503243715P
- [6] 키움 REST API 가이드 — https://openapi.kiwoom.com/guide/index?dummyVal=0
- [7] NH QV Open API(bekker/qvopenapi-rs) — https://github.com/bekker/qvopenapi-rs
- [8] NH투자증권 Open API 안내 — https://www.nhsec.com/WMDoc.action?viewPage=%2FguestGuide%2Ftrading%2FopenAPI.jsp
- [9] KB증권 오픈API 계좌개설·해외 소수점(미디어펜) — https://www.mediapen.com/news/view/741088
- [10] 미래에셋 자동주문시스템 유의사항 — https://securities.miraeasset.com/hki/hki3072/n09.do
- [11] 키움 소수점매매 서비스 — https://www.kiwoom.com/h/help/trade/VHelpDecimalPointView
- [12] 신한 Open API — https://openapi.shinhan.com/
- [13] 삼성증권 해외주식 소수점 자동 일반주식 전환 안내 — https://www.samsungpop.com/ (customer guide notice 22078)
- [14] 미니스탁(한국투자증권) — https://mini.koreainvestment.com/
- [15] 한투MTS '한국투자' 美주식 소수점 도입(파이낸셜뉴스) — https://www.fnnews.com/news/202506040944222842
- [16] KB Group Open API Portal — https://apiportal.kbfg.com/
- [17] 미래에셋 국내주식 소수단위매매 약관 — https://securities.miraeasset.com/hki/hki3031/r24.do
- [18] 신한i인디 파이썬 매뉴얼(WikiDocs) — https://wikidocs.net/book/2167
- [19] 삼성증권 이용안내 — https://www.samsungpop.com/
- [20] 토스증권 Open API 가이드 2026(Pulse Know) — https://www.pulse-know.com/toss-invest-open-api-guide-2026/
- [21] 토스증권 Open API 공식 — https://corp.tossinvest.com/ko/open-api · https://developers.tossinvest.com/docs/market-data
- [22] tossinvest-cli(비공식 CLI, 소수점 주문 = WTS 전용/비공식) — https://github.com/JungHoonGhae/tossinvest-cli
- [23] 토스증권 오픈 API 시장(다음뉴스) — https://v.daum.net/v/20260521073602305
- [24] 국내주식 소수단위주식거래 유의사항(미래에셋) — https://securities.miraeasset.com/hki/hki3032/n27.do
- [25] 소수점 주식(나무위키) — https://namu.wiki/w/소수점 주식
