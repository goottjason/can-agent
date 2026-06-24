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
@DisplayName("시장 방향 분석기 단위테스트")
class MarketDirectionAnalyzerTest {

    @Mock
    private StockPriceRepository stockPriceRepository;

    @InjectMocks
    private MarketDirectionAnalyzer analyzer;

    private Stock stock;

    @BeforeEach
    void setUp() {
        stock = MockDataFactory.createSamsungStock();
    }

    @Test
    @DisplayName("데이터가 부족하면 0점을 리턴한다")
    void analyze_insufficientData_returnsZeroScore() {
        when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(
                any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());

        var result = analyzer.analyze(stock);

        assertThat(result.score()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("강세 시장에서 높은 점수를 리턴한다")
    void analyze_bullMarket_returnsHighScore() {
        List<StockPrice> prices50 = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            BigDecimal price = new BigDecimal("70000").add(new BigDecimal("500").multiply(BigDecimal.valueOf(i)));
            prices50.add(MockDataFactory.createRisingPrice(stock, LocalDate.now().minusDays(50 - i), price));
        }

        List<StockPrice> prices200 = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            BigDecimal price = new BigDecimal("50000").add(new BigDecimal("200").multiply(BigDecimal.valueOf(i)));
            prices200.add(MockDataFactory.createRisingPrice(stock, LocalDate.now().minusDays(200 - i), price));
        }

        when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(
                any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(prices50)
                .thenReturn(prices200);

        var result = analyzer.analyze(stock);

        assertThat(result.score()).isGreaterThan(BigDecimal.ZERO);
    }
}
