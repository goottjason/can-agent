package com.canagent.service.analysis;

import com.canagent.domain.stock.FinancialStatement;
import com.canagent.domain.stock.Stock;
import com.canagent.domain.stock.StockPrice;
import com.canagent.repository.FinancialStatementRepository;
import com.canagent.repository.StockPriceRepository;
import com.canagent.repository.StockRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class IndustryLeaderAnalyzer {

    private final StockRepository stockRepository;
    private final StockPriceRepository stockPriceRepository;
    private final FinancialStatementRepository financialStatementRepository;

    public IndustryLeaderAnalyzer(
            StockRepository stockRepository,
            StockPriceRepository stockPriceRepository,
            FinancialStatementRepository financialStatementRepository) {
        this.stockRepository = stockRepository;
        this.stockPriceRepository = stockPriceRepository;
        this.financialStatementRepository = financialStatementRepository;
    }

    public CanSlimElement analyze(Stock stock) {
        if (stock.getSector() == null || stock.getSector().isBlank()) {
            return new CanSlimElement(BigDecimal.ZERO, "업종 정보 없음");
        }

        List<Stock> sectorStocks = stockRepository.findBySectorAndActiveTrue(stock.getSector());
        if (sectorStocks.size() < 2) {
            return new CanSlimElement(BigDecimal.ZERO, "동일 업종 종목 부족");
        }

        BigDecimal relativeStrength = calculateRelativeStrength(stock);
        BigDecimal roe = getLatestRoe(stock);
        int sectorRank = calculateSectorRank(stock, sectorStocks);

        BigDecimal score = calculateLeaderScore(relativeStrength, roe, sectorRank, sectorStocks.size());
        String reason = buildReason(relativeStrength, roe, sectorRank, sectorStocks.size());

        return new CanSlimElement(score, reason, relativeStrength, roe, sectorRank);
    }

    private BigDecimal calculateRelativeStrength(Stock stock) {
        List<StockPrice> prices = stockPriceRepository
                .findByStockIdAndDateBetweenOrderByDateAsc(
                        stock.getId(),
                        LocalDate.now().minusWeeks(13),
                        LocalDate.now()
                );

        if (prices.size() < 10) {
            return BigDecimal.ZERO;
        }

        BigDecimal firstClose = prices.get(0).getClose();
        BigDecimal lastClose = prices.get(prices.size() - 1).getClose();

        if (firstClose == null || lastClose == null || firstClose.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        return lastClose.subtract(firstClose)
                .divide(firstClose, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    private BigDecimal getLatestRoe(Stock stock) {
        List<FinancialStatement> statements = financialStatementRepository
                .findByStockIdOrderByFiscalYearDescFiscalQuarterDesc(stock.getId());

        return statements.stream()
                .map(FinancialStatement::getRoe)
                .filter(r -> r != null)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    private int calculateSectorRank(Stock targetStock, List<Stock> sectorStocks) {
        List<StockPerformance> performances = new ArrayList<>();

        for (Stock s : sectorStocks) {
            BigDecimal rs = calculateRelativeStrength(s);
            performances.add(new StockPerformance(s.getId(), rs));
        }

        performances.sort(Comparator.comparing(StockPerformance::relativeStrength).reversed());

        for (int i = 0; i < performances.size(); i++) {
            if (performances.get(i).stockId().equals(targetStock.getId())) {
                return i + 1;
            }
        }

        return sectorStocks.size();
    }

    private BigDecimal calculateLeaderScore(BigDecimal relativeStrength, BigDecimal roe,
                                            int sectorRank, int totalStocks) {
        BigDecimal score = BigDecimal.ZERO;

        double rankPercentile = (double) sectorRank / totalStocks;

        if (rankPercentile <= 0.10 && roe.compareTo(new BigDecimal("20")) >= 0) {
            score = new BigDecimal("15");
        } else if (rankPercentile <= 0.25 && roe.compareTo(new BigDecimal("15")) >= 0) {
            score = new BigDecimal("12");
        } else if (rankPercentile <= 0.50) {
            score = new BigDecimal("9");
        } else if (relativeStrength.compareTo(BigDecimal.ZERO) > 0) {
            score = new BigDecimal("6");
        } else {
            score = new BigDecimal("3");
        }

        return score;
    }

    private String buildReason(BigDecimal relativeStrength, BigDecimal roe,
                               int sectorRank, int totalStocks) {
        return String.format("13주 상대강도: %.1f%%, ROE: %.1f%%, 업종 순위: %d/%d",
                relativeStrength, roe, sectorRank, totalStocks);
    }

    private record StockPerformance(Long stockId, BigDecimal relativeStrength) {}

    public record CanSlimElement(
            BigDecimal score,
            String reason,
            BigDecimal relativeStrength,
            BigDecimal roe,
            Integer sectorRank
    ) {
        public CanSlimElement(BigDecimal score, String reason) {
            this(score, reason, null, null, null);
        }
    }
}
