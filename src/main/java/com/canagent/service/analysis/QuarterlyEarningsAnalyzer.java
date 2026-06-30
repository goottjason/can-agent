package com.canagent.service.analysis;

import com.canagent.domain.stock.FinancialStatement;
import com.canagent.domain.stock.Stock;
import com.canagent.repository.FinancialStatementRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

@Service
public class QuarterlyEarningsAnalyzer {

    private final FinancialStatementRepository financialStatementRepository;

    public QuarterlyEarningsAnalyzer(FinancialStatementRepository financialStatementRepository) {
        this.financialStatementRepository = financialStatementRepository;
    }

    public CanSlimElement analyze(Stock stock) {
        List<FinancialStatement> statements = financialStatementRepository
                .findByStockIdOrderByFiscalYearDescFiscalQuarterDesc(stock.getId());

        if (statements.size() < 2) {
            return new CanSlimElement(BigDecimal.ZERO, "재무제표 데이터 부족");
        }

        FinancialStatement current = statements.get(0);
        FinancialStatement previous = findSameQuarterPreviousYear(statements);

        if (previous == null) {
            return new CanSlimElement(BigDecimal.ZERO, "이전 분기 데이터 부족");
        }

        // EPS가 있으면 EPS 사용, 없으면 순이익 사용
        BigDecimal currentVal = null;
        BigDecimal previousVal = null;
        String metric = "순이익";

        if (current.getEps() != null && previous.getEps() != null
                && current.getEps().compareTo(BigDecimal.ZERO) != 0
                && previous.getEps().compareTo(BigDecimal.ZERO) != 0) {
            currentVal = current.getEps();
            previousVal = previous.getEps();
            metric = "EPS";
        } else if (current.getNetIncome() != null && previous.getNetIncome() != null
                && previous.getNetIncome().compareTo(BigDecimal.ZERO) != 0) {
            currentVal = current.getNetIncome();
            previousVal = previous.getNetIncome();
        }

        if (currentVal == null || previousVal == null || previousVal.compareTo(BigDecimal.ZERO) == 0) {
            return new CanSlimElement(BigDecimal.ZERO, "수익 데이터 부족");
        }

        BigDecimal growthRate = currentVal.subtract(previousVal)
                .divide(previousVal.abs(), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        BigDecimal score = calculateQuarterlyScore(growthRate);
        String reason = String.format("분기 %s 성장률: %.1f%%", metric, growthRate);

        return new CanSlimElement(score, reason, currentVal, previousVal, growthRate);
    }

    private FinancialStatement findSameQuarterPreviousYear(List<FinancialStatement> statements) {
        if (statements.isEmpty()) return null;

        FinancialStatement current = statements.get(0);
        int targetYear = current.getFiscalYear() - 1;
        int targetQuarter = current.getFiscalQuarter();

        return statements.stream()
                .filter(s -> s.getFiscalYear() == targetYear && s.getFiscalQuarter() == targetQuarter)
                .findFirst()
                .orElse(null);
    }

    private BigDecimal calculateQuarterlyScore(BigDecimal growthRate) {
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
