package com.canagent.service;

import com.canagent.domain.portfolio.Portfolio;
import com.canagent.domain.trading.TradeType;
import com.canagent.repository.PortfolioRepository;
import com.canagent.repository.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);

    private final PortfolioRepository portfolioRepository;
    private final TradeRepository tradeRepository;

    @Value("${trading.max-positions:10}")
    private int maxPositions;

    @Value("${trading.position-size:1000000}")
    private BigDecimal positionSize;

    public PortfolioService(PortfolioRepository portfolioRepository,
                           TradeRepository tradeRepository) {
        this.portfolioRepository = portfolioRepository;
        this.tradeRepository = tradeRepository;
    }

    public List<Portfolio> getActivePortfolios() {
        return portfolioRepository.findByActiveTrue();
    }

    public BigDecimal getTotalInvestment() {
        return portfolioRepository.sumTotalBuyAmountByActiveTrue();
    }

    public BigDecimal getTotalCurrentValue() {
        return portfolioRepository.sumTotalCurrentValueByActiveTrue();
    }

    public BigDecimal getTotalProfit() {
        return portfolioRepository.sumTotalProfitAmountByActiveTrue();
    }

    public BigDecimal getTotalProfitRate() {
        BigDecimal totalInvestment = getTotalInvestment();
        if (totalInvestment.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return getTotalProfit()
                .divide(totalInvestment, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    public long getActivePortfolioCount() {
        return portfolioRepository.countByActiveTrue();
    }

    public int getAvailableSlots() {
        long activeCount = getActivePortfolioCount();
        return (int) Math.max(0, maxPositions - activeCount);
    }

    public boolean canOpenNewPosition() {
        return getAvailableSlots() > 0;
    }

    public BigDecimal getAvailableInvestmentAmount() {
        BigDecimal totalInvestment = getTotalInvestment();
        BigDecimal maxTotalInvestment = positionSize.multiply(new BigDecimal(maxPositions));
        return maxTotalInvestment.subtract(totalInvestment).max(BigDecimal.ZERO);
    }

    public BigDecimal getPositionWeight(Portfolio portfolio) {
        BigDecimal totalCurrentValue = getTotalCurrentValue();
        if (totalCurrentValue.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal positionValue = portfolio.getCurrentPrice()
                .multiply(portfolio.getQuantity());
        return positionValue
                .divide(totalCurrentValue, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    public Map<String, Object> getPortfolioSummary() {
        BigDecimal totalInvestment = getTotalInvestment();
        BigDecimal totalCurrentValue = getTotalCurrentValue();
        BigDecimal totalProfit = getTotalProfit();
        BigDecimal totalProfitRate = getTotalProfitRate();

        List<Portfolio> activePortfolios = getActivePortfolios();
        long activeCount = getActivePortfolioCount();
        int availableSlots = getAvailableSlots();

        List<Portfolio> lossPortfolios = portfolioRepository.findLossPortfolios(new BigDecimal("-5"));
        List<Portfolio> topProfitPortfolios = portfolioRepository.findTopProfitPortfolios();

        return Map.of(
                "totalInvestment", totalInvestment,
                "totalCurrentValue", totalCurrentValue,
                "totalProfit", totalProfit,
                "totalProfitRate", totalProfitRate,
                "activeCount", activeCount,
                "availableSlots", availableSlots,
                "maxPositions", maxPositions,
                "positionSize", positionSize,
                "lossPortfolios", lossPortfolios,
                "topProfitPortfolios", topProfitPortfolios.stream().limit(5).collect(Collectors.toList())
        );
    }

    public Map<String, Object> getTradeStatistics() {
        long buyCount = tradeRepository.countByTradeType(TradeType.BUY);
        long sellCount = tradeRepository.countByTradeType(TradeType.SELL);

        BigDecimal totalBuyAmount = tradeRepository.sumTotalAmountByTradeType(TradeType.BUY);
        BigDecimal totalSellAmount = tradeRepository.sumTotalAmountByTradeType(TradeType.SELL);

        long winningTrades = tradeRepository.countWinningSellTrades();
        double winRate = sellCount > 0 ? (double) winningTrades / sellCount * 100 : 0;

        long recentTradeCount = tradeRepository.countByTradeDateTimeAfter(LocalDateTime.now().minusDays(7));

        return Map.of(
                "totalBuyCount", buyCount,
                "totalSellCount", sellCount,
                "totalBuyAmount", totalBuyAmount,
                "totalSellAmount", totalSellAmount,
                "winRate", BigDecimal.valueOf(winRate).setScale(1, RoundingMode.HALF_UP),
                "recentTradeCount", recentTradeCount
        );
    }

    public Map<String, Object> getRiskStatus() {
        List<Portfolio> activePortfolios = getActivePortfolios();
        BigDecimal totalCurrentValue = getTotalCurrentValue();
        BigDecimal availableAmount = getAvailableInvestmentAmount();

        boolean isMaxPositions = getAvailableSlots() == 0;
        boolean isLowCash = availableAmount.compareTo(positionSize) < 0;

        List<Portfolio> highRiskPortfolios = activePortfolios.stream()
                .filter(p -> getPositionWeight(p).compareTo(new BigDecimal("20")) > 0)
                .toList();

        List<Portfolio> lossPortfolios = portfolioRepository.findLossPortfolios(new BigDecimal("-5"));

        return Map.of(
                "isMaxPositions", isMaxPositions,
                "isLowCash", isLowCash,
                "highRiskCount", highRiskPortfolios.size(),
                "lossCount", lossPortfolios.size(),
                "availableAmount", availableAmount,
                "totalCurrentValue", totalCurrentValue
        );
    }
}
