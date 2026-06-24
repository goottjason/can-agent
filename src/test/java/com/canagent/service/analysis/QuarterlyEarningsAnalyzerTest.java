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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("분기 실적 분석기 단위테스트")
class QuarterlyEarningsAnalyzerTest {

    @Mock
    private FinancialStatementRepository financialStatementRepository;

    @InjectMocks
    private QuarterlyEarningsAnalyzer analyzer;

    private Stock stock;

    @BeforeEach
    void setUp() {
        stock = MockDataFactory.createSamsungStock();
    }

    @Test
    @DisplayName("재무제표 데이터가 부족하면 0점을 리턴한다")
    void analyze_insufficientData_returnsZeroScore() {
        when(financialStatementRepository.findByStockIdOrderByFiscalYearDescFiscalQuarterDesc(any()))
                .thenReturn(Collections.emptyList());

        var result = analyzer.analyze(stock);

        assertThat(result.score()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.reason()).contains("부족");
    }

    @Test
    @DisplayName("분기별 실적이 성장하면 높은 점수를 리턴한다")
    void analyze_growthPositive_returnsHighScore() {
        FinancialStatement current = MockDataFactory.createGrowingEarnings(stock);
        FinancialStatement previous = MockDataFactory.createPreviousEarnings(stock);

        when(financialStatementRepository.findByStockIdOrderByFiscalYearDescFiscalQuarterDesc(any()))
                .thenReturn(Arrays.asList(current, previous));

        var result = analyzer.analyze(stock);

        assertThat(result.score()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(result.growthRate()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("분기별 실적이 하락하면 0점을 리턴한다")
    void analyze_decline_returnsZeroScore() {
        FinancialStatement current = MockDataFactory.createFinancialStatement(
                stock, 2024, 1, new BigDecimal("3000"), new BigDecimal("30000000000"));
        FinancialStatement previous = MockDataFactory.createFinancialStatement(
                stock, 2023, 1, new BigDecimal("4000"), new BigDecimal("40000000000"));

        when(financialStatementRepository.findByStockIdOrderByFiscalYearDescFiscalQuarterDesc(any()))
                .thenReturn(Arrays.asList(current, previous));

        var result = analyzer.analyze(stock);

        assertThat(result.score()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("이전 분기 데이터가 없으면 0점을 리턴한다")
    void analyze_noPreviousData_returnsZeroScore() {
        FinancialStatement current = MockDataFactory.createGrowingEarnings(stock);

        when(financialStatementRepository.findByStockIdOrderByFiscalYearDescFiscalQuarterDesc(any()))
                .thenReturn(List.of(current));

        var result = analyzer.analyze(stock);

        assertThat(result.score()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
