package com.canagent.service.analysis;

import com.canagent.domain.stock.Stock;
import com.canagent.domain.stock.StockPrice;
import com.canagent.repository.StockPriceRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

@Service
public class MarketDirectionAnalyzer {

    private final StockPriceRepository stockPriceRepository;

    public MarketDirectionAnalyzer(StockPriceRepository stockPriceRepository) {
        this.stockPriceRepository = stockPriceRepository;
    }

    public CanSlimElement analyze(Stock stock) {
        List<StockPrice> prices50 = stockPriceRepository
                .findByStockIdAndDateBetweenOrderByDateAsc(
                        stock.getId(),
                        LocalDate.now().minusWeeks(12),
                        LocalDate.now()
                );

        if (prices50.size() < 50) {
            return new CanSlimElement(BigDecimal.ZERO, "50일 이상 데이터 필요");
        }

        List<StockPrice> prices200 = stockPriceRepository
                .findByStockIdAndDateBetweenOrderByDateAsc(
                        stock.getId(),
                        LocalDate.now().minusWeeks(45),
                        LocalDate.now()
                );

        BigDecimal ma50 = calculateMA(prices50, 50);
        BigDecimal ma200 = prices200.size() >= 200 ? calculateMA(prices200, 200) : null;

        BigDecimal currentPrice = prices50.get(prices50.size() - 1).getClose();

        BigDecimal score = calculateMarketScore(currentPrice, ma50, ma200);
        String reason = buildReason(currentPrice, ma50, ma200);

        return new CanSlimElement(score, reason, ma50, ma200, currentPrice);
    }

    private BigDecimal calculateMA(List<StockPrice> prices, int period) {
        List<StockPrice> recentPrices = prices.subList(
                Math.max(0, prices.size() - period), prices.size());

        BigDecimal sum = recentPrices.stream()
                .map(StockPrice::getClose)
                .filter(p -> p != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return sum.divide(new BigDecimal(recentPrices.size()), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateMarketScore(BigDecimal currentPrice, BigDecimal ma50, BigDecimal ma200) {
        BigDecimal score = BigDecimal.ZERO;

        if (currentPrice != null && ma50 != null && currentPrice.compareTo(ma50) > 0) {
            score = score.add(new BigDecimal("5"));
        }

        if (ma50 != null && ma200 != null && ma50.compareTo(ma200) > 0) {
            score = score.add(new BigDecimal("5"));
        }

        if (currentPrice != null && ma50 != null && ma200 != null) {
            if (currentPrice.compareTo(ma50) > 0 && ma50.compareTo(ma200) > 0) {
                score = score.add(new BigDecimal("5"));
            }
        }

        return score;
    }

    private String buildReason(BigDecimal currentPrice, BigDecimal ma50, BigDecimal ma200) {
        StringBuilder sb = new StringBuilder();

        if (currentPrice != null && ma50 != null) {
            if (currentPrice.compareTo(ma50) > 0) {
                sb.append("현재가 > 50일 이평선");
            } else {
                sb.append("현재가 < 50일 이평선");
            }
        }

        if (ma50 != null && ma200 != null) {
            if (ma50.compareTo(ma200) > 0) {
                sb.append(", 50일 이평선 > 200일 이평선");
            } else {
                sb.append(", 50일 이평선 < 200일 이평선");
            }
        }

        return sb.toString();
    }

    public record CanSlimElement(
            BigDecimal score,
            String reason,
            BigDecimal ma50,
            BigDecimal ma200,
            BigDecimal currentPrice
    ) {
        public CanSlimElement(BigDecimal score, String reason) {
            this(score, reason, null, null, null);
        }
    }
}
