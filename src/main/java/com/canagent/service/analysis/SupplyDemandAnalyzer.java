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
public class SupplyDemandAnalyzer {

    private final StockPriceRepository stockPriceRepository;

    public SupplyDemandAnalyzer(StockPriceRepository stockPriceRepository) {
        this.stockPriceRepository = stockPriceRepository;
    }

    public CanSlimElement analyze(Stock stock) {
        List<StockPrice> recentPrices = stockPriceRepository
                .findByStockIdAndDateBetweenOrderByDateAsc(
                        stock.getId(),
                        LocalDate.now().minusWeeks(4),
                        LocalDate.now()
                );

        if (recentPrices.isEmpty()) {
            return new CanSlimElement(BigDecimal.ZERO, "가격 데이터 부족");
        }

        List<StockPrice> allPrices = stockPriceRepository
                .findByStockIdOrderByDateDesc(stock.getId());

        if (allPrices.size() < 20) {
            return new CanSlimElement(BigDecimal.ZERO, "20일 이상 데이터 필요");
        }

        BigDecimal avgVolume20 = calculateAverageVolume(allPrices.subList(0, 20));
        BigDecimal currentVolume = recentPrices.get(recentPrices.size() - 1).getVolume() != null
                ? new BigDecimal(recentPrices.get(recentPrices.size() - 1).getVolume())
                : BigDecimal.ZERO;

        if (avgVolume20.compareTo(BigDecimal.ZERO) == 0) {
            return new CanSlimElement(BigDecimal.ZERO, "평균 거래량이 0");
        }

        BigDecimal volumeRatio = currentVolume.divide(avgVolume20, 2, RoundingMode.HALF_UP);

        BigDecimal avgPriceChange = calculateAveragePriceChange(recentPrices);
        boolean volumeIncreasing = volumeRatio.compareTo(new BigDecimal("1.5")) >= 0;
        boolean priceIncreasing = avgPriceChange.compareTo(BigDecimal.ZERO) > 0;

        BigDecimal score = calculateSupplyDemandScore(volumeRatio, priceIncreasing);
        String reason = String.format("거래량 비율: %.2f, 가격 변화: %.1f%%",
                volumeRatio, avgPriceChange);

        return new CanSlimElement(score, reason, avgVolume20, currentVolume, volumeRatio);
    }

    private BigDecimal calculateAverageVolume(List<StockPrice> prices) {
        long totalVolume = prices.stream()
                .filter(p -> p.getVolume() != null)
                .mapToLong(StockPrice::getVolume)
                .sum();
        return new BigDecimal(totalVolume).divide(new BigDecimal(prices.size()), 0, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateAveragePriceChange(List<StockPrice> prices) {
        if (prices.size() < 2) return BigDecimal.ZERO;

        BigDecimal firstClose = prices.get(0).getClose();
        BigDecimal lastClose = prices.get(prices.size() - 1).getClose();

        if (firstClose == null || lastClose == null || firstClose.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        return lastClose.subtract(firstClose)
                .divide(firstClose, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    private BigDecimal calculateSupplyDemandScore(BigDecimal volumeRatio, boolean priceIncreasing) {
        if (volumeRatio.compareTo(new BigDecimal("2.0")) >= 0 && priceIncreasing) {
            return new BigDecimal("20");
        } else if (volumeRatio.compareTo(new BigDecimal("1.5")) >= 0 && priceIncreasing) {
            return new BigDecimal("15");
        } else if (volumeRatio.compareTo(new BigDecimal("1.2")) >= 0) {
            return new BigDecimal("10");
        } else {
            return new BigDecimal("5");
        }
    }

    public record CanSlimElement(
            BigDecimal score,
            String reason,
            BigDecimal avgVolume,
            BigDecimal currentVolume,
            BigDecimal volumeRatio
    ) {
        public CanSlimElement(BigDecimal score, String reason) {
            this(score, reason, null, null, null);
        }
    }
}
