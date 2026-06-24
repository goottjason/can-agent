package com.canagent.service.analysis;

import com.canagent.domain.stock.Stock;
import com.canagent.service.dto.CanSlimResult;
import com.canagent.service.dto.CanSlimResult.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

@Service
public class CanSlimAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CanSlimAnalysisService.class);

    private final QuarterlyEarningsAnalyzer quarterlyEarningsAnalyzer;
    private final AnnualEarningsAnalyzer annualEarningsAnalyzer;
    private final SupplyDemandAnalyzer supplyDemandAnalyzer;
    private final MarketDirectionAnalyzer marketDirectionAnalyzer;

    public CanSlimAnalysisService(
            QuarterlyEarningsAnalyzer quarterlyEarningsAnalyzer,
            AnnualEarningsAnalyzer annualEarningsAnalyzer,
            SupplyDemandAnalyzer supplyDemandAnalyzer,
            MarketDirectionAnalyzer marketDirectionAnalyzer) {
        this.quarterlyEarningsAnalyzer = quarterlyEarningsAnalyzer;
        this.annualEarningsAnalyzer = annualEarningsAnalyzer;
        this.supplyDemandAnalyzer = supplyDemandAnalyzer;
        this.marketDirectionAnalyzer = marketDirectionAnalyzer;
    }

    public CanSlimResult analyze(Stock stock) {
        log.info("CANSLIM 분석 시작: {} ({})", stock.getName(), stock.getCode());

        var quarterly = quarterlyEarningsAnalyzer.analyze(stock);
        var annual = annualEarningsAnalyzer.analyze(stock);
        var supply = supplyDemandAnalyzer.analyze(stock);
        var market = marketDirectionAnalyzer.analyze(stock);

        BigDecimal totalScore = quarterly.score()
                .add(annual.score())
                .add(supply.score())
                .add(market.score());

        Map<String, String> details = new HashMap<>();
        details.put("분기 실적", quarterly.reason());
        details.put("연간 실적", annual.reason());
        details.put("수급", supply.reason());
        details.put("시장 방향", market.reason());

        QuarterlyEarnings quarterlyResult = new QuarterlyEarnings(
                quarterly.score(),
                quarterly.currentEps(),
                quarterly.previousEps(),
                quarterly.growthRate(),
                quarterly.reason()
        );

        AnnualEarnings annualResult = new AnnualEarnings(
                annual.score(),
                annual.currentEps(),
                annual.previousEps(),
                annual.growthRate(),
                annual.reason()
        );

        SupplyDemand supplyResult = new SupplyDemand(
                supply.score(),
                supply.avgVolume(),
                supply.currentVolume(),
                supply.volumeRatio(),
                supply.reason()
        );

        MarketDirection marketResult = new MarketDirection(
                market.score(),
                determineMarketTrend(market.score()),
                market.reason()
        );

        MarketPosition positionResult = new MarketPosition(
                BigDecimal.ZERO,
                false,
                "N/A",
                "업종 선도주 분석 미구현"
        );

        CanSlimResult result = new CanSlimResult(
                stock.getCode(),
                stock.getName(),
                totalScore,
                quarterlyResult,
                annualResult,
                positionResult,
                supplyResult,
                marketResult,
                details
        );

        log.info("CANSLIM 분석 완료: {} ({}) - 총점: {}",
                stock.getName(), stock.getCode(), totalScore);

        return result;
    }

    private String determineMarketTrend(BigDecimal score) {
        if (score.compareTo(new BigDecimal("20")) >= 0) {
            return "강세";
        } else if (score.compareTo(new BigDecimal("10")) >= 0) {
            return "보통";
        } else {
            return "약세";
        }
    }
}
