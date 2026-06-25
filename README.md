# CAN Agent

William O'Neil의 **CANSLIM 투자 전략**과 **컵앤핸들(Cup and Handle) 패턴**을 기반으로 한 자동 주식 매매 시스템

한국 증시(KOSPI/KOSDAQ)를 대상으로, 외부 API(DART, KRX, 한국투자증권)에서 재무제표 및 주가 데이터를 수집하고, CANSLIM 7가지 요소 중 핵심 6가지를 분석하여 매수/매도 판단을 내린 후 포트폴리오를 자동 관리합니다.

---

## 프로젝트 목표

1. **데이터 기반 투자**: 감정에 의존하지 않고, 정량적 지표에 기반한 투자 판단
2. **자동화**: 매일 정해진 시간에 자동으로 종목 분석 및 매매 실행
3. **위험 관리**: 손절(-7%), 익절(+20%) 등 엄격한 리스크 관리 규칙 적용
4. **실시간 연동**: 한국투자증권 API를 통한 실전/모의투자 자동 주문
5. **알림**: 매매 발생 시 텔레그램/카카오/콘솔 알림 전송

---

## 기술 스택

| 구분 | 기술 |
|------|------|
| Language | Java 17 |
| Framework | Spring Boot 3.3.2 |
| Template | Thymeleaf |
| ORM | Spring Data JPA |
| DB | H2 (개발/테스트), PostgreSQL (운영) |
| Build | Gradle |
| HTTP Client | RestTemplate |
| Testing | JUnit 5, Mockito, MockMvc, AssertJ |
| API 연동 | DART, KRX, 한국투자증권 |

---

## 프로젝트 구조

```
can-agent/
├── src/main/java/com/canagent/
│   ├── CanAgentApplication.java          # 메인 클래스 (@EnableScheduling)
│   ├── config/
│   │   ├── ApiConfig.java                # DART/KRX/한국투자증권 API 설정
│   │   ├── NotificationConfig.java       # 알림 설정 (Telegram, Kakao, Console)
│   │   └── RestTemplateConfig.java       # RestTemplate 빈 설정
│   ├── domain/
│   │   ├── stock/
│   │   │   ├── Stock.java                # 주식 기본 정보 엔티티
│   │   │   ├── StockPrice.java           # 주가 데이터 (OHLCV) 엔티티
│   │   │   └── FinancialStatement.java   # 재무제표 엔티티
│   │   ├── analysis/
│   │   │   ├── CupAndHandlePattern.java  # 컵앤핸들 패턴 엔티티
│   │   │   └── PatternStatus.java        # 패턴 상태 열거형
│   │   ├── trading/
│   │   │   ├── Trade.java                # 매매 기록 엔티티
│   │   │   └── TradeType.java            # 매수/매도 열거형
│   │   └── portfolio/
│   │       └── Portfolio.java            # 포트폴리오 엔티티
│   ├── service/
│   │   ├── DartApiClient.java            # DART 재무 API 클라이언트
│   │   ├── KrxApiClient.java             # KRX 주가 API 클라이언트
│   │   ├── KoreaInvestmentApiClient.java # 한국투자증권 매매 API 클라이언트
│   │   ├── KoreaInvestmentTokenProvider.java # 한국투자증권 토큰 관리
│   │   ├── TradingStrategyService.java   # 매매 전략 판단 서비스
│   │   ├── KrxDataSyncService.java       # KRX 데이터 동기화
│   │   ├── DartDataSyncService.java      # DART 데이터 동기화
│   │   ├── StockService.java             # 종목 관리 서비스
│   │   ├── PortfolioService.java         # 포트폴리오 관리 서비스
│   │   ├── analysis/
│   │   │   ├── CanSlimAnalysisService.java        # 종합 CANSLIM 분석
│   │   │   ├── QuarterlyEarningsAnalyzer.java     # C: 분기 실적 분석 (20점)
│   │   │   ├── AnnualEarningsAnalyzer.java        # A: 연간 실적 분석 (20점)
│   │   │   ├── SupplyDemandAnalyzer.java          # S: 수급 분석 (15점)
│   │   │   ├── IndustryLeaderAnalyzer.java        # L: 업종 선도주 분석 (15점)
│   │   │   ├── InstitutionalInvestorAnalyzer.java # I: 기관 투자자 분석 (15점)
│   │   │   ├── MarketDirectionAnalyzer.java       # M: 시장 방향 분석 (15점)
│   │   │   └── CupAndHandleAnalyzer.java          # 컵앤핸들 패턴 감지
│   │   ├── notification/
│   │   │   ├── NotificationService.java           # 알림 서비스 인터페이스
│   │   │   ├── NotificationServiceRouter.java     # 알림 채널 라우터
│   │   │   ├── ConsoleNotificationService.java    # 콘솔 로그 알림
│   │   │   ├── TelegramNotificationService.java   # 텔레그램 알림
│   │   │   ├── KakaoNotificationService.java      # 카카오 웹훅 알림
│   │   │   └── NotificationEvent.java             # 알림 이벤트 DTO
│   │   └── dto/
│   │       ├── CanSlimResult.java                 # CANSLIM 분석 결과 DTO
│   │       ├── CupAndHandleResult.java            # 컵앤핸들 결과 DTO
│   │       ├── DartApiResponse.java               # DART API 응답 DTO
│   │       ├── DartFinancialDTO.java              # DART 재무 데이터 DTO
│   │       ├── KrxPriceDTO.java                   # KRX 주가 데이터 DTO
│   │       ├── KrxCorpDTO.java                    # KRX 기업 데이터 DTO
│   │       ├── KoreaInvestmentOrderResponse.java  # 한국투자증권 주문 응답 DTO
│   │       ├── KoreaInvestmentBalanceResponse.java # 한국투자증권 잔고 응답 DTO
│   │       └── KoreaInvestmentPriceResponse.java  # 한국투자증권 현재가 응답 DTO
│   ├── repository/                         # JPA Repository
│   ├── worker/
│   │   ├── AutoTradingWorker.java          # 스케줄 기반 자동매매 워커
│   │   ├── PortfolioScheduler.java         # 포트폴리오 현재가 갱신 스케줄러
│   │   └── DataSyncScheduler.java          # 데이터 동기화 스케줄러
│   └── web/
│       ├── DashboardController.java        # 대시보드 웹 컨트롤러
│       └── StockController.java            # 종목 관리 컨트롤러
├── src/main/resources/
│   ├── application.yml                     # 메인 설정 파일
│   ├── templates/
│   │   ├── dashboard.html                  # 대시보드 타임리프 템플릿
│   │   ├── stock-list.html                 # 종목 목록 타임리프 템플릿
│   │   └── stock-detail.html               # 종목 상세 타임리프 템플릿
│   └── static/css/dashboard.css            # 대시보드 스타일
└── src/test/                               # 단위/통합/E2E 테스트
```

