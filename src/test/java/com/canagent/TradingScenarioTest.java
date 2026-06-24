package com.canagent;

import com.canagent.domain.portfolio.Portfolio;
import com.canagent.domain.stock.Stock;
import com.canagent.domain.stock.StockPrice;
import com.canagent.domain.trading.Trade;
import com.canagent.domain.trading.TradeType;
import com.canagent.repository.*;
import com.canagent.service.TradingStrategyService;
import com.canagent.service.TradingStrategyService.TradingDecision;
import com.canagent.service.analysis.CanSlimAnalysisService;
import com.canagent.service.analysis.CupAndHandleAnalyzer;
import com.canagent.service.dto.CanSlimResult;
import com.canagent.service.dto.CupAndHandleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("매매 시나리오 테스트")
@Transactional
class TradingScenarioTest {

    @Autowired
    private TradingStrategyService tradingStrategyService;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StockPriceRepository stockPriceRepository;

    @Autowired
    private PortfolioRepository portfolioRepository;

    @Autowired
    private TradeRepository tradeRepository;

    @MockBean
    private CanSlimAnalysisService canSlimAnalysisService;

    @MockBean
    private CupAndHandleAnalyzer cupAndHandleAnalyzer;

    private Stock testStock;

    @BeforeEach
    void setUp() {
        testStock = MockDataFactory.createSamsungStock();
        stockRepository.save(testStock);
    }

    @Test
    @DisplayName("시나리오 1: 강력 매수 신호 → 매수 → 보유 → 익절 매도")
    void scenario1_strongBuy_then_hold_then_takeProfit() {
        StockPrice buyPrice = MockDataFactory.createRisingPrice(
                testStock, LocalDate.now().minusDays(1), new BigDecimal("70000"));
        stockPriceRepository.save(buyPrice);

        when(canSlimAnalysisService.analyze(any()))
                .thenReturn(createStrongBuyCanSlimResult());
        when(cupAndHandleAnalyzer.analyze(any()))
                .thenReturn(createBreakoutCupResult());

        TradingDecision buyDecision = tradingStrategyService.evaluateBuy(testStock, buyPrice.getClose());
        assertThat(buyDecision.shouldBuy()).isTrue();

        Trade buyTrade = tradingStrategyService.executeBuy(testStock, buyDecision.quantity(), buyPrice.getClose(), buyDecision.reason());
        assertThat(buyTrade.getTradeType()).isEqualTo(TradeType.BUY);

        Optional<Portfolio> portfolio = portfolioRepository.findByStockIdAndActiveTrue(testStock.getId());
        assertThat(portfolio).isPresent();
        assertThat(portfolio.get().getQuantity()).isGreaterThan(0);

        StockPrice sellPrice = MockDataFactory.createRisingPrice(
                testStock, LocalDate.now(), new BigDecimal("85000"));
        stockPriceRepository.save(sellPrice);

        when(canSlimAnalysisService.analyze(any()))
                .thenReturn(createHighScoreCanSlimResult());

        TradingDecision sellDecision = tradingStrategyService.evaluateSell(testStock, sellPrice.getClose());
        assertThat(sellDecision.shouldSell()).isTrue();
        assertThat(sellDecision.reason()).contains("익절");
    }

    @Test
    @DisplayName("시나리오 2: 매수 → 하락 → 손절 매도")
    void scenario2_buy_then_drop_then_stopLoss() {
        StockPrice buyPrice = MockDataFactory.createRisingPrice(
                testStock, LocalDate.now().minusDays(1), new BigDecimal("80000"));
        stockPriceRepository.save(buyPrice);

        when(canSlimAnalysisService.analyze(any()))
                .thenReturn(createHighScoreCanSlimResult());
        when(cupAndHandleAnalyzer.analyze(any()))
                .thenReturn(createBreakoutCupResult());

        TradingDecision buyDecision = tradingStrategyService.evaluateBuy(testStock, buyPrice.getClose());
        tradingStrategyService.executeBuy(testStock, buyDecision.quantity(), buyPrice.getClose(), buyDecision.reason());

        StockPrice dropPrice = MockDataFactory.createFallingPrice(
                testStock, LocalDate.now(), new BigDecimal("72000"));
        stockPriceRepository.save(dropPrice);

        when(canSlimAnalysisService.analyze(any()))
                .thenReturn(createLowScoreCanSlimResult());

        TradingDecision sellDecision = tradingStrategyService.evaluateSell(testStock, dropPrice.getClose());
        assertThat(sellDecision.shouldSell()).isTrue();
        assertThat(sellDecision.reason()).contains("손절");
    }

