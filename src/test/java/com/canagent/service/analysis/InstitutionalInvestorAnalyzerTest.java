package com.canagent.service.analysis;

import com.canagent.MockDataFactory;
import com.canagent.domain.stock.Stock;
import com.canagent.domain.stock.StockPrice;
import com.canagent.repository.StockPriceRepository;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("기관 투자자 분석기 단위테스트")
class InstitutionalInvestorAnalyzerTest {

    @Mock
    private StockPriceRepository stockPriceRepository;

    @InjectMocks
    private InstitutionalInvestorAnalyzer analyzer;

    private Stock stock;

    @BeforeEach
    void setUp() {
        stock = MockDataFactory.createSamsungStock();
    }

    @Test
    @DisplayName("4주간 데이터가 부족하면 0점을 리턴한다")
    void analyze_insufficientData_returnsZeroScore() {
        when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(
                any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());

        var result = analyzer.analyze(stock);

        assertThat(result.score()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("20일 데이터가 부족하면 0점을 리턴한다")
    void analyze_lessThan20Days_returnsZeroScore() {
        List<StockPrice> recentPrices = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            recentPrices.add(MockDataFactory.createRisingPrice(
                    stock, LocalDate.now().minusWeeks(4).plusDays(i), new BigDecimal("70000")));
        }

        List<StockPrice> allPrices = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            allPrices.add(MockDataFactory.createRisingPrice(
                    stock, LocalDate.now().minusDays(10 - i), new BigDecimal("70000")));
        }

        when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(
                any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(recentPrices);
        when(stockPriceRepository.findByStockIdOrderByDateDesc(any()))
                .thenReturn(allPrices);

        var result = analyzer.analyze(stock);

        assertThat(result.score()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("거래량이 크게 증가하고 가격이 오르면 높은 점수를 리턴한다")
    void analyze_highVolumeAndPriceIncrease_returnsHighScore() {
        List<StockPrice> recentPrices = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            StockPrice price = MockDataFactory.createRisingPrice(
                    stock, LocalDate.now().minusDays(20 - i),
                    new BigDecimal("70000").add(new BigDecimal("500").multiply(BigDecimal.valueOf(i))));
            recentPrices.add(price);
        }

        List<StockPrice> allPrices = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            allPrices.add(MockDataFactory.createRisingPrice(
                    stock, LocalDate.now().minusDays(25 - i), new BigDecimal("70000")));
        }

        when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(
                any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(recentPrices);
        when(stockPriceRepository.findByStockIdOrderByDateDesc(any()))
                .thenReturn(allPrices);

        var result = analyzer.analyze(stock);

        assertThat(result.score()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("거래량이 감소하고 가격이 내려가면 낮은 점수를 리턴한다")
    void analyze_lowVolumeAndPriceDecrease_returnsLowScore() {
        List<StockPrice> recentPrices = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            recentPrices.add(MockDataFactory.createFallingPrice(
                    stock, LocalDate.now().minusDays(20 - i),
                    new BigDecimal("75000").subtract(new BigDecimal("500").multiply(BigDecimal.valueOf(i)))));
        }

        List<StockPrice> allPrices = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            allPrices.add(MockDataFactory.createRisingPrice(
                    stock, LocalDate.now().minusDays(25 - i), new BigDecimal("70000")));
        }

        when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(
                any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(recentPrices);
        when(stockPriceRepository.findByStockIdOrderByDateDesc(any()))
                .thenReturn(allPrices);

        var result = analyzer.analyze(stock);

        assertThat(result.score()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(result.score()).isLessThanOrEqualTo(new BigDecimal("15"));
    }

    @Test
    @DisplayName("분석 결과에 거래량 증가율이 포함된다")
    void analyze_returnsReason() {
        List<StockPrice> recentPrices = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            recentPrices.add(MockDataFactory.createRisingPrice(
                    stock, LocalDate.now().minusDays(20 - i), new BigDecimal("70000")));
        }

        List<StockPrice> allPrices = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            allPrices.add(MockDataFactory.createRisingPrice(
                    stock, LocalDate.now().minusDays(25 - i), new BigDecimal("70000")));
        }

        when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(
                any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(recentPrices);
        when(stockPriceRepository.findByStockIdOrderByDateDesc(any()))
                .thenReturn(allPrices);

        var result = analyzer.analyze(stock);

        assertThat(result.reason()).contains("거래량 증가율");
        assertThat(result.reason()).contains("4주 가격변화");
        assertThat(result.reason()).contains("고거래량일");
    }
}