---

## 데이터 모델

| 엔티티 | 테이블 | 설명 |
|--------|--------|------|
| Stock | stocks | 주식 기본 정보 (코드, 이름, 업종, 시장, 활성 상태) |
| StockPrice | stock_prices | 주가 데이터 (OHLCV, 변화율, 범위) |
| FinancialStatement | financial_statements | 재무제표 (매출, 영업이익, 순이익, EPS, ROE, 부채비율) |
| CupAndHandlePattern | cup_and_handle_patterns | 컵앤핸들 패턴 감지 결과 및 이력 |
| Trade | trades | 매매 기록 (매수/매도, 수량, 가격, 수익률, 사유) |
| Portfolio | portfolios | 포트폴리오 (보유 수량, 평균매수가, 현재가, 수익률) |

---

## 외부 API 연동

### 1. DART API (재무제표)

| 항목 | 내용 |
|------|------|
| Base URL | `https://opendart.fss.or.kr/api` |
| 엔드포인트 | `/fnlttMultiAcnt.json` (재무제표), `/company.json` (기업정보) |
| 인증키 발급 | https://opendart.fss.or.kr/ |
| 용도 | 분기별/연간 재무제표 조회, 기업명 확인 |

### 2. KRX API (주가 정보)

| 항목 | 내용 |
|------|------|
| Base URL | `https://apis.data.go.kr/1160100/service/getStockPriceInfo` |
| 인증키 발급 | https://www.data.go.kr/ |
| 용도 | 일별 주가 데이터 (OHLCV) 조회, 상장종목 목록 조회 |

### 3. 한국투자증권 API (매매 연동)

| 항목 | 내용 |
|------|------|
| 포탈 | https://apiportal.koreainvestment.com/ |
| 실전투자 | https://openapi.koreainvestment.com:9443 |
| 모의투자 | https://openapivts.koreainvestment.com:29443 |
| 용도 | 실제 주문 실행 (매수/매도), 잔고 조회, 포지션 관리 |

#### 지원 기능

