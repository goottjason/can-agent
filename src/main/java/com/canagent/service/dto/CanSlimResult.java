package com.canagent.service.dto;

import java.math.BigDecimal;
import java.util.Map;

public record CanSlimResult(
        String stockCode,
        String stockName,
        BigDecimal totalScore,

        QuarterlyEarnings currentQuarterEarnings,
        AnnualEarnings annualEarnings,
        MarketPosition marketPosition,
        SupplyDemand supplyDemand,
        MarketDirection marketDirection,
        InstitutionalInvestor institutionalInvestor,

        Map<String, String> details
) {

    public record QuarterlyEarnings(
            BigDecimal score,
            BigDecimal currentEps,
            BigDecimal previousEps,
            BigDecimal growthRate,
            String reason
    ) {}

    public record AnnualEarnings(
            BigDecimal score,
            BigDecimal currentYearEps,
            BigDecimal previousYearEps,
            BigDecimal growthRate,
            String reason
    ) {}

    public record MarketPosition(
            BigDecimal score,
            boolean isIndustryLeader,
            String industryRank,
            String reason
    ) {}

    public record SupplyDemand(
            BigDecimal score,
            BigDecimal avgVolume,
            BigDecimal currentVolume,
            BigDecimal volumeRatio,
            String reason
    ) {}

    public record MarketDirection(
            BigDecimal score,
            String marketTrend,
            String reason
    ) {}

    public record InstitutionalInvestor(
            BigDecimal score,
            String reason
    ) {}

    public boolean isBuySignal() {
        return totalScore != null && totalScore.compareTo(new BigDecimal("40")) >= 0;
    }

    public boolean isStrongBuy() {
        return totalScore != null && totalScore.compareTo(new BigDecimal("60")) >= 0;
    }
}
