# CAN Agent

William O'Neil의 CANSLIM 투자 전략과 컵앤핸들 패턴을 기반으로 한 자동 주식 매매 시스템

---

## 기술 스택

| 구분 | 기술 |
|------|------|
| Language | Java 17 |
| Framework | Spring Boot 3.3 |
| Template | Thymeleaf |
| ORM | Spring Data JPA |
| DB | H2 (개발), PostgreSQL (운영) |
| Build | Gradle |

---

## 프로젝트 구조

```
src/main/java/com/canagent/
├── CanAgentApplication.java    # 메인 클래스
├── config/                     # 설정, 스케줄러, 외부 API 설정
├── domain/                     # 엔티티, 값 객체
│   ├── stock/                  # 주식 정보
│   ├── analysis/               # 기술적 분석 (CANSLIM, 컵앤핸들)
│   ├── trading/                # 매매 기록, 포지션
│   └── portfolio/              # 포트폴리오
├── service/                    # 비즈니스 로직
├── repository/                 # JPA Repository
├── worker/                     # 자동매매 워커 (스케줄링)
├── api/                        # REST API
└── web/                        # 타임리프 컨트롤러
```

---

## 데이터 모델

| 엔티티 | 테이블 | 설명 |
|--------|--------|------|
| Stock | stocks | 주식 기본 정보 (코드, 이름, 업종) |
| StockPrice | stock_prices | 주가 데이터 (OHLCV) |
| FinancialStatement | financial_statements | 재무제표 (매출, 영업이익, EPS) |
| CupAndHandlePattern | cup_and_handle_patterns | 컵앤핸들 패턴 감지 결과 |
| Trade | trades | 매매 기록 |
| Portfolio | portfolios | 포트폴리오 (보유 주식) |

---

## 외부 API 연동

### DART API (재무제표)
- Base URL: `https://opendart.fss.or.kr/api`
- 엔드포인트: `/fnlttMultiAcnt.json` (재무제표), `/company.json` (기업정보)
- 인증키 발급: https://opendart.fss.or.kr/

### KRX API (주가 정보)
- Base URL: `https://apis.data.go.kr/1160100/service/getStockPriceInfo`
- 공공데이터포털 API 사용
- 인증키 발급: https://www.data.go.kr/

### 환경 변수

```bash
export DART_API_KEY=your_dart_api_key
export KRX_API_KEY=your_krx_api_key
```

### 주요 클래스

| 클래스 | 역할 |
|--------|------|
| ApiConfig | API 설정 관리 |
| DartApiClient | DART API 호출 |
| KrxApiClient | KRX API 호출 |

---

## CANSLIM 분석 시스템

### 분석 요소

| 요소 | 설명 | 배점 |
|------|------|------|
| C: 분기 실적 | 분기별 EPS 성장률 | 25점 |
| A: 연간 실적 | 연간 EPS 성장률 | 25점 |
| S: 수급 | 거래량/가격 변화 분석 | 20점 |
| M: 시장 방향 | 50/200일 이평선 분석 | 20점 |
| L: 업종 선도 | (미구현) 업종 내 순위 | - |
| I: 기관 투자 | (미구현) 기관 보유 비율 | - |

### 매매 기준
- **70점 이상**: 매수 신호
- **85점 이상**: 강력 매수 신호

### 주요 클래스

| 클래스 | 역할 |
|--------|------|
| CanSlimAnalysisService | 종합 CANSLIM 분석 |
| QuarterlyEarningsAnalyzer | 분기 실적 분석 |
| AnnualEarningsAnalyzer | 연간 실적 분석 |
| SupplyDemandAnalyzer | 수급 분석 |
| MarketDirectionAnalyzer | 시장 방향 분석 |

---

## 컵앤핸들 패턴 분석

### 패턴 조건

| 조건 | 기준 |
|------|------|
| 컵 깊이 | 12~33% |
| 컵 형성 기간 | 7~65주 |
| 핸들 하락폭 | 5~15% |
| 핸들 형성 기간 | 1~4주 |
| 매수 시점 | 핸들 고점 돌파 시 |

### 패턴 단계

```
[CUP_FORMING] → [HANDLE_FORMING] → [HANDLE_COMPLETE] → [BREAKOUT]
     ↓                ↓                   ↓                ↓
  컵 형성 중        핸들 형성 중        핸들 완료         돌파 발생
```

### 점수 산정

| 구간 | 점수 |
|------|------|
| 컵 패턴 기본 | 30점 |
| 컵 깊이 20~25% | +10점 |
| 컵 기간 7~30주 | +10점 |
| 핸들 깊이 10~12% | +20점 |
| 핸들 기본 | +10점 |
| 돌파 발생 | +20점 |

### 주요 클래스

| 클래스 | 역할 |
|--------|------|
| CupAndHandleAnalyzer | 컵앤핸들 패턴 감지 |
| CupAndHandlePatternRepository | 패턴 이력 관리 |

---

## 자동매매 시스템

### 매매 전략

| 구분 | 기준 |
|------|------|
| 매수 조건 | CANSLIM 70점 이상 + 컵앤핸들 패턴 |
| 매도 조건 | 손절 -7%, 익절 +20%, CANSLIM 40점 이하 |
| 최대 보유 | 10종목 |
| 1건당 투자금 | 100만원 |

### 매매 흐름

```
[스케줄러 실행] → [종목별 분석] → [매수/매도 판단] → [주문 실행]
     ↓                ↓                ↓                ↓
  평일 09:00      CANSLIM/컵앤핸들    조건 충족 시     포트폴리오 갱신
```

### 주요 클래스

| 클래스 | 역할 |
|--------|------|
| AutoTradingWorker | 스케줄 기반 자동매매 |
| TradingStrategyService | 매매 전략 판단 |

### 설정 (application.yml)

```yaml
trading:
  scheduler:
    enabled: true
    cron: "0 0 9 * * MON-FRI"
  max-positions: 10
  position-size: 1000000
  stop-loss-rate: 7
  take-profit-rate: 20
```

---

## 코드 컨벤션 (TODO)

### 네이밍
- 클래스: PascalCase
- 메서드: camelCase
- 상수: UPPER_SNAKE_CASE
- 테이블: snake_case

### 아키텍처
- Controller → Service → Repository 흐름
- 외부 API 호출은 Service 레이어에서 처리
- 값 객체는 record 사용 고려

---

## 대시보드 UI

### 접속 주소
- http://localhost:8080

### 구성 요소

| 영역 | 내용 |
|------|------|
| 요약 카드 | 투자금, 현재가, 평가손익, 수익률 |
| 통계 | 보유 종목 수, 매수/매도 횟수 |
| 포트폴리오 | 보유 종목 목록, 수익률 |
| 매매 기록 | 최근 10건 매매 이력 |
| 수동 검사 | 수동으로 매매 검사 실행 |

### 주요 파일

| 파일 | 역할 |
|------|------|
| DashboardController | 대시보드 컨트롤러 |
| dashboard.html | 타임리프 템플릿 |
| dashboard.css | 스타일 |

---

## 실행 방법

```bash
# 빌드
./gradlew build

# 실행
./gradlew bootRun

# 테스트
./gradlew test
```

---

## 변경 이력

| 날짜 | 변경 내용 |
|------|----------|
| 2024-xx-xx | 프로젝트 초기화, 구조 설계 |
