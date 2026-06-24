package com.canagent.service.analysis;

import com.canagent.MockDataFactory;
import com.canagent.domain.stock.Stock;
import com.canagent.domain.stock.StockPrice;
import com.canagent.repository.FinancialStatementRepository;
import com.canagent.repository.StockPriceRepository;
import com.canagent.service.dto.CanSlimResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CANSLIM 종합 분석 서비스 단위테스트")
class CanSlimAnalysisServiceTest {

    @Mock
    private QuarterlyEarningsAnalyzer quarterlyEarningsAnalyzer;
    @Mock
    private AnnualEarningsAnalyzer annualEarningsAnalyzer;
    @Mock
    private SupplyDemandAnalyzer supplyDemandAnalyzer;
    @Mock
    private MarketDirectionAnalyzer marketDirectionAnalyzer;

    @InjectMocks
    private CanSlimAnalysisService analysisService;

    private Stock stock;

    @BeforeEach
    void setUp() {
        stock = MockDataFactory.createSamsungStock();
    }

    @Test
    @DisplayName("모든 요소가 높은 점수를 받으면 매수 신호를 리턴한다")
    void analyze_allHighScores_returnsBuySignal() {
        when(quarterlyEarningsAnalyzer.analyze(any()))
                .thenReturn(new QuarterlyEarningsAnalyzer.CanSlimElement(
                        new BigDecimal("25"), "성장", BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN));
        when(annualEarningsAnalyzer.analyze(any()))
                .thenReturn(new AnnualEarningsAnalyzer.CanSlimElement(
                        new BigDecimal("25"), "성장", BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN));
        when(supplyDemandAnalyzer.analyze(any()))
                .thenReturn(new SupplyDemandAnalyzer.CanSlimElement(
                        new BigDecimal("20"), "증가"));
        when(marketDirectionAnalyzer.analyze(any()))
                .thenReturn(new MarketDirectionAnalyzer.CanSlimElement(
                        new BigDecimal("20"), "강세"));

        CanSlimResult result = analysisService.analyze(stock);

        assertThat(result.totalScore()).isEqualByComparingTo(new BigDecimal("90"));
        assertThat(result.isBuySignal()).isTrue();
        assertThat(result.isStrongBuy()).isTrue();
    }

    @Test
    @DisplayName("모든 요소가 낮은 점수를 받으면 매수 신호가 아니다")
    void analyze_allLowScores_noBuySignal() {
        when(quarterlyEarningsAnalyzer.analyze(any()))
                .thenReturn(new QuarterlyEarningsAnalyzer.CanSlimElement(
                        BigDecimal.ZERO, "하락"));
        when(annualEarningsAnalyzer.analyze(any()))
                .thenReturn(new AnnualEarningsAnalyzer.CanSlimElement(
                        BigDecimal.ZERO, "하락"));
        when(supplyDemandAnalyzer.analyze(any()))
                .thenReturn(new SupplyDemandAnalyzer.CanSlimElement(
                        BigDecimal.ZERO, "감소"));
        when(marketDirectionAnalyzer.analyze(any()))
                .thenReturn(new MarketDirectionAnalyzer.CanSlimElement(
                        BigDecimal.ZERO, "약세"));

        CanSlimResult result = analysisService.analyze(stock);

        assertThat(result.totalScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.isBuySignal()).isFalse();
    }

    @Test
    @DisplayName("부분적으로 높은 점수를 받으면 일반 매수 신호를 리턴한다")
    void analyze_mixedScores_returnsNormalBuySignal() {
        when(quarterlyEarningsAnalyzer.analyze(any()))
                .thenReturn(new QuarterlyEarningsAnalyzer.CanSlimElement(
                        new BigDecimal("20"), "성장"));
        when(annualEarningsAnalyzer.analyze(any()))
                .thenReturn(new AnnualEarningsAnalyzer.CanSlimElement(
                        new BigDecimal("20"), "성장"));
        when(supplyDemandAnalyzer.analyze(any()))
                .thenReturn(new SupplyDemandAnalyzer.CanSlimElement(
                        new BigDecimal("15"), "보통"));
        when(marketDirectionAnalyzer.analyze(any()))
                .thenReturn(new MarketDirectionAnalyzer.CanSlimElement(
                        new BigDecimal("15"), "보통"));

        CanSlimResult result = analysisService.analyze(stock);

        assertThat(result.totalScore()).isEqualByComparingTo(new BigDecimal("70"));
        assertThat(result.isBuySignal()).isTrue();
        assertThat(result.isStrongBuy()).isFalse();
    }
}
