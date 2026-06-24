package com.canagent.service.analysis;

import com.canagent.MockDataFactory;
import com.canagent.domain.stock.Stock;
import com.canagent.service.dto.CanSlimResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
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
    @Mock
    private IndustryLeaderAnalyzer industryLeaderAnalyzer;
    @Mock
    private InstitutionalInvestorAnalyzer institutionalInvestorAnalyzer;

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
                        new BigDecimal("20"), "성장", BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN));
        when(annualEarningsAnalyzer.analyze(any()))
                .thenReturn(new AnnualEarningsAnalyzer.CanSlimElement(
                        new BigDecimal("20"), "성장", BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN));
        when(supplyDemandAnalyzer.analyze(any()))
                .thenReturn(new SupplyDemandAnalyzer.CanSlimElement(
                        new BigDecimal("15"), "증가"));
        when(marketDirectionAnalyzer.analyze(any()))
                .thenReturn(new MarketDirectionAnalyzer.CanSlimElement(
                        new BigDecimal("15"), "강세"));
        when(industryLeaderAnalyzer.analyze(any()))
                .thenReturn(new IndustryLeaderAnalyzer.CanSlimElement(
                        new BigDecimal("15"), "선도주", BigDecimal.TEN, new BigDecimal("20"), 1));
        when(institutionalInvestorAnalyzer.analyze(any()))
                .thenReturn(new InstitutionalInvestorAnalyzer.CanSlimElement(
                        new BigDecimal("15"), "기관 매수"));

        CanSlimResult result = analysisService.analyze(stock);

        assertThat(result.totalScore()).isEqualByComparingTo(new BigDecimal("100"));
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
        when(industryLeaderAnalyzer.analyze(any()))
                .thenReturn(new IndustryLeaderAnalyzer.CanSlimElement(
                        BigDecimal.ZERO, "후발주"));
        when(institutionalInvestorAnalyzer.analyze(any()))
                .thenReturn(new InstitutionalInvestorAnalyzer.CanSlimElement(
                        BigDecimal.ZERO, "기관 매도"));

        CanSlimResult result = analysisService.analyze(stock);

        assertThat(result.totalScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.isBuySignal()).isFalse();
    }

    @Test
    @DisplayName("부분적으로 높은 점수를 받으면 일반 매수 신호를 리턴한다")
    void analyze_mixedScores_returnsNormalBuySignal() {
        when(quarterlyEarningsAnalyzer.analyze(any()))
                .thenReturn(new QuarterlyEarningsAnalyzer.CanSlimElement(
                        new BigDecimal("16"), "성장"));
        when(annualEarningsAnalyzer.analyze(any()))
                .thenReturn(new AnnualEarningsAnalyzer.CanSlimElement(
                        new BigDecimal("16"), "성장"));
        when(supplyDemandAnalyzer.analyze(any()))
                .thenReturn(new SupplyDemandAnalyzer.CanSlimElement(
                        new BigDecimal("12"), "보통"));
        when(marketDirectionAnalyzer.analyze(any()))
                .thenReturn(new MarketDirectionAnalyzer.CanSlimElement(
                        new BigDecimal("10"), "보통"));
        when(industryLeaderAnalyzer.analyze(any()))
                .thenReturn(new IndustryLeaderAnalyzer.CanSlimElement(
                        new BigDecimal("9"), "중간"));
        when(institutionalInvestorAnalyzer.analyze(any()))
                .thenReturn(new InstitutionalInvestorAnalyzer.CanSlimElement(
                        new BigDecimal("9"), "보통"));

        CanSlimResult result = analysisService.analyze(stock);

        assertThat(result.totalScore()).isEqualByComparingTo(new BigDecimal("72"));
        assertThat(result.isBuySignal()).isTrue();
        assertThat(result.isStrongBuy()).isFalse();
    }

    @Test
    @DisplayName("분석 결과에 업종 선도주 정보가 포함된다")
    void analyze_includesIndustryLeaderInfo() {
        when(quarterlyEarningsAnalyzer.analyze(any()))
                .thenReturn(new QuarterlyEarningsAnalyzer.CanSlimElement(BigDecimal.ZERO, "하락"));
        when(annualEarningsAnalyzer.analyze(any()))
                .thenReturn(new AnnualEarningsAnalyzer.CanSlimElement(BigDecimal.ZERO, "하락"));
        when(supplyDemandAnalyzer.analyze(any()))
                .thenReturn(new SupplyDemandAnalyzer.CanSlimElement(BigDecimal.ZERO, "감소"));
        when(marketDirectionAnalyzer.analyze(any()))
                .thenReturn(new MarketDirectionAnalyzer.CanSlimElement(BigDecimal.ZERO, "약세"));
        when(industryLeaderAnalyzer.analyze(any()))
                .thenReturn(new IndustryLeaderAnalyzer.CanSlimElement(
                        new BigDecimal("15"), "13주 상대강도: 10.0%, ROE: 25.0%, 업종 순위: 1/5",
                        new BigDecimal("10"), new BigDecimal("25"), 1));
        when(institutionalInvestorAnalyzer.analyze(any()))
                .thenReturn(new InstitutionalInvestorAnalyzer.CanSlimElement(BigDecimal.ZERO, "기관 매도"));

        CanSlimResult result = analysisService.analyze(stock);

        assertThat(result.marketPosition()).isNotNull();
        assertThat(result.marketPosition().score()).isEqualByComparingTo(new BigDecimal("15"));
        assertThat(result.marketPosition().isIndustryLeader()).isTrue();
        assertThat(result.details()).containsKey("업종 선도주");
        assertThat(result.details()).containsKey("기관 투자자");
    }
}
