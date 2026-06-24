package com.canagent.service;

import com.canagent.MockDataFactory;
import com.canagent.domain.portfolio.Portfolio;
import com.canagent.domain.stock.Stock;
import com.canagent.domain.trading.Trade;
import com.canagent.domain.trading.TradeType;
import com.canagent.repository.PortfolioRepository;
import com.canagent.repository.TradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("포트폴리오 서비스 단위테스트")
class PortfolioServiceTest {

    @Mock
    private PortfolioRepository portfolioRepository;
    @Mock
    private TradeRepository tradeRepository;

    @InjectMocks
    private PortfolioService portfolioService;

    private Stock samsung;
    private Stock naver;

    @BeforeEach
    void setUp() throws Exception {
        samsung = MockDataFactory.createSamsungStock();
        naver = MockDataFactory.createNaverStock();

        var maxPositionsField = PortfolioService.class.getDeclaredField("maxPositions");
        maxPositionsField.setAccessible(true);
        maxPositionsField.setInt(portfolioService, 10);

        var positionSizeField = PortfolioService.class.getDeclaredField("positionSize");
        positionSizeField.setAccessible(true);
        positionSizeField.set(portfolioService, new BigDecimal("1000000"));
    }

    @Test
    @DisplayName("활성 포트폴리오 조회")
    void getActivePortfolios_returnsList() {
        Portfolio p = MockDataFactory.createPortfolio(samsung, 10, new BigDecimal("70000"));
        when(portfolioRepository.findByActiveTrue()).thenReturn(List.of(p));

        List<Portfolio> result = portfolioService.getActivePortfolios();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStock().getCode()).isEqualTo("005930");
    }

    @Test
    @DisplayName("총 투자금액 조회")
    void getTotalInvestment_returnsSum() {
        Portfolio p1 = MockDataFactory.createPortfolio(samsung, 10, new BigDecimal("70000"));
        Portfolio p2 = MockDataFactory.createPortfolio(naver, 5, new BigDecimal("300000"));
        when(portfolioRepository.sumTotalBuyAmountByActiveTrue())
                .thenReturn(new BigDecimal("2200000"));

        BigDecimal result = portfolioService.getTotalInvestment();

        assertThat(result).isEqualByComparingTo(new BigDecimal("2200000"));
    }

    @Test
    @DisplayName("총 수익률 계산 - 투자금 0일 때")
    void getTotalProfitRate_zeroInvestment_returnsZero() {
        when(portfolioRepository.sumTotalBuyAmountByActiveTrue()).thenReturn(BigDecimal.ZERO);

        BigDecimal result = portfolioService.getTotalProfitRate();

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("총 수익률 계산 - 정상 계산")
    void getTotalProfitRate_positiveInvestment_calculatesCorrectly() {
        when(portfolioRepository.sumTotalBuyAmountByActiveTrue()).thenReturn(new BigDecimal("1000000"));
        when(portfolioRepository.sumTotalProfitAmountByActiveTrue()).thenReturn(new BigDecimal("100000"));

        BigDecimal result = portfolioService.getTotalProfitRate();

        assertThat(result).isEqualByComparingTo(new BigDecimal("10.0000"));
    }

    @Test
    @DisplayName("활성 포트폴리오 수 조회")
    void getActivePortfolioCount_returnsCount() {
        when(portfolioRepository.countByActiveTrue()).thenReturn(3L);

        long count = portfolioService.getActivePortfolioCount();

        assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("가능 슬롯 수 계산")
    void getAvailableSlots_calculatesCorrectly() {
        when(portfolioRepository.countByActiveTrue()).thenReturn(7L);

        int slots = portfolioService.getAvailableSlots();

        assertThat(slots).isEqualTo(3);
    }

    @Test
    @DisplayName("가능 슬롯 수 - 포트폴리오 수가 max 이상일 때")
    void getAvailableSlots_exceedsMax_returnsZero() {
        when(portfolioRepository.countByActiveTrue()).thenReturn(15L);

        int slots = portfolioService.getAvailableSlots();

        assertThat(slots).isEqualTo(0);
    }

    @Test
    @DisplayName("신규 포지션 개설 가능 여부 - 가능")
    void canOpenNewPosition_available_returnsTrue() {
        when(portfolioRepository.countByActiveTrue()).thenReturn(5L);

        assertThat(portfolioService.canOpenNewPosition()).isTrue();
    }

    @Test
    @DisplayName("신규 포지션 개설 가능 여부 - 불가")
    void canOpenNewPosition_full_returnsFalse() {
        when(portfolioRepository.countByActiveTrue()).thenReturn(10L);

        assertThat(portfolioService.canOpenNewPosition()).isFalse();
    }

    @Test
    @DisplayName("가능 투자금액 계산")
    void getAvailableInvestmentAmount_calculatesCorrectly() {
        when(portfolioRepository.sumTotalBuyAmountByActiveTrue()).thenReturn(new BigDecimal("5000000"));

        BigDecimal available = portfolioService.getAvailableInvestmentAmount();

        assertThat(available).isEqualByComparingTo(new BigDecimal("5000000"));
    }

    @Test
    @DisplayName("가능 투자금액 - 투자금이 최대일 때")
    void getAvailableInvestmentAmount_exceedsMax_returnsZero() {
        when(portfolioRepository.sumTotalBuyAmountByActiveTrue()).thenReturn(new BigDecimal("15000000"));

        BigDecimal available = portfolioService.getAvailableInvestmentAmount();

        assertThat(available).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("종목 비중 계산")
    void getPositionWeight_calculatesCorrectly() {
        Portfolio p = MockDataFactory.createPortfolio(samsung, 10, new BigDecimal("70000"));
        when(portfolioRepository.sumTotalCurrentValueByActiveTrue()).thenReturn(new BigDecimal("1000000"));

        BigDecimal weight = portfolioService.getPositionWeight(p);

        // 포지션 가치 = 70000 * 10 = 700000
        // 비중 = 700000 / 1000000 * 100 = 70%
        assertThat(weight).isEqualByComparingTo(new BigDecimal("70.0000"));
    }

    @Test
    @DisplayName("종목 비중 - 총 가치가 0일 때")
    void getPositionWeight_zeroTotalValue_returnsZero() {
        Portfolio p = MockDataFactory.createPortfolio(samsung, 10, new BigDecimal("70000"));
        when(portfolioRepository.sumTotalCurrentValueByActiveTrue()).thenReturn(BigDecimal.ZERO);

        BigDecimal weight = portfolioService.getPositionWeight(p);

        assertThat(weight).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("포트폴리오 요약 조회")
    void getPortfolioSummary_returnsAllFields() {
        Portfolio p = MockDataFactory.createPortfolio(samsung, 10, new BigDecimal("70000"));
        when(portfolioRepository.sumTotalBuyAmountByActiveTrue()).thenReturn(new BigDecimal("700000"));
        when(portfolioRepository.sumTotalCurrentValueByActiveTrue()).thenReturn(new BigDecimal("730000"));
        when(portfolioRepository.sumTotalProfitAmountByActiveTrue()).thenReturn(new BigDecimal("30000"));
        when(portfolioRepository.findByActiveTrue()).thenReturn(List.of(p));
        when(portfolioRepository.countByActiveTrue()).thenReturn(1L);
        when(portfolioRepository.findLossPortfolios(any())).thenReturn(Collections.emptyList());
        when(portfolioRepository.findTopProfitPortfolios()).thenReturn(List.of(p));

        Map<String, Object> summary = portfolioService.getPortfolioSummary();

        assertThat(summary).containsKey("totalInvestment");
        assertThat(summary).containsKey("totalCurrentValue");
        assertThat(summary).containsKey("totalProfit");
        assertThat(summary).containsKey("totalProfitRate");
        assertThat(summary).containsKey("activeCount");
        assertThat(summary).containsKey("availableSlots");
        assertThat(summary).containsKey("maxPositions");
        assertThat(summary).containsKey("positionSize");
        assertThat(summary).containsKey("lossPortfolios");
        assertThat(summary).containsKey("topProfitPortfolios");
        assertThat(summary.get("activeCount")).isEqualTo(1L);
        assertThat(summary.get("availableSlots")).isEqualTo(9);
    }

    @Test
    @DisplayName("매매 통계 조회")
    void getTradeStatistics_returnsAllFields() {
        Stock stock = MockDataFactory.createSamsungStock();
        Trade buyTrade = new Trade(stock, TradeType.BUY, 10, new BigDecimal("70000"), "테스트 매수");
        Trade sellTrade = new Trade(stock, TradeType.SELL, 10, new BigDecimal("80000"), "테스트 매도");
        sellTrade.setProfitRate(new BigDecimal("14.29"));

        when(tradeRepository.findAll()).thenReturn(List.of(buyTrade, sellTrade));

        Map<String, Object> stats = portfolioService.getTradeStatistics();

        assertThat(stats.get("totalBuyCount")).isEqualTo(1L);
        assertThat(stats.get("totalSellCount")).isEqualTo(1L);
        assertThat((BigDecimal) stats.get("winRate")).isEqualByComparingTo(new BigDecimal("100.0"));
        assertThat(stats).containsKey("totalBuyAmount");
        assertThat(stats).containsKey("totalSellAmount");
        assertThat(stats).containsKey("recentTradeCount");
    }

    @Test
    @DisplayName("매매 통계 - 매도 없을 때 승률 0")
    void getTradeStatistics_noSells_winRateZero() {
        Stock stock = MockDataFactory.createSamsungStock();
        Trade buyTrade = new Trade(stock, TradeType.BUY, 10, new BigDecimal("70000"), "테스트 매수");

        when(tradeRepository.findAll()).thenReturn(List.of(buyTrade));

        Map<String, Object> stats = portfolioService.getTradeStatistics();

        assertThat(stats.get("totalBuyCount")).isEqualTo(1L);
        assertThat(stats.get("totalSellCount")).isEqualTo(0L);
        assertThat((BigDecimal) stats.get("winRate")).isEqualByComparingTo(new BigDecimal("0.0"));
    }

    @Test
    @DisplayName("리스크 상태 조회 - 정상")
    void getRiskStatus_normalState() {
        Portfolio p = MockDataFactory.createPortfolio(samsung, 10, new BigDecimal("70000"));
        when(portfolioRepository.findByActiveTrue()).thenReturn(List.of(p));
        when(portfolioRepository.sumTotalCurrentValueByActiveTrue()).thenReturn(new BigDecimal("700000"));
        when(portfolioRepository.sumTotalBuyAmountByActiveTrue()).thenReturn(new BigDecimal("700000"));
        when(portfolioRepository.countByActiveTrue()).thenReturn(1L);
        when(portfolioRepository.findLossPortfolios(any())).thenReturn(Collections.emptyList());

        Map<String, Object> risk = portfolioService.getRiskStatus();

        assertThat(risk.get("isMaxPositions")).isEqualTo(false);
        assertThat(risk.get("isLowCash")).isEqualTo(false);
        assertThat(risk.get("lossCount")).isEqualTo(0);
        assertThat(risk).containsKey("availableAmount");
        assertThat(risk).containsKey("totalCurrentValue");
    }

    @Test
    @DisplayName("리스크 상태 - 최대 포지션 도달")
    void getRiskStatus_maxPositions() {
        when(portfolioRepository.findByActiveTrue()).thenReturn(Collections.emptyList());
        when(portfolioRepository.sumTotalCurrentValueByActiveTrue()).thenReturn(BigDecimal.ZERO);
        when(portfolioRepository.sumTotalBuyAmountByActiveTrue()).thenReturn(new BigDecimal("10000000"));
        when(portfolioRepository.countByActiveTrue()).thenReturn(10L);
        when(portfolioRepository.findLossPortfolios(any())).thenReturn(Collections.emptyList());

        Map<String, Object> risk = portfolioService.getRiskStatus();

        assertThat(risk.get("isMaxPositions")).isEqualTo(true);
    }

    @Test
    @DisplayName("리스크 상태 - 낮은 현금")
    void getRiskStatus_lowCash() {
        when(portfolioRepository.findByActiveTrue()).thenReturn(Collections.emptyList());
        when(portfolioRepository.sumTotalCurrentValueByActiveTrue()).thenReturn(BigDecimal.ZERO);
        when(portfolioRepository.sumTotalBuyAmountByActiveTrue()).thenReturn(new BigDecimal("9500000"));
        when(portfolioRepository.countByActiveTrue()).thenReturn(9L);
        when(portfolioRepository.findLossPortfolios(any())).thenReturn(Collections.emptyList());

        Map<String, Object> risk = portfolioService.getRiskStatus();

        assertThat(risk.get("isLowCash")).isEqualTo(true);
    }
}
