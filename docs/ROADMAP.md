# CAN Agent 개발 로드맵

## 전체 현황 요약

| 구분 | 상태 |
|------|------|
| CANSLIM 분석 엔진 (C, A, S, L, I, M) | 완료 |
| 컵앤핸들 패턴 분석 | 완료 |
| 매매 전략 로직 | 완료 |
| 자동매매 워커 (스케줄링) | 완료 |
| 대시보드 UI | 완료 |
| DART API 클라이언트 | 완료 |
| KRX API 클라이언트 | 완료 |
| 한국투자증권 API 연동 | 완료 |
| 종목 자동 수집 | 완료 |
| 포트폴리오 관리 | 완료 |
| CANSLIM L/I 분석 | 완료 |
| 알림 시스템 | 완료 |

---

## Phase 1: 데이터 파이프라인 구축 ✅

> **목표**: KRX에서 주가 데이터를 정상적으로 수집하고 DB에 저장

### 완료 기준
- [x] KRX API 호출 시 정상적으로 주가 데이터 수신
- [x] DART API 호출 시 정상적으로 재무제표 데이터 수신
- [x] 수집된 데이터가 DB에 저장됨
- [x] 스케줄러에 의해 자동으로 데이터 갱신됨

### 주요 수정 파일
- `KrxApiClient.java` - `mapToDTO()` 메서드 수정
- `KrxDataSyncService.java` - 주가 데이터 동기화
- `DartDataSyncService.java` - 재무제표 데이터 동기화
- `DataSyncScheduler.java` - 데이터 동기화 스케줄러

---

## Phase 2: 한국투자증권 API 연동 ✅

> **목표**: 한국투자증권 API를 통해 실제 주문 실행

### 완료 기준
- [x] 한국투자증권 API 인증 성공
- [x] 모의투자로 주문 실행 가능
- [x] 잔고 조회 정상 동작
- [x] 실전/모의투자 전환 동작
- [x] AutoTradingWorker에서 실제 주문 실행됨

### 주요 생성/수정 파일
- `KoreaInvestmentApiClient.java` - 한국투자증권 API 클라이언트
- `KoreaInvestmentTokenProvider.java` - 토큰 관리
- `ApiConfig.java` - KoreaInvestment 설정 추가
- `TradingStrategyService.java` - 실제 주문 연동
- `KoreaInvestmentOrderResponse.java`, `KoreaInvestmentBalanceResponse.java`, `KoreaInvestmentPriceResponse.java` - 응답 DTO

---

## Phase 3: 종목 관리 기능 ✅

> **목표**: 분석 대상 종목을 자동으로 수집하고 관리

### 완료 기준
- [x] 상장종목 전체 목록 자동 수집
- [x] 종목별 활성/비활성 설정 가능
- [x] 웹 UI에서 종목 관리 가능
- [x] 종목 상세 정보 페이지

### 주요 생성/수정 파일
- `StockService.java` - 종목 CRUD 및 KRX 동기화
- `StockController.java` - 종목 관리 REST + Thymeleaf
- `stock-list.html`, `stock-detail.html` - 종목 관리 UI
- `StockRepository.java` - 검색/필터 쿼리 추가

---

## Phase 4: 포트폴리오 관리 고도화 ✅

> **목표**: 실시간 포트폴리오 관리 및 투자금 관리

### 완료 기준
- [x] 포트폴리오 현재가 실시간 갱신 (5분 간격)
- [x] 장 마감 후 종가 갱신
- [x] 투자 가능 잔액 관리
- [x] 수익률/승률 통계 표시
- [x] 리스크 한도 자동 관리 (포지션 한도, 부족 현금 경고)
- [x] 대시보드 고도화 (리스크 알림, 진행 바, 통계 카드)

### 주요 생성/수정 파일
- `PortfolioScheduler.java` - 포트폴리오 갱신 스케줄러
- `PortfolioService.java` - 포트폴리오 통계/리스크 관리
- `DashboardController.java` - PortfolioService 기반 통계 API
- `dashboard.html` - 리스크 알림/진행 바/통계 카드 추가
- `PortfolioRepository.java` - 집계 쿼리 추가

---

## Phase 5: CANSLIM 분석 강화 ✅

> **목표**: CANSLIM 7요소 중 미구현 요소 구현

### 완료 기준
- [x] 업종 선도주 분석 정상 동작
- [x] 기관 투자자 분석 정상 동작
- [x] CANSLIM 총점에 L/I 반영 (100점 체계로 재보정)
- [x] 기존 테스트 모두 통과

### 주요 생성/수정 파일
- `IndustryLeaderAnalyzer.java` - 업종 선도주 분석 (상대강도 + ROE + 업종 순위)
- `InstitutionalInvestorAnalyzer.java` - 기관 투자자 분석 (거래량 프록시)
- `CanSlimAnalysisService.java` - L/I 분석기 통합, 점수 재보정

---

## Phase 6: 알림 시스템 ✅

> **목표**: 매매 발생 시 사용자에게 알림 전송

### 완료 기준
- [x] 매수/매도 시 알림 전송
- [x] 텔레그램 알림 동작
- [x] 카카오 웹훅 알림 동작
- [x] 콘솔 로그 알림 (기본)
- [x] 알림 채널 라우팅

### 주요 생성/수정 파일
- `NotificationConfig.java` - 알림 설정 (Telegram, Kakao, Console)
- `NotificationEvent.java` - 알림 이벤트 DTO
- `NotificationService.java` - 알림 서비스 인터페이스
- `ConsoleNotificationService.java` - 콘솔 로그 알림
- `TelegramNotificationService.java` - 텔레그램 알림
- `KakaoNotificationService.java` - 카카오 웹훅 알림
- `NotificationServiceRouter.java` - 알림 채널 라우터
- `AutoTradingWorker.java` - 알림 훅 추가

---

## 진행 순서

```
Phase 1 (데이터 파이프라인) ✅
    ↓
Phase 2 (한국투자증권 API 연동) ✅
    ↓
Phase 3 (종목 관리) ✅
    ↓
Phase 4 (포트폴리오 관리) ✅
    ↓
Phase 5 (CANSLIM 강화) ✅
    ↓
Phase 6 (알림 시스템) ✅
```

---

## 모든 Phase 완료! 🎉

---

## 테스트 현황

| 테스트 분류 | 테스트 수 | 상태 |
|------------|----------|------|
| KRX 데이터 동기화 | 4 | ✅ |
| DART 데이터 동기화 | 5 | ✅ |
| 한국투자증권 API | 7 | ✅ |
| 종목 관리 서비스 | 11 | ✅ |
| 포트폴리오 서비스 | 20 | ✅ |
| 포트폴리오 스케줄러 | 10 | ✅ |
| CANSLIM 분석 | 38 | ✅ |
| 대시보드 E2E | 3 | ✅ |
| 매매 시나리오 | 1 | ✅ |
| 알림 시스템 | 19 | ✅ |
| **합계** | **116** | **전체 통과** |

---

## 예상 남은 공수

모든 Phase가 완료되었습니다!
