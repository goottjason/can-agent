# 무료 미국 재무데이터 확보 실사 — CANSLIM C·A 입력 $0 조달

조사일: 2026-07-09 · 방식: deep-research(5각도, 83주장 추출 → 75검증, 71확정/4반증) · 브랜치: `us-pivot` · 선행: `B_toss_us_pivot_duediligence.md`
※ 종합 에이전트 1개가 구조화출력 실패로 워크플로우는 error 종료됐으나, 검색·페치·검증(101/102 에이전트)은 완료 → 저널에서 복구·직접 종합.

---

## 0. 결론: **월 $0 조합 성립 — SEC EDGAR(펀더멘털·섹터) + 토스(시세·주문)**

CANSLIM의 C(분기 EPS)·A(연간 EPS)와 섹터 분류를 **완전무료·공식·상업사용 가능**한 SEC EDGAR로 조달할 수 있다. 토스가 못 주는 재무 공백이 **데이터 비용 0원**으로 메워진다. **단, 미국 국내 상장사(domestic filer) 한정** — ADR/외국기업은 분기 EPS를 EDGAR에 안 낸다(아래 4.1).

---

## 1. SEC EDGAR — **압도적 1순위 (완전무료·공식·상업OK)** [confidence: high, 다수 3-0]

- **비용/인증:** 완전 무료. API 키·등록 불필요. **User-Agent 헤더(앱명+이메일)만** 필수(없으면 차단).
- **유량:** IP당 **10 req/s, 일일 상한 없음.** 초과 시 일시 스로틀(영구밴 아님). 실무 8 req/s 권장. 재무는 분기 1회 갱신이라 나이틀리 배치에 차고 넘침.
- **★ 나이틀리 벌크 ZIP:** `companyfacts.zip`(전 종목 XBRL 재무, ~7GB) + `submissions.zip` + `company_tickers.json`(~1MB, 티커→CIK). **매일 ~03:00 ET 재컴파일** → 전 미국기업 펀더멘털을 **per-ticker 유량 걱정 없이 한 번에** 수집 가능.
- **CANSLIM 입력 직결:** `companyconcept`/`companyfacts` 엔드포인트가 us-gaap XBRL 태그로 **EarningsPerShareDiluted(희석EPS), NetIncomeLoss(순이익), Revenues**를 10-Q(분기)·10-K(연간)에서 FY/Q1-Q3 구분해 제공. → **C·A 점수 산출 가능.**
- **섹터 분류(GICS 무료 대체):** SEC가 전 filer에 **SIC 코드** 부여, 목록을 SEC.gov에 **무료·무라이선스** 공개(예 2082=MALT BEVERAGES). 상위 'Office' 그룹핑(제조/에너지·운송/부동산 등)으로 굵은 섹터도 가능. → **그룹 상대강도 구현 가능.**
- **★ 상업사용 가능:** 미국 정부 공공데이터 → 상업 자동매매 시스템에 써도 라이선스 제약 없음. (아래 상용 API 대비 결정적 우위.)
- **파싱 도구:** `edgartools`(Python) — `download_edgar_data(facts=True)`로 Company Facts(~2GB) 로컬 캐시 후 `income_statement()`·`get_facts()` **오프라인 동작** → SEC 재호출 없이 나이틀리 캐싱. XBRL 태그 정규화도 처리.

## 2. yfinance — **프로덕션 부적합** [high]

- 비공식 Yahoo 스크래퍼. **2025-04-29 Yahoo가 TLS 핑거프린팅 도입 → 전면 429**(브라우저·curl은 되는데 Python requests만 차단). 이슈 "not planned"로 종료, 무보증.
- 문서화 안 된 유량(~360 req/hr 추정), ~950티커 후 429, 예고 없이 붕괴(AWS Lambda 등 서버배포 직격). `curl_cffi` Chrome 위장은 취약한 임시방편.
- **판정:** 가끔 조회·소규모 백테스트만. **지속 자동매매 데이터소스로 쓰지 말 것.**

## 3. 무료 티어 상용 API — **보조용, 프로덕션 주력 불가** [high]

