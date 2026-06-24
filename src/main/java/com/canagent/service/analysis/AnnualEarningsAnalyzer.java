package com.canagent.service.analysis;

import com.canagent.domain.stock.FinancialStatement;
import com.canagent.domain.stock.Stock;
import com.canagent.repository.FinancialStatementRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class AnnualEarningsAnalyzer {

    private final FinancialStatementRepository financialStatementRepository;

    public AnnualEarningsAnalyzer(FinancialStatementRepository financialStatementRepository) {
        this.financialStatementRepository = financialStatementRepository;
    }

    public CanSlimElement analyze(Stock stock) {
        List<FinancialStatement> statements = financialStatementRepository
                .findByStockIdOrderByFiscalYearDescFiscalQuarterDesc(stock.getId());

        if (statements.isEmpty()) {
            return new CanSlimElement(BigDecimal.ZERO, "재무제표 데이터 부족");
        }

        FinancialStatement currentYear = statements.get(0);

        FinancialStatement previousYear = findPreviousYearFullData(statements, currentYear.getFiscalYear());

        if (previousYear == null || currentYear.getEps() == null || previousYear.getEps() == null) {
            return new CanSlimElement(BigDecimal.ZERO, "연간 EPS 데이터 부족");
        }

        BigDecimal currentEps = currentYear.getEps();
        BigDecimal previousEps = previousYear.getEps();

        if (previousEps.compareTo(BigDecimal.ZERO) == 0) {
            return new CanSlimElement(BigDecimal.ZERO, "이전 연도 EPS가 0");
        }

        BigDecimal growthRate = currentEps.subtract(previousEps)
                .divide(previousEps.abs(), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        BigDecimal score = calculateAnnualScore(growthRate);
        String reason = String.format("연간 EPS 성장률: %.1f%%", growthRate);

        return new CanSlimElement(score, reason, currentEps, previousEps, growthRate);
    }

    private FinancialStatement findPreviousYearFullData(List<FinancialStatement> statements, int currentYear) {
        int targetYear = currentYear - 1;

        return statements.stream()
                .filter(s -> s.getFiscalYear() == targetYear && s.getFiscalQuarter() == 4)
                .findFirst()
                .orElse(null);
    }

    private BigDecimal calculateAnnualScore(BigDecimal growthRate) {
        if (growthRate.compareTo(new BigDecimal("25")) >= 0) {
            return new BigDecimal("25");
        } else if (growthRate.compareTo(new BigDecimal("10")) >= 0) {
            return new BigDecimal("20");
        } else if (growthRate.compareTo(new BigDecimal("5")) >= 0) {
            return new BigDecimal("15");
        } else if (growthRate.compareTo(BigDecimal.ZERO) > 0) {
            return new BigDecimal("10");
        } else {
            return BigDecimal.ZERO;
        }
    }

    public record CanSlimElement(
            BigDecimal score,
            String reason,
            BigDecimal currentEps,
            BigDecimal previousEps,
            BigDecimal growthRate
    ) {
        public CanSlimElement(BigDecimal score, String reason) {
            this(score, reason, null, null, null);
        }
    }
}
