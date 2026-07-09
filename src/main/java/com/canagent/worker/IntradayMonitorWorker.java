package com.canagent.worker;

import com.canagent.domain.analysis.MonitorCheckLog;
import com.canagent.domain.portfolio.Portfolio;
import com.canagent.domain.stock.Stock;
import com.canagent.domain.stock.StockPrice;
import com.canagent.domain.trading.Trade;
import com.canagent.repository.AnalysisScoreRepository;
import com.canagent.repository.MonitorCheckLogRepository;
import com.canagent.repository.PortfolioRepository;
import com.canagent.repository.StockPriceRepository;
import com.canagent.repository.StockRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.canagent.port.BrokerPort;
import com.canagent.service.TradingStrategyService;
import com.canagent.service.TradingStrategyService.TradingDecision;
import com.canagent.service.analysis.CanSlimAnalysisService;
import com.canagent.service.analysis.CupAndHandleAnalyzer;
import com.canagent.service.dto.CanSlimResult;
import com.canagent.service.dto.CupAndHandleResult;
import com.canagent.service.dto.KoreaInvestmentBalanceResponse;
import com.canagent.service.dto.KoreaInvestmentPriceResponse;
import com.canagent.service.notification.NotificationEvent;
import com.canagent.service.notification.NotificationServiceRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "trading.scheduler.enabled", havingValue = "true")
public class IntradayMonitorWorker {

    private static final Logger log = LoggerFactory.getLogger(IntradayMonitorWorker.class);

    private final StockRepository stockRepository;
    private final StockPriceRepository stockPriceRepository;
    private final PortfolioRepository portfolioRepository;
    private final AnalysisScoreRepository analysisScoreRepository;
    private final BrokerPort koreaInvestmentApiClient;
    private final CanSlimAnalysisService canSlimAnalysisService;
    private final CupAndHandleAnalyzer cupAndHandleAnalyzer;
    private final TradingStrategyService tradingStrategyService;
    private final NotificationServiceRouter notificationServiceRouter;
    private final MonitorCheckLogRepository monitorCheckLogRepository;
    private final ObjectMapper objectMapper;

    @Value("${trading.max-positions:10}")
    private int maxPositions;

    @Value("${trading.position-rate:10}")
    private int positionRate;

    @Value("${trading.min-score:120}")
    private int minScore;

    private volatile boolean monitoring = false;
    private volatile LocalDateTime lastCheckTime;
    private volatile int lastSignalCount;
    private volatile int lastScanCount;
    private volatile List<Map<String, Object>> lastSignals = Collections.emptyList();

    public IntradayMonitorWorker(
            StockRepository stockRepository,
            StockPriceRepository stockPriceRepository,
            PortfolioRepository portfolioRepository,
            AnalysisScoreRepository analysisScoreRepository,
            BrokerPort koreaInvestmentApiClient,
            CanSlimAnalysisService canSlimAnalysisService,
            CupAndHandleAnalyzer cupAndHandleAnalyzer,
            TradingStrategyService tradingStrategyService,
            NotificationServiceRouter notificationServiceRouter,
            MonitorCheckLogRepository monitorCheckLogRepository,
            ObjectMapper objectMapper) {
        this.stockRepository = stockRepository;
        this.stockPriceRepository = stockPriceRepository;
        this.portfolioRepository = portfolioRepository;
        this.analysisScoreRepository = analysisScoreRepository;
        this.koreaInvestmentApiClient = koreaInvestmentApiClient;
        this.canSlimAnalysisService = canSlimAnalysisService;
        this.cupAndHandleAnalyzer = cupAndHandleAnalyzer;
        this.tradingStrategyService = tradingStrategyService;
        this.notificationServiceRouter = notificationServiceRouter;
        this.monitorCheckLogRepository = monitorCheckLogRepository;
        this.objectMapper = objectMapper;
    }