    @Test
    @DisplayName("시나리오 3: 보유 종목이 최대치에 도달하면 매수하지 않는다")
    void scenario3_maxPositionsReached_noBuy() {
        for (int i = 0; i < 10; i++) {
            Stock stock = MockDataFactory.createStock(
                    String.format("00000%d", i), "종목" + i, "KOSPI", "섹터");
            stockRepository.save(stock);

            Portfolio portfolio = MockDataFactory.createPortfolio(stock, 10, new BigDecimal("50000"));
            portfolioRepository.save(portfolio);
        }

        when(canSlimAnalysisService.analyze(any()))
                .thenReturn(createStrongBuyCanSlimResult());
        when(cupAndHandleAnalyzer.analyze(any()))
                .thenReturn(createBreakoutCupResult());

        StockPrice price = MockDataFactory.createRisingPrice(
                testStock, LocalDate.now(), new BigDecimal("75000"));
        stockPriceRepository.save(price);

        TradingDecision decision = tradingStrategyService.evaluateBuy(testStock, price.getClose());
        assertThat(decision.shouldBuy()).isFalse();
        assertThat(decision.reason()).contains("최대 보유");
    }

    @Test
    @DisplayName("시나리오 4: CANSLIM 점수가 낮아지면 매도")
    void scenario4_canSlimScoreDrops_sell() {
        StockPrice buyPrice = MockDataFactory.createRisingPrice(
                testStock, LocalDate.now().minusDays(1), new BigDecimal("75000"));
        stockPriceRepository.save(buyPrice);

        when(canSlimAnalysisService.analyze(any()))
                .thenReturn(createHighScoreCanSlimResult());
        when(cupAndHandleAnalyzer.analyze(any()))
                .thenReturn(createBreakoutCupResult());

        TradingDecision buyDecision = tradingStrategyService.evaluateBuy(testStock, buyPrice.getClose());
        tradingStrategyService.executeBuy(testStock, buyDecision.quantity(), buyPrice.getClose(), buyDecision.reason());

        StockPrice currentPrice = MockDataFactory.createRisingPrice(
                testStock, LocalDate.now(), new BigDecimal("76000"));
        stockPriceRepository.save(currentPrice);

        when(canSlimAnalysisService.analyze(any()))
                .thenReturn(createLowScoreCanSlimResult());

        TradingDecision sellDecision = tradingStrategyService.evaluateSell(testStock, currentPrice.getClose());
        assertThat(sellDecision.shouldSell()).isTrue();
        assertThat(sellDecision.reason()).contains("CANSLIM");
    }

    private CanSlimResult createStrongBuyCanSlimResult() {
        return new CanSlimResult(
                testStock.getCode(), testStock.getName(), new BigDecimal("90"),
                new CanSlimResult.QuarterlyEarnings(new BigDecimal("25"), BigDecimal.TEN, BigDecimal.ONE, new BigDecimal("25"), "성장"),
                new CanSlimResult.AnnualEarnings(new BigDecimal("25"), BigDecimal.TEN, BigDecimal.ONE, new BigDecimal("25"), "성장"),
                new CanSlimResult.MarketPosition(new BigDecimal("20"), true, "1위", "선도주"),
                new CanSlimResult.SupplyDemand(new BigDecimal("20"), BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("2"), "증가"),
                new CanSlimResult.MarketDirection(new BigDecimal("20"), "강세", "강세"),
                java.util.Map.of()
        );
    }

    private CanSlimResult createHighScoreCanSlimResult() {
        return new CanSlimResult(
                testStock.getCode(), testStock.getName(), new BigDecimal("75"),
                new CanSlimResult.QuarterlyEarnings(new BigDecimal("20"), BigDecimal.TEN, BigDecimal.ONE, new BigDecimal("20"), "성장"),
                new CanSlimResult.AnnualEarnings(new BigDecimal("20"), BigDecimal.TEN, BigDecimal.ONE, new BigDecimal("20"), "성장"),
                new CanSlimResult.MarketPosition(new BigDecimal("15"), true, "2위", "선도주"),
                new CanSlimResult.SupplyDemand(new BigDecimal("10"), BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("1"), "보통"),
                new CanSlimResult.MarketDirection(new BigDecimal("10"), "보통", "보통"),
                java.util.Map.of()
        );
    }

    private CanSlimResult createLowScoreCanSlimResult() {
        return new CanSlimResult(
                testStock.getCode(), testStock.getName(), BigDecimal.TEN,
                new CanSlimResult.QuarterlyEarnings(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "하락"),
                new CanSlimResult.AnnualEarnings(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "하락"),
                new CanSlimResult.MarketPosition(BigDecimal.ZERO, false, "N/A", "비선도주"),
                new CanSlimResult.SupplyDemand(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "감소"),
                new CanSlimResult.MarketDirection(BigDecimal.ZERO, "약세", "약세"),
                java.util.Map.of()
        );
    }

    private CupAndHandleResult createBreakoutCupResult() {
        return new CupAndHandleResult(
                testStock.getCode(), testStock.getName(),
                CupAndHandleResult.PatternType.BREAKOUT, new BigDecimal("80"),
                LocalDate.now().minusWeeks(20), LocalDate.now().minusWeeks(10), new BigDecimal("20"),
                LocalDate.now().minusWeeks(10), LocalDate.now().minusWeeks(2), new BigDecimal("10"),
                new BigDecimal("80000"), new BigDecimal("90000"), true, "돌파 발생"
        );
    }
}
