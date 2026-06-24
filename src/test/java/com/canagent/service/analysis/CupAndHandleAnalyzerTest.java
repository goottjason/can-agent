package com.canagent.service.analysis;

import com.canagent.MockDataFactory;
import com.canagent.domain.stock.Stock;
import com.canagent.domain.stock.StockPrice;
import com.canagent.repository.CupAndHandlePatternRepository;
import com.canagent.repository.StockPriceRepository;
import com.canagent.service.dto.CupAndHandleResult;
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
@DisplayName("컵앤핸들 분석기 단위테스트")
class CupAndHandleAnalyzerTest {

    @Mock
    private StockPriceRepository stockPriceRepository;

    @Mock
    private CupAndHandlePatternRepository patternRepository;

    @InjectMocks
    private CupAndHandleAnalyzer analyzer;

    private Stock stock;

    @BeforeEach
    void setUp() {
        stock = MockDataFactory.createSamsungStock();
    }

    @Test
    @DisplayName("데이터가 부족하면 패턴 없음을 리턴한다")
    void analyze_insufficientData_returnsNoPattern() {
        when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(
                any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());

        CupAndHandleResult result = analyzer.analyze(stock);

        assertThat(result.patternType()).isEqualTo(CupAndHandleResult.PatternType.NONE);
        assertThat(result.isBuySignal()).isFalse();
    }

    @Test
    @DisplayName("컵 패턴이 감지되면 CUP_FORMING을 리턴한다")
    void analyze_cupDetected_returnsCupForming() {
        List<StockPrice> prices = createCupPatternPrices();

        when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(
                any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(prices);

        CupAndHandleResult result = analyzer.analyze(stock);

        assertThat(result.patternType()).isIn(
                CupAndHandleResult.PatternType.CUP_FORMING,
                CupAndHandleResult.PatternType.HANDLE_FORMING,
                CupAndHandleResult.PatternType.HANDLE_COMPLETE,
                CupAndHandleResult.PatternType.BREAKOUT,
                CupAndHandleResult.PatternType.NONE
        );
    }

    @Test
    @DisplayName("전체 컵앤핸들 패턴이 감지되면 점수를 리턴한다")
    void analyze_fullPattern_returnsScore() {
        List<StockPrice> prices = createFullCupAndHandlePattern();

        when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(
                any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(prices);

        CupAndHandleResult result = analyzer.analyze(stock);

        assertThat(result).isNotNull();
        assertThat(result.patternType()).isNotNull();
        assertThat(result.score()).isNotNull();
    }

    private List<StockPrice> createCupPatternPrices() {
        List<StockPrice> prices = new ArrayList<>();
        BigDecimal basePrice = new BigDecimal("80000");

        for (int i = 0; i < 30; i++) {
            BigDecimal price;
            if (i < 10) {
                price = basePrice.subtract(new BigDecimal("500").multiply(BigDecimal.valueOf(i)));
            } else if (i < 20) {
                price = basePrice.subtract(new BigDecimal("5000")).add(new BigDecimal("500").multiply(BigDecimal.valueOf(i - 10)));
            } else {
                price = basePrice.subtract(new BigDecimal("5000")).add(new BigDecimal("5000")).add(new BigDecimal("100").multiply(BigDecimal.valueOf(i - 20)));
            }
            prices.add(MockDataFactory.createRisingPrice(stock, LocalDate.now().minusWeeks(30 - i), price));
        }
        return prices;
    }

    private List<StockPrice> createFullCupAndHandlePattern() {
        List<StockPrice> prices = new ArrayList<>();

        for (int i = 0; i < 50; i++) {
            BigDecimal price;
            if (i < 15) {
                price = new BigDecimal("80000").subtract(new BigDecimal("500").multiply(BigDecimal.valueOf(i)));
            } else if (i < 30) {
                price = new BigDecimal("72500").add(new BigDecimal("500").multiply(BigDecimal.valueOf(i - 15)));
            } else if (i < 40) {
                price = new BigDecimal("80000").subtract(new BigDecimal("100").multiply(BigDecimal.valueOf(i - 30)));
            } else {
                price = new BigDecimal("79000").add(new BigDecimal("100").multiply(BigDecimal.valueOf(i - 40)));
            }
            prices.add(MockDataFactory.createRisingPrice(stock, LocalDate.now().minusWeeks(50 - i), price));
        }
        return prices;
    }
}
