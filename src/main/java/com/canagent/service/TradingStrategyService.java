package com.canagent.service;

import com.canagent.domain.portfolio.Portfolio;
import com.canagent.domain.stock.Stock;
import com.canagent.domain.trading.Trade;
import com.canagent.domain.trading.TradeType;
import com.canagent.repository.PortfolioRepository;
import com.canagent.repository.TradeRepository;
import com.canagent.service.analysis.CanSlimAnalysisService;
import com.canagent.service.analysis.CupAndHandleAnalyzer;
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

    @Value("${trading.max-positions:10}")
    private int maxPositions;

    @Value("${trading.position-size:1000000}")
    private BigDecimal positionSize;

    @Value("${trading.stop-loss-rate:7}")
    private BigDecimal stopLossRate;

    @Value("${trading.take-profit-rate:20}")
    private BigDecimal takeProfitRate;

    public TradingStrategyService(
            CanSlimAnalysisService canSlimAnalysisService,
            CupAndHandleAnalyzer cupAndHandleAnalyzer,
            PortfolioRepository portfolioRepository,
            TradeRepository tradeRepository) {
        this.canSlimAnalysisService = canSlimAnalysisService;
        this.cupAndHandleAnalyzer = cupAndHandleAnalyzer;
        this.portfolioRepository = portfolioRepository;
        this.tradeRepository = tradeRepository;
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

        if (canSlimBuy && cupBuy) {
            int quantity = positionSize.divide(currentPrice, 0, RoundingMode.FLOOR).intValue();
            String reason = String.format("CANSLIM 점수: %s, 컵앤핸들: %s",
                    canSlimResult.totalScore(), cupResult.reason());
            return TradingDecision.buy(quantity, reason);
        }

        if (canSlimBuy) {
            int quantity = positionSize.divide(currentPrice, 0, RoundingMode.FLOOR).intValue();
            String reason = String.format("CANSLIM 점수: %s (강력 매수)", canSlimResult.totalScore());
            return TradingDecision.buy(quantity, reason);
        }

        if (cupBuy) {
            int quantity = positionSize.divide(currentPrice, 0, RoundingMode.FLOOR).intValue();
            String reason = String.format("컵앤핸들 패턴: %s", cupResult.reason());
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
    public Trade executeBuy(Stock stock, int quantity, BigDecimal price, String reason) {
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
    public Trade executeSell(Stock stock, int quantity, BigDecimal price, String reason) {
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
            int quantity,
            String reason
    ) {
        public static TradingDecision buy(int quantity, String reason) {
            return new TradingDecision(TradeType.BUY, quantity, reason);
        }

        public static TradingDecision sell(int quantity, String reason) {
            return new TradingDecision(TradeType.SELL, quantity, reason);
        }

        public static TradingDecision hold(String reason) {
            return new TradingDecision(null, 0, reason);
        }

        public boolean shouldBuy() {
            return action == TradeType.BUY && quantity > 0;
        }

        public boolean shouldSell() {
            return action == TradeType.SELL && quantity > 0;
        }
    }
}
