package com.canagent.service;

import com.canagent.config.ApiConfig;
import com.canagent.domain.portfolio.Portfolio;
import com.canagent.domain.stock.Stock;
import com.canagent.domain.trading.Trade;
import com.canagent.domain.trading.TradeType;
import com.canagent.repository.PortfolioRepository;
import com.canagent.repository.TradeRepository;
import com.canagent.service.analysis.CanSlimAnalysisService;
import com.canagent.service.analysis.CupAndHandleAnalyzer;
import com.canagent.service.dto.KoreaInvestmentBalanceResponse;
import com.canagent.service.dto.KoreaInvestmentOrderResponse;
import com.canagent.service.dto.CanSlimResult;
import com.canagent.service.dto.CupAndHandleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@Service
public class TradingStrategyService {

    private static final Logger log = LoggerFactory.getLogger(TradingStrategyService.class);

    private final CanSlimAnalysisService canSlimAnalysisService;
    private final CupAndHandleAnalyzer cupAndHandleAnalyzer;
    private final PortfolioRepository portfolioRepository;
    private final TradeRepository tradeRepository;
    private final KoreaInvestmentApiClient koreaInvestmentApiClient;
    private final ApiConfig apiConfig;

    @Value("${trading.max-positions:10}")
    private int maxPositions;

    @Value("${trading.stop-loss-rate:7}")
    private BigDecimal stopLossRate;

    @Value("${trading.take-profit-rate:20}")
    private BigDecimal takeProfitRate;

    @Value("${trading.real-trading:false}")
    private boolean realTrading;

    public TradingStrategyService(
            CanSlimAnalysisService canSlimAnalysisService,
            CupAndHandleAnalyzer cupAndHandleAnalyzer,
            PortfolioRepository portfolioRepository,
            TradeRepository tradeRepository,
            KoreaInvestmentApiClient koreaInvestmentApiClient,
            ApiConfig apiConfig) {
        this.canSlimAnalysisService = canSlimAnalysisService;
        this.cupAndHandleAnalyzer = cupAndHandleAnalyzer;
        this.portfolioRepository = portfolioRepository;
        this.tradeRepository = tradeRepository;
        this.koreaInvestmentApiClient = koreaInvestmentApiClient;
        this.apiConfig = apiConfig;
    }

    public TradingDecision evaluateBuy(Stock stock, BigDecimal currentPrice) {
        log.info("매수 평가: {} ({})", stock.getName(), stock.getCode());

        CanSlimResult canSlimResult = canSlimAnalysisService.analyze(stock);
        CupAndHandleResult cupResult = cupAndHandleAnalyzer.analyze(stock);

        boolean canSlimBuy = canSlimResult.isBuySignal();
        boolean cupBuy = cupResult.isBuySignal() || cupResult.patternType() == CupAndHandleResult.PatternType.HANDLE_COMPLETE;

        Optional<Portfolio> existingPosition = portfolioRepository.findByStockIdAndActiveTrue(stock.getId());
        if (existingPosition.isPresent()) {
            return TradingDecision.hold("이미 보유 중인 종목");
        }

        long activePositions = portfolioRepository.findByActiveTrue().size();
        if (activePositions >= maxPositions) {
            return TradingDecision.hold("최대 보유 종목 수 도달");
        }

        // 실제 예수금 조회
        KoreaInvestmentBalanceResponse balance = koreaInvestmentApiClient.getBalance();
        if (!balance.isSuccess() || balance.getOutput2() == null || balance.getOutput2().isEmpty()) {
            log.warn("잔고 조회 실패: {}", balance.getMsg1());
            return TradingDecision.hold("잔고 조회 실패");
        }

        String availableCashStr = balance.getOutput2().get(0).getWithdrawableAmount();
        BigDecimal availableCash = new BigDecimal(availableCashStr);
        BigDecimal positionSize = availableCash.divide(new BigDecimal(maxPositions), 0, RoundingMode.FLOOR);
        log.info("예수금: {}원, 종목당 배분: {}원", availableCash, positionSize);

        if (positionSize.compareTo(new BigDecimal("1000")) < 0) {
            return TradingDecision.hold("예수금 부족 (" + availableCash + "원)");
        }

        BigDecimal quantity = positionSize.divide(currentPrice, 4, RoundingMode.FLOOR);

        if (canSlimBuy && cupBuy) {
            String reason = String.format("CANSLIM 점수: %s, 컵앤핸들: %s, 예수금: %s원",
                    canSlimResult.totalScore(), cupResult.reason(), availableCash);
            return TradingDecision.buy(quantity, reason);
        }

        if (canSlimBuy) {
            String reason = String.format("CANSLIM 점수: %s (강력 매수), 예수금: %s원",
                    canSlimResult.totalScore(), availableCash);
            return TradingDecision.buy(quantity, reason);
        }

        if (cupBuy) {
            String reason = String.format("컵앤핸들 패턴: %s, 예수금: %s원",
                    cupResult.reason(), availableCash);
            return TradingDecision.buy(quantity, reason);
        }

        return TradingDecision.hold("매수 조건 미충족");
    }