| 기능 | 엔드포인트 | 설명 |
|------|-----------|------|
| 주문 실행 | POST /uapi/domestic-stock/v1/trading/order-cash | 시장가/지정가 매수/매도 |
| 잔고 조회 | GET /uapi/domestic-stock/v1/trading/inquire-balance | 보유 종목 및 잔고 확인 |
| 현재가 조회 | GET /uapi/domestic-stock/v1/quotations/inquire-price | 실시간 현재가 |

#### 환경 변수 설정

```bash
# .env 파일을 복사하여 사용
cp .env.example .env

# 또는 직접 설정
export DART_API_KEY=your_dart_api_key
export KRX_API_KEY=your_krx_api_key
export KOREA_INVESTMENT_APP_KEY=your_app_key
export KOREA_INVESTMENT_APP_SECRET=your_app_secret
export KOREA_INVESTMENT_ACCOUNT_NUMBER=12345678-01
export KOREA_INVESTMENT_IS_REAL=false
```

---

## CANSLIM 분석 시스템

William O'Neil이 개발한 CANSLIM 전략은 **성장주 투자**를 위한 7가지 핵심 요소를 분석합니다.

### 분석 요소 (100점 만점)

| 요소 | 설명 | 배점 | 구현 상태 |
|------|------|------|-----------|
| **C**: Current Earnings | 분기별 EPS 성장률 (전년 동기 대비) | 20점 | 완료 |
| **A**: Annual Earnings | 연간 EPS 성장률 (지속적 성장 추세) | 20점 | 완료 |
| **S**: Supply & Demand | 거래량 비율, 가격 변화 분석 (수급 균형) | 15점 | 완료 |
| **L**: Leader or Laggard | 업종 내 상대강도, ROE, 업종 순위 | 15점 | 완료 |
| **I**: Institutional Sponsorship | 거래량 증가율, 고거래량일 분석 (기관 매수 프록시) | 15점 | 완료 |
| **M**: Market Direction | 50일/200일 이동평균선 분석 (시장 추세) | 15점 | 완료 |
| **N**: New or New Highs | 신저가/신고가 여부 | - | 미구현 |

### 매매 기준

| 점수 구간 | 판단 |
|-----------|------|
| 70점 이상 | 매수 신호 |
| 85점 이상 | 강력 매수 신호 |
| 40점 이하 | 매도 신호 (보유 시) |

### 분석 흐름

```
Stock 데이터 입력
    ↓
┌─────────────────────────────────────────────┐
│  CanSlimAnalysisService.analyze()           │
│  ├─ QuarterlyEarningsAnalyzer  (C: 20점)   │
│  ├─ AnnualEarningsAnalyzer     (A: 20점)   │
│  ├─ SupplyDemandAnalyzer       (S: 15점)   │
│  ├─ IndustryLeaderAnalyzer     (L: 15점)   │
│  ├─ InstitutionalInvestorAnalyzer (I: 15점)│
│  └─ MarketDirectionAnalyzer    (M: 15점)   │
└─────────────────────────────────────────────┘
    ↓
종합 점수 (0~100점)
    ↓
70점 이상 → 매수 신호
```

### 주요 클래스

| 클래스 | 역할 |
|--------|------|
| CanSlimAnalysisService | 6개 분석기 종합, 점수 합산 |
| QuarterlyEarningsAnalyzer | 분기 EPS 성장률 분석 (20점) |
| AnnualEarningsAnalyzer | 연간 EPS 성장률 분석 (20점) |
| SupplyDemandAnalyzer | 거래량/가격 변화 분석 (15점) |
| IndustryLeaderAnalyzer | 업종 내 선도주 분석 - 상대강도, ROE, 업종 순위 (15점) |
| InstitutionalInvestorAnalyzer | 기관 투자자 매수 분석 - 거래량 프록시 (15점) |
| MarketDirectionAnalyzer | 이동평균선 분석 (15점) |

---

## 컵앤핸들 패턴 분석

캔슬림 투자 전략에서 가장 강력한 매수 패턴 중 하나인 **컵앤핸들(Cup and Handle)** 패턴을 자동으로 감지합니다.

### 패턴 조건

| 조건 | 기준 |
|------|------|
| 컵 깊이 | 12~33% (최고점 대비 하락폭) |
| 컵 형성 기간 | 7~65주 |
| 핸들 하락폭 | 5~15% (컵 고점 대비) |
| 핸들 형성 기간 | 1~4주 |
| 매수 시점 | 핸들 고점 돌파 시 |

### 패턴 단계

