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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("매매 전략 서비스 통합테스트")
@Transactional
class TradingStrategyServiceIntegrationTest {

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

        StockPrice price = MockDataFactory.createRisingPrice(
                testStock, LocalDate.now(), new BigDecimal("75000"));
        stockPriceRepository.save(price);
    }

    @Test
    @DisplayName("매수 조건 충족 시 매수 결정을 리턴한다")
    void evaluateBuy_conditionMet_returnsBuyDecision() {
        when(canSlimAnalysisService.analyze(any()))
                .thenReturn(createHighScoreCanSlimResult());
        when(cupAndHandleAnalyzer.analyze(any()))
                .thenReturn(createBuySignalCupResult());

        StockPrice latestPrice = stockPriceRepository.findTopByStockIdOrderByDateDesc(testStock.getId()).orElseThrow();

        TradingDecision decision = tradingStrategyService.evaluateBuy(testStock, latestPrice.getClose());

        assertThat(decision.shouldBuy()).isTrue();
        assertThat(decision.quantity()).isGreaterThan(0);
    }

    @Test
    @DisplayName("이미 보유 중인 종목이면 매수하지 않는다")
    void evaluateBuy_alreadyHolding_returnsHoldDecision() {
        Portfolio portfolio = MockDataFactory.createPortfolio(testStock, 10, new BigDecimal("75000"));
        portfolioRepository.save(portfolio);

        when(canSlimAnalysisService.analyze(any()))
                .thenReturn(createHighScoreCanSlimResult());
        when(cupAndHandleAnalyzer.analyze(any()))
                .thenReturn(createBuySignalCupResult());

        StockPrice latestPrice = stockPriceRepository.findTopByStockIdOrderByDateDesc(testStock.getId()).orElseThrow();

        TradingDecision decision = tradingStrategyService.evaluateBuy(testStock, latestPrice.getClose());

        assertThat(decision.shouldBuy()).isFalse();
    }

    @Test
    @DisplayName("매도 조건 충족 시 매도 결정을 리턴한다")
    void evaluateSell_stopLossMet_returnsSellDecision() {
        Portfolio portfolio = MockDataFactory.createPortfolio(testStock, 10, new BigDecimal("80000"));
        portfolioRepository.save(portfolio);

        when(canSlimAnalysisService.analyze(any()))
                .thenReturn(createLowScoreCanSlimResult());

        StockPrice latestPrice = MockDataFactory.createFallingPrice(
                testStock, LocalDate.now(), new BigDecimal("70000"));

        TradingDecision decision = tradingStrategyService.evaluateSell(testStock, latestPrice.getClose());

        assertThat(decision.shouldSell()).isTrue();
    }

    @Test
    @DisplayName("매수 실행 시 포트폴리오가 생성된다")
    void executeBuy_createsPortfolio() {
        int quantity = 10;
        BigDecimal price = new BigDecimal("75000");

        Trade trade = tradingStrategyService.executeBuy(testStock, quantity, price, "테스트 매수");

        assertThat(trade).isNotNull();
        assertThat(trade.getTradeType()).isEqualTo(TradeType.BUY);

        Optional<Portfolio> portfolio = portfolioRepository.findByStockIdAndActiveTrue(testStock.getId());
        assertThat(portfolio).isPresent();
        assertThat(portfolio.get().getQuantity()).isEqualTo(quantity);
    }

    @Test
    @DisplayName("매도 실행 시 포트폴리오가 갱신된다")
    void executeSell_updatesPortfolio() {
        Portfolio portfolio = MockDataFactory.createPortfolio(testStock, 10, new BigDecimal("75000"));
        portfolioRepository.save(portfolio);

        int sellQuantity = 5;
        BigDecimal sellPrice = new BigDecimal("80000");

        Trade trade = tradingStrategyService.executeSell(testStock, sellQuantity, sellPrice, "테스트 매도");

        assertThat(trade).isNotNull();
        assertThat(trade.getTradeType()).isEqualTo(TradeType.SELL);

        Optional<Portfolio> updatedPortfolio = portfolioRepository.findByStockIdAndActiveTrue(testStock.getId());
        assertThat(updatedPortfolio).isPresent();
        assertThat(updatedPortfolio.get().getQuantity()).isEqualTo(5);
    }

    private CanSlimResult createHighScoreCanSlimResult() {
        return new CanSlimResult(
                testStock.getCode(),
                testStock.getName(),
                new BigDecimal("85"),
                new CanSlimResult.QuarterlyEarnings(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, "성장"),
                new CanSlimResult.AnnualEarnings(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, "성장"),
                new CanSlimResult.MarketPosition(BigDecimal.TEN, true, "1위", "선도주"),
                new CanSlimResult.SupplyDemand(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, "증가"),
                new CanSlimResult.MarketDirection(BigDecimal.TEN, "강세", "강세"),
                java.util.Map.of()
        );
    }

    private CanSlimResult createLowScoreCanSlimResult() {
        return new CanSlimResult(
                testStock.getCode(),
                testStock.getName(),
                BigDecimal.TEN,
                new CanSlimResult.QuarterlyEarnings(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "하락"),
                new CanSlimResult.AnnualEarnings(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "하락"),
                new CanSlimResult.MarketPosition(BigDecimal.ZERO, false, "N/A", "비선도주"),
                new CanSlimResult.SupplyDemand(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "감소"),
                new CanSlimResult.MarketDirection(BigDecimal.ZERO, "약세", "약세"),
                java.util.Map.of()
        );
    }

    private CupAndHandleResult createBuySignalCupResult() {
        return new CupAndHandleResult(
                testStock.getCode(),
                testStock.getName(),
                CupAndHandleResult.PatternType.BREAKOUT,
                new BigDecimal("80"),
                LocalDate.now().minusWeeks(20),
                LocalDate.now().minusWeeks(10),
                new BigDecimal("20"),
                LocalDate.now().minusWeeks(10),
                LocalDate.now().minusWeeks(2),
                new BigDecimal("10"),
                new BigDecimal("80000"),
                new BigDecimal("90000"),
                true,
                "돌파 발생"
        );
    }
}