| API | 무료 한도 | 상업사용 | CANSLIM 재무 | 판정 |
|---|---|---|---|---|
| **Finnhub** | 300 calls/day, 60/min | ❌ 제한 | 펀더멘털 有 | 프로토타입만 |
| **FMP** | 250 req/day, EOD only | ❌ 개인용(공개/멀티유저 표시·재배포 금지)¹ | 분기/연간 有 | 테스트만 |
| **Alpha Vantage** | **25 req/day**, 5/min | ❌ 비상업 | 손익/EARNINGS 有 | 한도 너무 낮음 |
| **Polygon.io** | 무료 없음 | — | — | 최저 $99/mo |
| **Marketstack** | 100 req/**month**, CC필수 | 제한 | EOD only | 부적합 |
| **EODHD** | 매우 제한적 | 제한 | 有 | 연결확인용 |

¹ FMP 제약은 검증에서 "모든 상업사용 금지"는 과장으로 반증 — 정확히는 **공개/멀티유저 플랫폼 표시·재배포 금지**(개인 플랜). 여전히 자동매매 서비스엔 부적합.
- **공통 문제:** 대부분 **비상업 약관** + 낮은 일일 한도 → 상업/멀티유저 CANSLIM 스크리너의 **주력 재무소스로 불가.** EDGAR 대비 이점 없음.

## 4. 한계와 보완 지점

### 4.1 ★ EDGAR 커버리지 함정 (검증에서 반증으로 드러남) — 중요
- "NYSE/NASDAQ 전종목 커버"는 **과장.** **외국기업(foreign private issuer)·ADR은 분기 10-Q를 안 낸다** — 연간 20-F + 수시 6-K만 제출, XBRL도 us-gaap 아닌 **ifrs-full** 태그. → **ADR은 CANSLIM의 C(분기 EPS)를 EDGAR로 못 구함.**
- **대응:** 유니버스를 **미국 국내 보통주로 한정**(ADR/외국기업 스크린아웃)하면 EDGAR로 100% 무료 충족. ADR도 매매하려면 그 종목만 별도 소스 필요.
- 부가: 회사별 XBRL 태그 불일치(EPS를 EarningsPerShareBasic/Diluted 혼용 등)로 파싱 정규화 필요 → edgartools가 상당부분 흡수.

### 4.2 무료로 부족할 때 최소 유료 보완 지점
- (a) **ADR/외국기업 분기실적**, (b) 애널리스트 컨센서스·추정(CANSLIM 필수 아님), (c) XBRL 씨름 없이 정규화된 펀더멘털 원할 때 → **Finnhub/FMP 유료** 최소플랜. 단 **순수 미국 국내주 C+A는 EDGAR 단독 $0로 충분.**

## 5. 추천 아키텍처 — 데이터 비용 $0 파이프라인

```
[나이틀리 배치 ~03:30 ET 이후]
  SEC EDGAR companyfacts.zip + submissions.zip + company_tickers.json
    → edgartools 로컬 캐시(~2GB)
    → 분기 EPS(EarningsPerShareDiluted)·순이익(NetIncomeLoss)·매출 파싱 → C·A 점수
    → SIC 코드 → 섹터 그룹 상대강도
  [유니버스: 미국 국내 보통주만 (ADR/foreign filer 제외)]

[장중/주문]
  토스 Open API: 일봉 캔들(50/200MA 자체계산)·거래량·시세·소수점 주문
```
- 두 소스 모두 **공식·상업사용 가능**, 합산 데이터비용 **$0/월**. 펀더멘털은 분기 갱신이라 캐싱으로 유량 무부담.

## 6. 주의(시간민감)
무료 한도·약관은 자주 변동(Alpha Vantage 500→100→25/day 축소 이력, Polygon $29 Starter 폐지, yfinance 붕괴). 계약/구현 전 각 벤더 재확인. EDGAR 유량정책(10req/s, 2021-07-27 시행)은 공식·안정적. SIC 목록은 2021-06 갱신으로 안정적.

## 출처(1차 우선)
- SEC EDGAR: sec.gov/search-filings/edgar-application-programming-interfaces, data.sec.gov, sec.gov SIC code list, sec.gov filergroup 공정접근정책(10req/s)
- edgartools: readthedocs(local-storage, companyfacts)
- 상용: financialmodelingprep.com/terms-of-service, finnhub.io, alphavantage.co, polygon.io, marketstack.com, eodhd.com
- yfinance: github.com/ranaroussi/yfinance issues(#2422 등, TLS 핑거프린팅·429)
