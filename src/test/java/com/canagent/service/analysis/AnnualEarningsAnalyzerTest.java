package com.canagent.service.analysis;

import com.canagent.MockDataFactory;
import com.canagent.domain.stock.FinancialStatement;
import com.canagent.domain.stock.Stock;
import com.canagent.repository.FinancialStatementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("연간 실적 분석기 단위테스트")
class AnnualEarningsAnalyzerTest {

    @Mock
    private FinancialStatementRepository financialStatementRepository;

    @InjectMocks
    private AnnualEarningsAnalyzer analyzer;

    private Stock stock;

    @BeforeEach
    void setUp() {
        stock = MockDataFactory.createSamsungStock();
    }

    @Test
    @DisplayName("연간 실적이 성장하면 높은 점수를 리턴한다")
    void analyze_annualGrowth_returnsHighScore() {
        FinancialStatement current = MockDataFactory.createFinancialStatement(
                stock, 2024, 4, new BigDecimal("20000"), new BigDecimal("200000000000"));
        FinancialStatement previous = MockDataFactory.createFinancialStatement(
                stock, 2023, 4, new BigDecimal("15000"), new BigDecimal("150000000000"));

        when(financialStatementRepository.findByStockIdOrderByFiscalYearDescFiscalQuarterDesc(any()))
                .thenReturn(Arrays.asList(current, previous));

        var result = analyzer.analyze(stock);

        assertThat(result.score()).isEqualByComparingTo(new BigDecimal("20"));
    }

    @Test
    @DisplayName("데이터가 없으면 0점을 리턴한다")
    void analyze_noData_returnsZeroScore() {
        when(financialStatementRepository.findByStockIdOrderByFiscalYearDescFiscalQuarterDesc(any()))
                .thenReturn(Collections.emptyList());

        var result = analyzer.analyze(stock);

        assertThat(result.score()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
