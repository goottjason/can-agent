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

        if (previousYear == null) {
            return new CanSlimElement(BigDecimal.ZERO, "이전 연도 데이터 부족");
        }

        BigDecimal currentVal = null;
        BigDecimal previousVal = null;
        String metric = "순이익";

        if (currentYear.getEps() != null && previousYear.getEps() != null
                && currentYear.getEps().compareTo(BigDecimal.ZERO) != 0
                && previousYear.getEps().compareTo(BigDecimal.ZERO) != 0) {
            currentVal = currentYear.getEps();
            previousVal = previousYear.getEps();
            metric = "EPS";
        } else if (currentYear.getNetIncome() != null && previousYear.getNetIncome() != null
                && previousYear.getNetIncome().compareTo(BigDecimal.ZERO) != 0) {
            currentVal = currentYear.getNetIncome();
            previousVal = previousYear.getNetIncome();
        }

        if (currentVal == null || previousVal == null || previousVal.compareTo(BigDecimal.ZERO) == 0) {
            return new CanSlimElement(BigDecimal.ZERO, "연간 수익 데이터 부족");
        }

        BigDecimal growthRate = currentVal.subtract(previousVal)
                .divide(previousVal.abs(), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        BigDecimal score = calculateAnnualScore(growthRate);
        String reason = String.format("연간 %s 성장률: %.1f%%", metric, growthRate);

        return new CanSlimElement(score, reason, currentVal, previousVal, growthRate);
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
            return new BigDecimal("20");
        } else if (growthRate.compareTo(new BigDecimal("10")) >= 0) {
            return new BigDecimal("16");
        } else if (growthRate.compareTo(new BigDecimal("5")) >= 0) {
            return new BigDecimal("12");
        } else if (growthRate.compareTo(BigDecimal.ZERO) > 0) {
            return new BigDecimal("8");
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