```
[CUP_FORMING] → [HANDLE_FORMING] → [HANDLE_COMPLETE] → [BREAKOUT]
     ↓                ↓                   ↓                ↓
  컵 형성 중        핸들 형성 중        핸들 완료         돌파 발생
  (하락 후 바닥     (소폭 조정)        (돌파 준비)       (매수 타이밍)
   다지기)
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
| **최대 점수** | **100점** |

---

## 자동매매 시스템

### 매매 전략

| 구분 | 기준 |
|------|------|
| 매수 조건 | CANSLIM 70점 이상 또는 컵앤핸들 패턴 |
| 매도 조건 | 손절 -7%, 익절 +20%, CANSLIM 40점 이하 |
| 최대 보유 | 10종목 |
| 1건당 투자금 | 100만원 |
| 스케줄 | 평일 오전 9시 자동 실행 |

### 매매 흐름

```
[스케줄러 실행] (매일 09:00, 평일)
        ↓
[활성 종목 순회]
        ↓
┌───────────────────────┐
│ 각 종목별:             │
│ 1. 최신 주가 조회      │
│ 2. 매도 평가           │
│    ├─ 손절 조건 확인   │
│    ├─ 익절 조건 확인   │
│    └─ CANSLIM 점수 확인│
│ 3. 매수 평가           │
│    ├─ CANSLIM 분석     │
│    ├─ 컵앤핸들 분석    │
│    └─ 포지션 확인      │
│ 4. 주문 실행           │
│    ├─ 한국투자증권 API  │
│    └─ 포트폴리오 갱신   │
│ 5. 알림 전송           │
│    ├─ 텔레그램         │
│    ├─ 카카오           │
│    └─ 콘솔 로그        │
└───────────────────────┘
        ↓
[매매 기록 저장]
```

### 주요 클래스

| 클래스 | 역할 |
|--------|------|
| AutoTradingWorker | 스케줄 기반 자동매매 실행 |
| TradingStrategyService | 매매 전략 판단 (매수/매도 결정) |
| KoreaInvestmentApiClient | 한국투자증권 주문 실행 |
| PortfolioScheduler | 포트폴리오 현재가 자동 갱신 |
| DataSyncScheduler | 주가/재무 데이터 자동 동기화 |

### 설정 (application.yml)

```yaml
trading:
  scheduler:
    enabled: true
    cron: "0 0 9 * * MON-FRI"              # 평일 오전 9시 (자동매매)
    sync-cron: "0 30 15 * * MON-FRI"       # 평일 오후 3시 30분 (주가 동기화)
    quarterly-cron: "0 0 10 1,4,7,10 *"    # 분기별 재무제표 동기화
    price-cron: "0 */5 9-15 * * MON-FRI"   # 장중 5분마다 현재가 갱신
    close-price-cron: "0 0 16 * * MON-FRI" # 장 마감 후 종가 갱신
  max-positions: 10              # 최대 보유 종목 수
  position-size: 1000000         # 1건당 투자 금액 (100만원)
  stop-loss-rate: 7              # 손절 기준 (%)
  take-profit-rate: 20           # 익절 기준 (%)
  real-trading: false            # 실제 매매 실행 여부
```

---

## 알림 시스템

매매 발생 시 다양한 채널로 알림을 전송합니다.

### 지원 채널

| 채널 | 설정 키 | 설명 |
|------|---------|------|
| 텔레그램 | `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` | 텔레그램 봇을 통한 메시지 전송 |
| 카카오 | `KAKAO_WEBHOOK_URL` | 카카오 워크 웹훅을 통한 메시지 전송 |
| 콘솔 | 기본 활성화 | 애플리케이션 로그에 알림 출력 |

### 알림 설정 (application.yml)

```yaml
notification:
  enabled: true
  telegram:
    enabled: false
    bot-token: ${TELEGRAM_BOT_TOKEN:}
    chat-id: ${TELEGRAM_CHAT_ID:}
  kakao:
    enabled: false
    webhook-url: ${KAKAO_WEBHOOK_URL:}
  console:
    enabled: true
```

### 알림 메시지 예시

```
🔴 매수 알림