    @Scheduled(cron = "${trading.scheduler.monitor-cron:0 */5 9-15 * * MON-FRI}", zone = "Asia/Seoul")
    public void monitorTrading() {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul"));

        if (!isTradingHours(now)) {
            return;
        }

        log.info("===== 장중 모니터링 시작 =====");
        monitoring = true;
        lastCheckTime = now;

        CheckFunnel funnel = new CheckFunnel(minScore);
        try {
            // 1단계: 보유 종목 매도 체크 (≤10개, ~5초)
            checkHeldPositionsForSell(now);

            // 2단계: 매수 대상 스캔 (~400종목)
            List<Stock> targetStocks = getTargetStocks();
            funnel.scanned = targetStocks.size();
            lastScanCount = targetStocks.size();
            log.info("대상 종목 수: {}", targetStocks.size());

            List<SignalStock> signalStocks = new ArrayList<>();

            for (Stock stock : targetStocks) {
                try {
                    processStockForSignal(stock, signalStocks, funnel);
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("종목 모니터링 실패: {} ({}) - {}", stock.getName(), stock.getCode(), e.getMessage());
                }
            }

            funnel.signalCount = signalStocks.size();

            // 3단계: 매수 주문 실행
            LocalDateTime checkTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul"));
            if (!signalStocks.isEmpty() && isTradingHours(checkTime)) {
                executeBuyOrders(signalStocks, funnel);
            } else if (!signalStocks.isEmpty()) {
                log.info("장 마감으로 매수 건너뜀: {}건", signalStocks.size());
                for (SignalStock s : signalStocks) {
                    funnel.recordExecution(s, "MARKET_CLOSED", "장 마감으로 주문 건너뜀");
                }
            }

            lastSignalCount = signalStocks.size();
            lastSignals = signalStocks.stream()
                    .map(s -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("code", s.stock.getCode());
                        m.put("name", s.stock.getName());
                        m.put("price", s.currentPrice);
                        m.put("canSlimScore", s.canSlimResult.totalScore());
                        m.put("cupScore", s.cupResult.score());
                        m.put("totalScore", s.totalScore);
                        m.put("reason", s.reason);
                        CheckFunnel.ExecutionResult er = funnel.executionByCode.get(s.stock.getCode());
                        m.put("executionStatus", er != null ? er.status() : "PENDING");
                        m.put("executionDetail", er != null ? er.detail() : "");
                        return m;
                    })
                    .collect(Collectors.toList());

            log.info("장중 모니터링 완료: 스캔 {} / 현재가실패 {} / 보유중 {} / 신호미달 {} / 총점미달 {} / 신호 {} / 주문성공 {} / 차단 {}",
                    funnel.scanned, funnel.priceFail, funnel.heldSkip, funnel.signalMiss,
                    funnel.scoreMiss, funnel.signalCount, funnel.orderSuccess, funnel.orderBlocked);
        } catch (Exception e) {
            log.error("장중 모니터링 실패: {}", e.getMessage());
        } finally {
            monitoring = false;
            persistCheckLog(now, funnel);
        }
    }

    private void persistCheckLog(LocalDateTime checkTime, CheckFunnel funnel) {
        try {
            String nearMissJson = objectMapper.writeValueAsString(funnel.topNearMiss(10));
            String executionJson = objectMapper.writeValueAsString(new ArrayList<>(funnel.executionByCode.values()));
            monitorCheckLogRepository.save(new MonitorCheckLog(
                    checkTime, funnel.scanned, funnel.priceFail, funnel.heldSkip,
                    funnel.signalMiss, funnel.scoreMiss, funnel.signalCount,
                    funnel.orderSuccess, funnel.orderBlocked, funnel.minScore,
                    nearMissJson, executionJson));
        } catch (Exception e) {
            log.error("모니터링 검사 로그 저장 실패: {}", e.getMessage());
        }
    }

    // ========== 1단계: 보유 종목 매도 체크 ==========

    private void checkHeldPositionsForSell(LocalDateTime now) {
        // JOIN FETCH로 Stock 즉시 로딩 — @Scheduled 스레드(세션 없음)에서 portfolio.getStock() 접근 안전
        List<Portfolio> heldPositions = portfolioRepository.findActiveWithStock();
        if (heldPositions.isEmpty()) {
            return;
        }

        log.info("보유 종목 매도 체크: {}건", heldPositions.size());
        LocalDate today = now.toLocalDate();

        for (Portfolio portfolio : heldPositions) {
            try {
                Stock stock = portfolio.getStock();
                KoreaInvestmentPriceResponse priceResponse = koreaInvestmentApiClient.getCurrentPrice(stock.getCode());

                if (priceResponse == null || !priceResponse.isSuccess()) {
                    continue;
                }

                // P2: 현재가 int→BigDecimal 무손실 소비(USD 센트 보존). KRW는 scale 0으로 기존 동작 동일.
                BigDecimal currentPrice = priceResponse.getCurrentPrice();
                if (currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                saveCurrentPrice(stock, currentPrice, priceResponse);

                BigDecimal profitRate = currentPrice.subtract(portfolio.getAverageBuyPrice())
                        .divide(portfolio.getAverageBuyPrice(), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));

                // 손절: -7% 이하
                if (profitRate.compareTo(new BigDecimal("-7")) <= 0) {
                    log.info("[손절] {} ({}) 수익률: {}% - 매도 실행", stock.getName(), stock.getCode(), profitRate);
                    Trade trade = tradingStrategyService.executeSell(stock, portfolio.getQuantity(), currentPrice,
                            String.format("장중 손절 (-%.1f%%)", profitRate.negate()));
                    if (trade != null) {
                        notificationServiceRouter.sendNotification(NotificationEvent.fromTrade(trade));
                    }
                    continue;
                }

                // 익절: +20% 이상
                if (profitRate.compareTo(new BigDecimal("20")) >= 0) {
                    log.info("[익절] {} ({}) 수익률: {}% - 매도 실행", stock.getName(), stock.getCode(), profitRate);
                    Trade trade = tradingStrategyService.executeSell(stock, portfolio.getQuantity(), currentPrice,
                            String.format("장중 익절 (+%.1f%%)", profitRate));
                    if (trade != null) {
                        notificationServiceRouter.sendNotification(NotificationEvent.fromTrade(trade));
                    }
                    continue;
                }

                // 점수 하락: DB 최신 can_slim_score < 40
                analysisScoreRepository.findByStockIdAndAnalysisDate(stock.getId(), today)
                        .ifPresent(score -> {
                            if (score.getCanSlimScore() < 40) {
                                log.info("[점수하락] {} ({}) CANSLIM 점수: {} - 매도 실행",
                                        stock.getName(), stock.getCode(), score.getCanSlimScore());
                                try {
                                    Trade trade = tradingStrategyService.executeSell(stock, portfolio.getQuantity(), currentPrice,
                                            String.format("장중 CANSLIM 점수 하락: %d", score.getCanSlimScore()));
                                    if (trade != null) {
                                        notificationServiceRouter.sendNotification(NotificationEvent.fromTrade(trade));
                                    }
                                } catch (Exception e) {
                                    log.error("매도 실행 실패: {} ({}) - {}", stock.getName(), stock.getCode(), e.getMessage());
                                }
                            }
                        });

                Thread.sleep(500);
            } catch (Exception e) {
                log.error("보유 종목 매도 체크 실패: {} - {}", portfolio.getStock().getName(), e.getMessage());
            }
        }
    }

    // ========== 2단계: 매수 대상 스캔 ==========

    private List<Stock> getTargetStocks() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        // 하드 "어제" 대신 stock_prices 내 최신 완료 거래일(오늘 미만)을 기준으로 — 동기화 지연·휴장일에 견고.
        // 오늘 날짜는 장중 미완성 데이터이므로 제외한다.
        LocalDate baseDate = stockPriceRepository.findLatestTradeDateBefore(today).orElse(null);
        if (baseDate == null) {
            log.warn("기준 거래일 데이터 없음 (오늘 {} 이전 stock_prices 없음) - 대상 종목 0", today);
            return Collections.emptyList();
        }

        org.springframework.data.domain.Pageable top200 = org.springframework.data.domain.PageRequest.of(0, 200);
        List<StockPrice> topVolume = stockPriceRepository.findTopByVolumeOnDate(baseDate, top200);
        List<StockPrice> topChange = stockPriceRepository.findTopByChangeRateOnDate(baseDate, top200);

        Map<Long, Stock> stockMap = new LinkedHashMap<>();
        for (StockPrice sp : topVolume) {
            stockMap.putIfAbsent(sp.getStock().getId(), sp.getStock());
        }
        for (StockPrice sp : topChange) {
            stockMap.putIfAbsent(sp.getStock().getId(), sp.getStock());
        }

        List<Stock> result = new ArrayList<>(stockMap.values());
        log.info("필터링된 대상 종목(기준일 {}): 거래량 상위 {} + 변동률 상위 {} = 중복 제거 후 {}개",
                baseDate, topVolume.size(), topChange.size(), result.size());
        return result;
    }

    private boolean isTradingHours(LocalDateTime utcNow) {
        java.time.ZoneId kst = java.time.ZoneId.of("Asia/Seoul");
        LocalDateTime kstNow = utcNow.atZone(kst).toLocalDateTime();
        LocalTime time = kstNow.toLocalTime();
        LocalTime marketOpen = LocalTime.of(9, 0);
        LocalTime marketClose = LocalTime.of(15, 30);

        DayOfWeek dayOfWeek = kstNow.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return false;
        }

        return !time.isBefore(marketOpen) && !time.isAfter(marketClose);
    }

    private void processStockForSignal(Stock stock, List<SignalStock> signalStocks, CheckFunnel funnel) {
        KoreaInvestmentPriceResponse priceResponse = koreaInvestmentApiClient.getCurrentPrice(stock.getCode());
        if (priceResponse == null || !priceResponse.isSuccess()) {
            funnel.priceFail++;
            return;
        }

        // P2: 현재가 int→BigDecimal 무손실 소비(USD 센트 보존). KRW는 scale 0으로 기존 동작 동일.
        BigDecimal currentPrice = priceResponse.getCurrentPrice();
        if (currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            funnel.priceFail++;
            return;
        }

        saveCurrentPrice(stock, currentPrice, priceResponse);

        // 보유 종목이면 매수 스킵
        Optional<Portfolio> existingPosition =
                portfolioRepository.findByStockIdAndActiveTrue(stock.getId());
        if (existingPosition.isPresent()) {
            funnel.heldSkip++;
            return;
        }

        CanSlimResult canSlimResult = canSlimAnalysisService.analyze(stock);
        CupAndHandleResult cupResult = cupAndHandleAnalyzer.analyze(stock);

        // 점수를 DB에 UPSERT (당일 기준)
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        analysisScoreRepository.upsertScore(
                stock.getId(),
                today,
                "INTRADAY_MONITOR",
                canSlimResult.totalScore().intValue(),
                canSlimResult.currentQuarterEarnings() != null ? canSlimResult.currentQuarterEarnings().score().intValue() : 0,
                canSlimResult.annualEarnings() != null ? canSlimResult.annualEarnings().score().intValue() : 0,
                canSlimResult.supplyDemand() != null ? canSlimResult.supplyDemand().score().intValue() : 0,
                canSlimResult.marketDirection() != null ? canSlimResult.marketDirection().score().intValue() : 0,
                canSlimResult.marketPosition() != null ? canSlimResult.marketPosition().score().intValue() : 0,
                canSlimResult.institutionalInvestor() != null ? canSlimResult.institutionalInvestor().score().intValue() : 0,
                cupResult.score() != null ? cupResult.score().intValue() : 0,
                cupResult.patternType() != null ? cupResult.patternType().name() : "NO_PATTERN",
                canSlimResult.totalScore().add(cupResult.score() != null ? cupResult.score() : BigDecimal.ZERO).intValue()
        );

        boolean canSlimBuy = canSlimResult.isBuySignal();
        boolean cupBuy = cupResult.isBuySignal();

        BigDecimal totalScore = canSlimResult.totalScore().add(cupResult.score());

        if (!canSlimBuy && !cupBuy) {
            funnel.signalMiss++;
            funnel.recordNearMiss(stock, currentPrice, canSlimResult, cupResult, totalScore, "SIGNAL_MISS",
                    String.format("매수 신호 없음 (CANSLIM %s<40, 컵 패턴 없음)", canSlimResult.totalScore()));
            return;
        }

        if (totalScore.compareTo(new BigDecimal(String.valueOf(minScore))) < 0) {
            funnel.scoreMiss++;
            funnel.recordNearMiss(stock, currentPrice, canSlimResult, cupResult, totalScore, "SCORE_MISS",
                    String.format("총점 %s < %d", totalScore, minScore));
            return;
        }

        String reason = buildReason(canSlimResult, cupResult, canSlimBuy, cupBuy);
        signalStocks.add(new SignalStock(stock, currentPrice, canSlimResult, cupResult, totalScore, reason));
    }

    private void saveCurrentPrice(Stock stock, BigDecimal currentPrice, KoreaInvestmentPriceResponse response) {
        try {
            KoreaInvestmentPriceResponse.PriceOutput output = response.getOutput();
            StockPrice stockPrice = new StockPrice(
                    stock,
                    LocalDate.now(java.time.ZoneId.of("Asia/Seoul")),
                    output != null ? parseBigDecimal(output.getOpeningPrice()) : currentPrice,
                    output != null ? parseBigDecimal(output.getHighPrice()) : currentPrice,
                    output != null ? parseBigDecimal(output.getLowPrice()) : currentPrice,
                    currentPrice,
                    output != null ? parseLong(output.getCumulativeVolume()) : 0L
            );
            if (output != null) {
                stockPrice.setChangeRate(parseBigDecimal(output.getChangeRate()));
            }
            stockPriceRepository.save(stockPrice);
        } catch (Exception e) {
            log.error("현재가 저장 실패: {} - {}", stock.getCode(), e.getMessage());
        }
    }

    private String buildReason(CanSlimResult canSlim, CupAndHandleResult cup, boolean canSlimBuy, boolean cupBuy) {
        StringBuilder sb = new StringBuilder();
        if (canSlimBuy && cupBuy) {
            sb.append("강력 매수 - CANSLIM: ").append(canSlim.totalScore())
                    .append(", 컵앤핸들: ").append(cup.score());
        } else if (canSlimBuy) {
            sb.append("CANSLIM 매수 신호 - 점수: ").append(canSlim.totalScore());
        } else if (cupBuy) {
            sb.append("컵앤핸들 매수 신호 - 패턴: ").append(cup.patternType())
                    .append(", 점수: ").append(cup.score());
        }
        return sb.toString();
    }

    // ========== 3단계: 매수 주문 실행 ==========

    private void executeBuyOrders(List<SignalStock> signalStocks, CheckFunnel funnel) {
        long activePositions = portfolioRepository.findByActiveTrue().size();
        int availableSlots = maxPositions - (int) activePositions;

        if (availableSlots <= 0) {
            log.info("최대 보유 종목 수 도달: {}", maxPositions);
            for (SignalStock s : signalStocks) {
                funnel.recordExecution(s, "LIMIT_REACHED", "최대 보유 종목 수 도달: " + maxPositions);
            }
            return;
        }

        KoreaInvestmentBalanceResponse balance = getBalance();
        if (balance == null) {
            for (SignalStock s : signalStocks) {
                funnel.recordExecution(s, "BALANCE_FAIL", "잔고 조회 실패");
            }
            return;
        }

        BigDecimal availableCashBd = parseBigDecimal(balance.getOutput2().get(0).getWithdrawableAmount());
        if (availableCashBd.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("예수금 부족: {}원", availableCashBd);
            for (SignalStock s : signalStocks) {
                funnel.recordExecution(s, "INSUFFICIENT_CASH", "예수금 부족: " + availableCashBd + "원");
            }
            return;
        }
        long availableCash = availableCashBd.longValue();

        // 매수 계획 수립(순수 로직): 예수금 필터 → 점수순 슬롯 선정 → 종목당 상한(positionRate%) → 잔여 이월.
        // 소수점 주문이 불가하므로 정수 주 단위. 1주가 예수금 초과인 종목은 슬롯 낭비 없이 제외.
        Map<String, SignalStock> byCode = new LinkedHashMap<>();
        List<PlanInput> inputs = new ArrayList<>();
        for (SignalStock s : signalStocks) {
            byCode.put(s.stock.getCode(), s);
            inputs.add(new PlanInput(s.stock.getCode(), s.currentPrice.longValue(), s.totalScore.intValue()));
        }
        List<PlanResult> plan = planPurchases(inputs, availableCash, availableSlots, positionRate);

        for (PlanResult pr : plan) {
            SignalStock signal = byCode.get(pr.code());
            if (signal == null) continue;
            if (pr.status() != PlanStatus.BUY) {
                funnel.recordExecution(signal, pr.status().name(), pr.detail());
                continue;
            }

            BigDecimal quantity = BigDecimal.valueOf(pr.qty());
            log.info("매수 시도: {} {}주 @ {}원 (배분 {}원)",
                    signal.stock.getName(), pr.qty(), signal.currentPrice, pr.cost());
            try {
                Trade trade = null;
                for (int retry = 0; retry < 3; retry++) {
                    try {
                        trade = tradingStrategyService.executeBuy(
                                signal.stock,
                                quantity,
                                signal.currentPrice,
                                signal.reason + String.format(" (%d주 @ %s원, 배분 %d원)", pr.qty(), signal.currentPrice, pr.cost())
                        );
                        break;
                    } catch (Exception retryEx) {
                        if (retry < 2) {
                            log.warn("매수 재시도 ({}/3): {} ({}) - {}", retry + 1, signal.stock.getName(), signal.stock.getCode(), retryEx.getMessage());
                            Thread.sleep(1000);
                        } else {
                            throw retryEx;
                        }
                    }
                }

                if (trade != null) {
                    notificationServiceRouter.sendNotification(NotificationEvent.fromTrade(trade));
                    funnel.recordExecution(signal, "ORDER_SUCCESS",
                            String.format("%d주 @ %s원 (배분 %d원)", pr.qty(), signal.currentPrice, pr.cost()));
                } else {
                    funnel.recordExecution(signal, "ORDER_FAILED", "주문 결과 없음(수량 0 또는 시뮬레이션)");
                }
                Thread.sleep(1000);
            } catch (Exception e) {
                log.error("매수 실행 실패: {} ({}) - {}", signal.stock.getName(), signal.stock.getCode(), e.getMessage());
                funnel.recordExecution(signal, "ORDER_FAILED", "주문 실패: " + e.getMessage());
            }
        }
    }

    // ========== 매수 계획(순수 함수 — 부작용 없음, 단위테스트 대상) ==========

    enum PlanStatus { BUY, UNAFFORDABLE, LIMIT_REACHED, MIN_AMOUNT }

    /** 계획 입력: 종목코드·현재가(원)·총점. */
    record PlanInput(String code, long price, int score) {}

    /** 계획 결과: 종목별 매수수량 또는 탈락 사유. */
    record PlanResult(String code, PlanStatus status, int qty, long cost, String detail) {}

    /**
     * 정수 주 단위 분산 매수 계획을 수립한다(부작용 없음).
     * 1) 예수금 필터: 1주 가격 &gt; 예수금 → UNAFFORDABLE(소수점 미지원, 슬롯 미소비),
     * 2) 점수 desc 정렬 후 가용 슬롯만큼 선정(초과분 LIMIT_REACHED),
     * 3) 종목당 상한 = 예수금 × positionRate%(분산: 10% 기본)까지 점수순으로 정수 매수,
     * 4) 잔여현금을 점수순 라운드로빈(1주씩)으로 이월 — 분산(다양성) 우선,
     * 5) 끝내 0주면 MIN_AMOUNT.
     */
    static List<PlanResult> planPurchases(List<PlanInput> signals, long availableCash,
                                          int availableSlots, int positionRate) {
        List<PlanResult> results = new ArrayList<>();
        if (availableSlots <= 0) {
            for (PlanInput s : signals) {
                results.add(new PlanResult(s.code(), PlanStatus.LIMIT_REACHED, 0, 0, "최대 보유 종목 수 도달"));
            }
            return results;
        }

        // 1) 예수금 필터 — 슬롯 미소비. price<=0(유효하지 않은 현재가)은 0으로 나눔·무한이월 방지 위해 제외.
        List<PlanInput> affordable = new ArrayList<>();
        for (PlanInput s : signals) {
            if (s.price() <= 0) {
                results.add(new PlanResult(s.code(), PlanStatus.MIN_AMOUNT, 0, 0, "유효하지 않은 현재가"));
            } else if (s.price() > availableCash) {
                results.add(new PlanResult(s.code(), PlanStatus.UNAFFORDABLE, 0, 0,
                        "1주 " + s.price() + "원 > 예수금 " + availableCash + "원 (소수점 미지원)"));
            } else {
                affordable.add(s);
            }
        }

        // 2) 점수순 정렬 후 슬롯 선정
        affordable.sort(Comparator.comparingInt(PlanInput::score).reversed());
        int limit = Math.min(availableSlots, affordable.size());
        List<PlanInput> selected = new ArrayList<>(affordable.subList(0, limit));
        for (int i = limit; i < affordable.size(); i++) {
            results.add(new PlanResult(affordable.get(i).code(), PlanStatus.LIMIT_REACHED, 0, 0,
                    "가용 슬롯(" + availableSlots + ") 초과"));
        }

        // 3) 종목당 상한(positionRate%) — 점수순 정수 매수
        long perPositionCap = Math.max(1L, availableCash * positionRate / 100);
        Map<String, Integer> qty = new LinkedHashMap<>();
        for (PlanInput s : selected) qty.put(s.code(), 0);
        long remaining = availableCash;
        for (PlanInput s : selected) {
            long budget = Math.min(perPositionCap, remaining);
            int q = (int) (budget / s.price());
            if (q > 0) {
                qty.merge(s.code(), q, Integer::sum);
                remaining -= (long) q * s.price();
            }
        }

        // 4) 잔여현금 이월 — 점수순 라운드로빈(1주씩), 분산 우선
        boolean progressed = true;
        while (progressed && remaining > 0) {
            progressed = false;
            for (PlanInput s : selected) {
                if (remaining >= s.price()) {
                    qty.merge(s.code(), 1, Integer::sum);
                    remaining -= s.price();
                    progressed = true;
                }
            }
        }

        // 5) 결과화
        for (PlanInput s : selected) {
            int q = qty.get(s.code());
            if (q > 0) {
                results.add(new PlanResult(s.code(), PlanStatus.BUY, q, (long) q * s.price(), null));
            } else {
                results.add(new PlanResult(s.code(), PlanStatus.MIN_AMOUNT, 0, 0,
                        "잔여현금 부족(1주 " + s.price() + "원)"));
            }
        }
        return results;
    }

    private KoreaInvestmentBalanceResponse getBalance() {
        try {
            KoreaInvestmentBalanceResponse balance = koreaInvestmentApiClient.getBalance();
            if (balance != null && balance.isSuccess() && balance.getOutput2() != null && !balance.getOutput2().isEmpty()) {
                return balance;
            }
        } catch (Exception e) {
            log.warn("잔고 조회 실패: {}", e.getMessage());
        }
        return null;
    }

    public boolean isMonitoring() { return monitoring; }
    public LocalDateTime getLastCheckTime() { return lastCheckTime; }
    public int getLastSignalCount() { return lastSignalCount; }
    public int getLastScanCount() { return lastScanCount; }
    public int getMinScore() { return minScore; }
    public List<Map<String, Object>> getLastSignals() { return lastSignals; }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            return Long.parseLong(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private record SignalStock(
            Stock stock,
            BigDecimal currentPrice,
            CanSlimResult canSlimResult,
            CupAndHandleResult cupResult,
            BigDecimal totalScore,
            String reason
    ) {}

    /**
     * 1회 검사의 퍼널 카운터와 탈락/실행 사유를 누적한다.
     * 요소별 분해는 analysis_scores 저장 매핑과 동일하게 맞춘다
     * (institutional은 현재 영속화 경로에서 0으로 저장됨 — upsertScore와 일치).
     */
    private static final class CheckFunnel {
        final int minScore;
        int scanned, priceFail, heldSkip, signalMiss, scoreMiss, signalCount, orderSuccess, orderBlocked;
        final List<NearMiss> nearMisses = new ArrayList<>();
        final Map<String, ExecutionResult> executionByCode = new LinkedHashMap<>();

        CheckFunnel(int minScore) { this.minScore = minScore; }

        void recordNearMiss(Stock stock, BigDecimal price, CanSlimResult cs, CupAndHandleResult cup,
                            BigDecimal total, String stage, String reason) {
            nearMisses.add(new NearMiss(
                    stock.getCode(),
                    stock.getName(),
                    total.intValue(),
                    cs.totalScore().intValue(),
                    cup.score() != null ? cup.score().intValue() : 0,
                    cs.currentQuarterEarnings() != null ? cs.currentQuarterEarnings().score().intValue() : 0,
                    cs.annualEarnings() != null ? cs.annualEarnings().score().intValue() : 0,
                    cs.supplyDemand() != null ? cs.supplyDemand().score().intValue() : 0,
                    cs.marketDirection() != null ? cs.marketDirection().score().intValue() : 0,
                    cs.marketPosition() != null ? cs.marketPosition().score().intValue() : 0,
                    cs.institutionalInvestor() != null ? cs.institutionalInvestor().score().intValue() : 0,
                    stage,
                    reason));
        }

        List<NearMiss> topNearMiss(int n) {
            return nearMisses.stream()
                    .sorted(Comparator.comparingInt(NearMiss::totalScore).reversed())
                    .limit(n)
                    .collect(Collectors.toList());
        }

        void recordExecution(SignalStock s, String status, String detail) {
            executionByCode.put(s.stock.getCode(), new ExecutionResult(
                    s.stock.getCode(),
                    s.stock.getName(),
                    s.currentPrice,
                    s.canSlimResult.totalScore().intValue(),
                    s.cupResult.score() != null ? s.cupResult.score().intValue() : 0,
                    s.totalScore.intValue(),
                    s.reason,
                    status,
                    detail));
            if ("ORDER_SUCCESS".equals(status)) {
                orderSuccess++;
            } else {
                orderBlocked++;
            }
        }

        record NearMiss(String code, String name, int totalScore, int canSlimScore, int cupScore,
                        int quarterly, int annual, int supplyDemand, int marketDirection,
                        int industryLeader, int institutional, String stage, String reason) {}

        // P2: price int→BigDecimal — 실행 관측 레코드도 USD 센트 유실 방지(KRW는 scale 0으로 JSON 직렬화 동일).
        record ExecutionResult(String code, String name, BigDecimal price, int canSlimScore, int cupScore,
                               int totalScore, String reason, String status, String detail) {}
    }
}
