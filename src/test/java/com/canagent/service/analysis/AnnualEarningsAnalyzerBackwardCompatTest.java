package com.canagent.service.analysis;

import com.canagent.MockDataFactory;
import com.canagent.domain.stock.FinancialStatement;
import com.canagent.domain.stock.FiscalPeriodType;
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
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 검증 (c): AnnualEarningsAnalyzer 하위호환 — KR(Q4=연간) 케이스와 EDGAR(periodType=ANNUAL) 케이스가
 * 둘 다 연간 성장으로 인식되어야 한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("연간 분석기 하위호환 테스트 (검증 c)")
class AnnualEarningsAnalyzerBackwardCompatTest {

    @Mock
    private FinancialStatementRepository financialStatementRepository;

    @InjectMocks
    private AnnualEarningsAnalyzer analyzer;

    private Stock stock;

    @BeforeEach
    void setUp() {
        stock = MockDataFactory.createSamsungStock();
    }

    private FinancialStatement annual(int year, FiscalPeriodType type, Integer quarter,
                                      BigDecimal eps, BigDecimal netIncome) {
        FinancialStatement fs = new FinancialStatement(stock, year, quarter, LocalDate.of(year, 12, 31));
        fs.setPeriodType(type);
        fs.updateFinancials(new BigDecimal("1000"), new BigDecimal("100"),
                netIncome, eps, new BigDecimal("20"), new BigDecimal("50"));
        return fs;
    }

    @Test
    @DisplayName("KR 기존 데이터(periodType=null, Q4=연간)를 연간으로 인식")
    void kr_q4_isAnnual() {
        FinancialStatement current = annual(2024, null, 4, new BigDecimal("20000"), new BigDecimal("200"));
        FinancialStatement previous = annual(2023, null, 4, new BigDecimal("15000"), new BigDecimal("150"));

        when(financialStatementRepository.findByStockIdOrderByFiscalYearDescFiscalQuarterDesc(any()))
                .thenReturn(List.of(current, previous));

        var result = analyzer.analyze(stock);

        // 20000 vs 15000 → +33% → 만점 20
        assertThat(result.score()).isEqualByComparingTo(new BigDecimal("20"));
    }

    @Test
    @DisplayName("EDGAR 데이터(periodType=ANNUAL, fiscalQuarter=null)를 연간으로 인식")
    void edgar_annual_isAnnual() {
        FinancialStatement current = annual(2023, FiscalPeriodType.ANNUAL, null,
                new BigDecimal("2.00"), new BigDecimal("200"));
        FinancialStatement previous = annual(2022, FiscalPeriodType.ANNUAL, null,
                new BigDecimal("1.50"), new BigDecimal("150"));

        when(financialStatementRepository.findByStockIdOrderByFiscalYearDescFiscalQuarterDesc(any()))
                .thenReturn(List.of(current, previous));

        var result = analyzer.analyze(stock);

        // 2.00 vs 1.50 EPS → +33% → 만점 20 (fiscalQuarter=null 언박싱 NPE 없이 동작)
        assertThat(result.score()).isEqualByComparingTo(new BigDecimal("20"));
    }

    @Test
    @DisplayName("EDGAR 분기행이 섞여 있어도 연간행만으로 판정(분기 최신행에 오분류 없음)")
    void edgar_mixedQuarterAndAnnual() {
        // 최신행이 2024 Q3 분기(periodType=QUARTER)이고 연간은 2023/2022
        FinancialStatement q3 = new FinancialStatement(stock, 2024, 3, LocalDate.of(2024, 9, 30));
        q3.setPeriodType(FiscalPeriodType.QUARTER);
        q3.updateFinancials(new BigDecimal("300"), new BigDecimal("30"),
                new BigDecimal("60"), new BigDecimal("0.6"), null, new BigDecimal("50"));

        FinancialStatement fy2023 = annual(2023, FiscalPeriodType.ANNUAL, null,
                new BigDecimal("2.00"), new BigDecimal("200"));
        FinancialStatement fy2022 = annual(2022, FiscalPeriodType.ANNUAL, null,
                new BigDecimal("1.50"), new BigDecimal("150"));

        when(financialStatementRepository.findByStockIdOrderByFiscalYearDescFiscalQuarterDesc(any()))
                .thenReturn(List.of(q3, fy2023, fy2022));

        var result = analyzer.analyze(stock);

        // 분기행 무시하고 FY2023 vs FY2022 → 만점 20
        assertThat(result.score()).isEqualByComparingTo(new BigDecimal("20"));
    }
}