• 종목: 삼성전자 (005930)
• 수량: 10주
• 가격: 70,000원
• 금액: 700,000원
• 사유: CANSLIM 점수 75점
• 시간: 2026-06-25 09:00:00
```

---

## 종목 관리

### 기능

| 기능 | 설명 |
|------|------|
| 종목 검색 | 이름/코드로 종목 검색 |
| 종목 등록 | KRX에서 종목을 검색하여 시스템에 등록 |
| 종목 상세 | 각 종목의 CANSLIM 분석 결과, 가격 정보 확인 |
| 활성/비활성 | 종목별 모니터링 활성화/비활성화 설정 |
| 자동 수집 | KRX에서 상장종목 전체 목록 자동 동기화 |

### 접속

- 종목 목록: http://localhost:8080/stocks
- 종목 상세: http://localhost:8080/stocks/{code}

---

## 포트폴리오 관리

### 기능

| 기능 | 설명 |
|------|------|
| 현재가 갱신 | 장중 5분마다 보유 종목 현재가 자동 갱신 |
| 종가 갱신 | 장 마감 후 종가 갱신 |
| 수익률 계산 | 실시간 평가손익 및 수익률 표시 |
| 리스크 관리 | 포지션 한도, 부족 현금 경고 |
| 승률 통계 | 매수/매도 횟수, 승률 계산 |

### 대시보드

- 접속: http://localhost:8080
- 구성: 요약 카드, 리스크 알림, 포트폴리오 테이블, 매매 기록

---

## 실행 방법

### 1. 환경 변수 설정

```bash
# .env 파일 복사
cp .env.example .env

# .env 파일 편집 (API 키 입력)
vim .env
```

### 2. 빌드 및 실행

```bash
# 빌드
./gradlew build

# 개발 서버 실행
./gradlew bootRun

# 프로덕션 빌드
./gradlew bootJar
java -jar build/libs/can-agent-0.1.0.jar
```

### 3. 테스트 실행

```bash
# 전체 테스트
./gradlew test

# 특정 테스트 클래스
./gradlew test --tests "com.canagent.service.analysis.CanSlimAnalysisServiceTest"
```

### 4. 대시보드 접속

```
http://localhost:8080
```

---

## 테스트 현황

| 테스트 분류 | 테스트 수 | 상태 |
|------------|----------|------|
| KRX 데이터 동기화 | 4 | 완료 |
| DART 데이터 동기화 | 5 | 완료 |
| 한국투자증권 API | 7 | 완료 |
| 종목 관리 서비스 | 11 | 완료 |
| 포트폴리오 서비스 | 20 | 완료 |
| 포트폴리오 스케줄러 | 10 | 완료 |
| CANSLIM 분석 | 38 | 완료 |
| 대시보드 E2E | 3 | 완료 |
| 매매 시나리오 | 1 | 완료 |
| 알림 시스템 | 19 | 완료 |
| **합계** | **116** | **전체 통과** |

---

## 개발 로드맵

상세한 개발 로드맵은 [docs/ROADMAP.md](docs/ROADMAP.md)를 참조하세요.

| Phase | 목표 | 상태 |
|-------|------|------|
| Phase 1 | 데이터 파이프라인 구축 | 완료 |
| Phase 2 | 한국투자증권 API 연동 | 완료 |
| Phase 3 | 종목 관리 기능 | 완료 |
| Phase 4 | 포트폴리오 관리 고도화 | 완료 |
| Phase 5 | CANSLIM 분석 강화 (L, I) | 완료 |
| Phase 6 | 알림 시스템 | 완료 |

---

## 코드 컨벤션

### 네이밍

| 구분 | 규칙 | 예시 |
|------|------|------|
| 클래스 | PascalCase | `CanSlimAnalysisService` |
| 메서드 | camelCase | `evaluateBuy()` |
| 상수 | UPPER_SNAKE_CASE | `MAX_POSITIONS` |
| 테이블 | snake_case | `stock_prices` |

### 아키텍처

- **Controller → Service → Repository** 흐름 유지
- 외부 API 호출은 Service 레이어에서 처리
- DTO와 엔티티를 명확히 구분
- 트랜잭션 관리: `@Transactional` 어노테이션 활용

---

## 라이선스

이 프로젝트는 학습 및 개인 투자 목적으로 개발되었습니다. 실제 투자에 사용하기 전에 충분한 테스트와 검증이 필요합니다.

---

## 주의사항

1. **실전투자 시 주의**: 자동매매 시스템은 시장 상황에 따라 예상과 다른 결과를 초래할 수 있습니다.
2. **손실 리스크**: 투자 원금의 손실이 발생할 수 있으므로, 감내 가능한 금액으로만 투자하세요.
3. **API 사용량**: 외부 API의 사용량 제한을 확인하고, 적절한 간격으로 요청하세요.
4. **데이터 정확도**: 외부 API에서 제공하는 데이터의 정확성을 보장하지 않습니다.
