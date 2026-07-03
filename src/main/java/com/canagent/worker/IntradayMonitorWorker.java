package com.canagent.worker;

import com.canagent.domain.portfolio.Portfolio;
import com.canagent.domain.stock.Stock;
import com.canagent.domain.stock.StockPrice;
import com.canagent.domain.trading.Trade;
import com.canagent.repository.AnalysisScoreRepository;
import com.canagent.repository.PortfolioRepository;
import com.canagent.repository.StockPriceRepository;
import com.canagent.repository.StockRepository;
import com.canagent.service.KoreaInvestmentApiClient;
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
    private final KoreaInvestmentApiClient koreaInvestmentApiClient;
    private final CanSlimAnalysisService canSlimAnalysisService;
    private final CupAndHandleAnalyzer cupAndHandleAnalyzer;
    private final TradingStrategyService tradingStrategyService;
    private final NotificationServiceRouter notificationServiceRouter;

    @Value("${trading.max-positions:10}")
    private int maxPositions;

    @Value("${trading.position-rate:10}")
    private int positionRate;

    @Value("${trading.min-score:120}")
    private int minScore;

    private volatile boolean monitoring = false;
    private volatile LocalDateTime lastCheckTime;
    private volatile int lastSignalCount;
    private volatile List<Map<String, Object>> lastSignals = Collections.emptyList();

    public IntradayMonitorWorker(
            StockRepository stockRepository,
            StockPriceRepository stockPriceRepository,
            PortfolioRepository portfolioRepository,
            AnalysisScoreRepository analysisScoreRepository,
            KoreaInvestmentApiClient koreaInvestmentApiClient,
            CanSlimAnalysisService canSlimAnalysisService,
            CupAndHandleAnalyzer cupAndHandleAnalyzer,
            TradingStrategyService tradingStrategyService,
            NotificationServiceRouter notificationServiceRouter) {
        this.stockRepository = stockRepository;
        this.stockPriceRepository = stockPriceRepository;
        this.portfolioRepository = portfolioRepository;
        this.analysisScoreRepository = analysisScoreRepository;
        this.koreaInvestmentApiClient = koreaInvestmentApiClient;
        this.canSlimAnalysisService = canSlimAnalysisService;
        this.cupAndHandleAnalyzer = cupAndHandleAnalyzer;
        this.tradingStrategyService = tradingStrategyService;
        this.notificationServiceRouter = notificationServiceRouter;
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

        try {
            // 1단계: 보유 종목 매도 체크 (≤10개, ~5초)
            checkHeldPositionsForSell(now);

            // 2단계: 매수 대상 스캔 (~400종목)
            List<Stock> targetStocks = getTargetStocks();
            log.info("대상 종목 수: {}", targetStocks.size());

            List<SignalStock> signalStocks = new ArrayList<>();

            for (Stock stock : targetStocks) {
                try {
                    processStockForSignal(stock, signalStocks);
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("종목 모니터링 실패: {} ({}) - {}", stock.getName(), stock.getCode(), e.getMessage());
                }
            }

            // 3단계: 매수 주문 실행
            LocalDateTime checkTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul"));
            if (!signalStocks.isEmpty() && isTradingHours(checkTime)) {
                executeBuyOrders(signalStocks);
            } else if (!signalStocks.isEmpty()) {
                log.info("장 마감으로 매수 건너뜀: {}건", signalStocks.size());
            }

            lastSignalCount = signalStocks.size();
            lastSignals = signalStocks.stream()
                    .map(s -> Map.<String, Object>of(
                            "code", s.stock.getCode(),
                            "name", s.stock.getName(),
                            "price", s.currentPrice,
                            "canSlimScore", s.canSlimResult.totalScore(),
                            "cupScore", s.cupResult.score(),
                            "totalScore", s.totalScore,
                            "reason", s.reason
                    ))
                    .collect(Collectors.toList());

            log.info("장중 모니터링 완료: 매수 신호 {}건 발견", signalStocks.size());
        } catch (Exception e) {
            log.error("장중 모니터링 실패: {}", e.getMessage());
        } finally {
            monitoring = false;
        }
    }

    // ========== 1단계: 보유 종목 매도 체크 ==========

    private void checkHeldPositionsForSell(LocalDateTime now) {
        List<Portfolio> heldPositions = portfolioRepository.findByActiveTrue();
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

                int currentPriceInt = priceResponse.getCurrentPrice();
                if (currentPriceInt <= 0) {
                    continue;
                }

                BigDecimal currentPrice = new BigDecimal(currentPriceInt);
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
        LocalDate yesterday = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(1);

        List<StockPrice> topVolume = stockPriceRepository.findTopByVolumeOnDate(yesterday, 200);
        List<StockPrice> topChange = stockPriceRepository.findTopByChangeRateOnDate(yesterday, 200);

        Map<Long, Stock> stockMap = new LinkedHashMap<>();
        for (StockPrice sp : topVolume) {
            stockMap.putIfAbsent(sp.getStock().getId(), sp.getStock());
        }
        for (StockPrice sp : topChange) {
            stockMap.putIfAbsent(sp.getStock().getId(), sp.getStock());
        }

        List<Stock> result = new ArrayList<>(stockMap.values());
        log.info("필터링된 대상 종목: 거래량 상위 {} + 변동률 상위 {} = 중복 제거 후 {}개",
                topVolume.size(), topChange.size(), result.size());
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

    private void processStockForSignal(Stock stock, List<SignalStock> signalStocks) {
        KoreaInvestmentPriceResponse priceResponse = koreaInvestmentApiClient.getCurrentPrice(stock.getCode());
        if (priceResponse == null || !priceResponse.isSuccess()) {
            return;
        }

        int currentPriceInt = priceResponse.getCurrentPrice();
        if (currentPriceInt <= 0) {
            return;
        }

        BigDecimal currentPrice = new BigDecimal(currentPriceInt);

        saveCurrentPrice(stock, currentPrice, priceResponse);

        // 보유 종목이면 매수 스킵
        Optional<Portfolio> existingPosition =
                portfolioRepository.findByStockIdAndActiveTrue(stock.getId());
        if (existingPosition.isPresent()) {
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
                0,
                cupResult.score() != null ? cupResult.score().intValue() : 0,
                cupResult.patternType() != null ? cupResult.patternType().name() : "NO_PATTERN",
                canSlimResult.totalScore().add(cupResult.score() != null ? cupResult.score() : BigDecimal.ZERO).intValue()
        );

        boolean canSlimBuy = canSlimResult.isBuySignal();
        boolean cupBuy = cupResult.isBuySignal();

        if (!canSlimBuy && !cupBuy) {
            return;
        }

        BigDecimal totalScore = canSlimResult.totalScore().add(cupResult.score());
        if (totalScore.compareTo(new BigDecimal(String.valueOf(minScore))) < 0) {
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
                    LocalDate.now(),
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

    private void executeBuyOrders(List<SignalStock> signalStocks) {
        long activePositions = portfolioRepository.findByActiveTrue().size();
        int availableSlots = maxPositions - (int) activePositions;

        if (availableSlots <= 0) {
            log.info("최대 보유 종목 수 도달: {}", maxPositions);
            return;
        }

        KoreaInvestmentBalanceResponse balance = getBalance();
        if (balance == null) {
            return;
        }

        BigDecimal availableCash = parseBigDecimal(balance.getOutput2().get(0).getWithdrawableAmount());
        if (availableCash.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("예수금 부족: {}원", availableCash);
            return;
        }

        List<SignalStock> sortedSignals = signalStocks.stream()
                .sorted(Comparator.comparing((SignalStock s) -> s.totalScore).reversed())
                .limit(availableSlots)
                .collect(Collectors.toList());

        BigDecimal totalScore = sortedSignals.stream()
                .map(s -> s.totalScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        for (SignalStock signal : sortedSignals) {
            try {
                BigDecimal ratio = signal.totalScore.divide(totalScore, 4, RoundingMode.HALF_UP);
                BigDecimal investAmount = availableCash.multiply(ratio)
                        .setScale(0, RoundingMode.FLOOR);

                if (investAmount.compareTo(new BigDecimal("5000")) < 0) {
                    log.info("최소 주문금액 미달: {} ({}) - {}원", signal.stock.getName(), signal.stock.getCode(), investAmount);
                    continue;
                }

                BigDecimal quantity = investAmount.divide(signal.currentPrice, 0, RoundingMode.FLOOR);

                if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                    log.info("매수 수량 0: {} ({})", signal.stock.getName(), signal.stock.getCode());
                    continue;
                }

                log.info("매수 시도: {} {}주 @ {}원 (배분: {}원, 비율: {}%)",
                        signal.stock.getName(), quantity, signal.currentPrice, investAmount,
                        ratio.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP));

                Trade trade = null;
                for (int retry = 0; retry < 3; retry++) {
                    try {
                        trade = tradingStrategyService.executeBuy(
                                signal.stock,
                                quantity,
                                signal.currentPrice,
                                signal.reason + String.format(" (배분: %s원, 비율: %.1f%%)", investAmount, ratio.multiply(new BigDecimal("100")))
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
                }

                availableCash = availableCash.subtract(investAmount);
                Thread.sleep(1000);
            } catch (Exception e) {
                log.error("매수 실행 실패: {} ({}) - {}", signal.stock.getName(), signal.stock.getCode(), e.getMessage());
            }
        }
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
}