    public TradingDecision evaluateSell(Stock stock, BigDecimal currentPrice) {
        log.info("매도 평가: {} ({})", stock.getName(), stock.getCode());

        Optional<Portfolio> portfolioOpt = portfolioRepository.findByStockIdAndActiveTrue(stock.getId());
        if (portfolioOpt.isEmpty()) {
            return TradingDecision.hold("보유 종목 아님");
        }

        Portfolio portfolio = portfolioOpt.get();
        BigDecimal profitRate = currentPrice.subtract(portfolio.getAverageBuyPrice())
                .divide(portfolio.getAverageBuyPrice(), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        if (profitRate.compareTo(stopLossRate.negate()) <= 0) {
            return TradingDecision.sell(
                    portfolio.getQuantity(),
                    String.format("손절 (-%.1f%%)", profitRate.negate())
            );
        }

        if (profitRate.compareTo(takeProfitRate) >= 0) {
            return TradingDecision.sell(
                    portfolio.getQuantity(),
                    String.format("익절 (+%.1f%%)", profitRate)
            );
        }

        CanSlimResult canSlimResult = canSlimAnalysisService.analyze(stock);
        if (canSlimResult.totalScore().compareTo(new BigDecimal("40")) < 0) {
            return TradingDecision.sell(
                    portfolio.getQuantity(),
                    String.format("CANSLIM 점수 하락: %s", canSlimResult.totalScore())
            );
        }

        return TradingDecision.hold("보유 유지");
    }

    @Transactional
    public Trade executeBuy(Stock stock, BigDecimal quantity, BigDecimal price, String reason) {
        log.info("매수 실행: {} {}주 @ {}원 - {}", stock.getName(), quantity, price, reason);

        if (realTrading) {
            int intQty = quantity.setScale(0, RoundingMode.FLOOR).intValue();
            if (intQty <= 0) {
                log.warn("매수 수량 0 이하: {} (가격: {})", quantity, price);
                return null;
            }
            KoreaInvestmentOrderResponse response = koreaInvestmentApiClient.buy(
                    stock.getCode(), intQty, price.intValue());
            if (!response.isSuccess()) {
                log.error("한국투자증권 매수 주문 실패: {}", response.getMsg1());
                throw new RuntimeException("매수 주문 실패: " + response.getMsg1());
            }
        } else {
            if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("매수 수량 0 이하 (시뮬레이션): {} (가격: {})", quantity, price);
                return null;
            }
        }

        Trade trade = new Trade(stock, TradeType.BUY, quantity, price, reason);

        Optional<Portfolio> existing = portfolioRepository.findByStockIdAndActiveTrue(stock.getId());
        if (existing.isPresent()) {
            Portfolio portfolio = existing.get();
            portfolio.addQuantity(quantity, price);
            portfolioRepository.save(portfolio);
        } else {
            Portfolio portfolio = new Portfolio(stock, quantity, price);
            portfolioRepository.save(portfolio);
        }

        return tradeRepository.save(trade);
    }

    @Transactional
    public Trade executeSell(Stock stock, BigDecimal quantity, BigDecimal price, String reason) {
        log.info("매도 실행: {} {}주 @ {}원 - {}", stock.getName(), quantity, price, reason);

        if (realTrading) {
            int intQty = quantity.setScale(0, RoundingMode.FLOOR).intValue();
            if (intQty <= 0) {
                log.warn("매도 수량 0 이하: {} (가격: {})", quantity, price);
                return null;
            }
            KoreaInvestmentOrderResponse response = koreaInvestmentApiClient.sell(
                    stock.getCode(), intQty, price.intValue());
            if (!response.isSuccess()) {
                log.error("한국투자증권 매도 주문 실패: {}", response.getMsg1());
                throw new RuntimeException("매도 주문 실패: " + response.getMsg1());
            }
        } else {
            if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("매도 수량 0 이하 (시뮬레이션): {} (가격: {})", quantity, price);
                return null;
            }
        }

        Trade trade = new Trade(stock, TradeType.SELL, quantity, price, reason);

        Optional<Portfolio> portfolioOpt = portfolioRepository.findByStockIdAndActiveTrue(stock.getId());
        if (portfolioOpt.isPresent()) {
            Portfolio portfolio = portfolioOpt.get();
            BigDecimal profitRate = price.subtract(portfolio.getAverageBuyPrice())
                    .divide(portfolio.getAverageBuyPrice(), 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            trade.setProfitRate(profitRate);

            portfolio.reduceQuantity(quantity);
            portfolioRepository.save(portfolio);
        }

        return tradeRepository.save(trade);
    }

    public record TradingDecision(
            TradeType action,
            BigDecimal quantity,
            String reason
    ) {
        public static TradingDecision buy(BigDecimal quantity, String reason) {
            return new TradingDecision(TradeType.BUY, quantity, reason);
        }

        public static TradingDecision sell(BigDecimal quantity, String reason) {
            return new TradingDecision(TradeType.SELL, quantity, reason);
        }

        public static TradingDecision hold(String reason) {
            return new TradingDecision(null, BigDecimal.ZERO, reason);
        }

        public boolean shouldBuy() {
            return action == TradeType.BUY && quantity.compareTo(BigDecimal.ZERO) > 0;
        }

        public boolean shouldSell() {
            return action == TradeType.SELL && quantity.compareTo(BigDecimal.ZERO) > 0;
        }
    }
}
