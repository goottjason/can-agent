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
public class InstitutionalInvestorAnalyzer {

    private final StockPriceRepository stockPriceRepository;

    public InstitutionalInvestorAnalyzer(StockPriceRepository stockPriceRepository) {
        this.stockPriceRepository = stockPriceRepository;
    }

    public CanSlimElement analyze(Stock stock) {
        List<StockPrice> recentPrices = stockPriceRepository
                .findByStockIdAndDateBetweenOrderByDateAsc(
                        stock.getId(),
                        LocalDate.now().minusWeeks(4),
                        LocalDate.now()
                );

        if (recentPrices.size() < 10) {
            return new CanSlimElement(BigDecimal.ZERO, "4주간 데이터 부족");
        }

        List<StockPrice> allPrices = stockPriceRepository
                .findByStockIdOrderByDateDesc(stock.getId());

        if (allPrices.size() < 20) {
            return new CanSlimElement(BigDecimal.ZERO, "20일 이상 데이터 필요");
        }

        BigDecimal avgVolume20 = calculateAverageVolume(allPrices.subList(0, 20));
        BigDecimal avgVolumeRecent = calculateAverageVolume(recentPrices);

        if (avgVolume20.compareTo(BigDecimal.ZERO) == 0) {
            return new CanSlimElement(BigDecimal.ZERO, "평균 거래량이 0");
        }

        BigDecimal volumeGrowthRate = avgVolumeRecent.subtract(avgVolume20)
                .divide(avgVolume20, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        BigDecimal priceChange4w = calculatePriceChange(recentPrices);

        int highVolumeDays = countHighVolumeDays(recentPrices, avgVolume20);

        BigDecimal score = calculateInstitutionalScore(volumeGrowthRate, priceChange4w, highVolumeDays);
        String reason = buildReason(volumeGrowthRate, priceChange4w, highVolumeDays);

        return new CanSlimElement(score, reason, volumeGrowthRate, priceChange4w, highVolumeDays);
    }

    private BigDecimal calculateAverageVolume(List<StockPrice> prices) {
        long totalVolume = prices.stream()
                .filter(p -> p.getVolume() != null)
                .mapToLong(StockPrice::getVolume)
                .sum();
        return new BigDecimal(totalVolume).divide(new BigDecimal(prices.size()), 0, RoundingMode.HALF_UP);
    }

    private BigDecimal calculatePriceChange(List<StockPrice> prices) {
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

    private int countHighVolumeDays(List<StockPrice> recentPrices, BigDecimal avgVolume20) {
        BigDecimal threshold = avgVolume20.multiply(new BigDecimal("1.5"));
        int count = 0;

        for (StockPrice price : recentPrices) {
            if (price.getVolume() != null && new BigDecimal(price.getVolume()).compareTo(threshold) >= 0) {
                count++;
            }
        }

        return count;
    }

    private BigDecimal calculateInstitutionalScore(BigDecimal volumeGrowthRate,
                                                    BigDecimal priceChange4w,
                                                    int highVolumeDays) {
        boolean accumulation = volumeGrowthRate.compareTo(new BigDecimal("20")) >= 0
                && priceChange4w.compareTo(BigDecimal.ZERO) > 0;
        boolean moderateAccumulation = volumeGrowthRate.compareTo(new BigDecimal("10")) >= 0
                && priceChange4w.compareTo(BigDecimal.ZERO) > 0;
        boolean distribution = volumeGrowthRate.compareTo(new BigDecimal("20")) >= 0
                && priceChange4w.compareTo(BigDecimal.ZERO) < 0;

        if (accumulation && highVolumeDays >= 3) {
            return new BigDecimal("15");
        } else if (accumulation) {
            return new BigDecimal("12");
        } else if (moderateAccumulation) {
            return new BigDecimal("9");
        } else if (distribution) {
            return new BigDecimal("6");
        } else if (highVolumeDays >= 2) {
            return new BigDecimal("6");
        } else {
            return new BigDecimal("3");
        }
    }

    private String buildReason(BigDecimal volumeGrowthRate, BigDecimal priceChange4w, int highVolumeDays) {
        return String.format("거래량 증가율: %.1f%%, 4주 가격변화: %.1f%%, 고거래량일: %d일",
                volumeGrowthRate, priceChange4w, highVolumeDays);
    }

    public record CanSlimElement(
            BigDecimal score,
            String reason,
            BigDecimal volumeGrowthRate,
            BigDecimal priceChange4w,
            Integer highVolumeDays
    ) {
        public CanSlimElement(BigDecimal score, String reason) {
            this(score, reason, null, null, null);
        }
    }
}
