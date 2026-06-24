package com.canagent.service.analysis;

import com.canagent.MockDataFactory;
import com.canagent.domain.stock.FinancialStatement;
import com.canagent.domain.stock.Stock;
import com.canagent.domain.stock.StockPrice;
import com.canagent.repository.FinancialStatementRepository;
import com.canagent.repository.StockPriceRepository;
import com.canagent.repository.StockRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("업종 선도주 분석기 단위테스트")
class IndustryLeaderAnalyzerTest {

    @Mock
    private StockRepository stockRepository;
    @Mock
    private StockPriceRepository stockPriceRepository;
    @Mock
    private FinancialStatementRepository financialStatementRepository;

    @InjectMocks
    private IndustryLeaderAnalyzer analyzer;

    private Stock stock;
    private Stock naver;
    private Stock kakao;
    private List<Stock> sectorStocks;

    @BeforeEach
    void setUp() throws Exception {
        stock = MockDataFactory.createSamsungStock();
        naver = MockDataFactory.createNaverStock();
        kakao = MockDataFactory.createKakaoStock();

        setId(stock, 1L);
        setId(naver, 2L);
        setId(kakao, 3L);

        sectorStocks = new ArrayList<>();
        sectorStocks.add(stock);
        sectorStocks.add(naver);
        sectorStocks.add(kakao);
        for (int i = 4; i <= 12; i++) {
            Stock s = MockDataFactory.createStock("00000" + i, "종목" + i, "KOSPI", "반도체");
            setId(s, (long) i);
            sectorStocks.add(s);
        }
    }

    private void setId(Stock s, Long id) throws Exception {
        var field = Stock.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(s, id);
    }

    @Test
    @DisplayName("업종 정보가 없으면 0점을 리턴한다")
    void analyze_noSector_returnsZeroScore() {
        Stock noSectorStock = MockDataFactory.createStock("005930", "삼성전자", "KOSPI", null);

        var result = analyzer.analyze(noSectorStock);

        assertThat(result.score()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("동일 업종 종목이 부족하면 0점을 리턴한다")
    void analyze_insufficientSectorStocks_returnsZeroScore() {
        when(stockRepository.findBySectorAndActiveTrue("반도체")).thenReturn(List.of(stock));

        var result = analyzer.analyze(stock);

        assertThat(result.score()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("가격 데이터가 부족해도 업종 순위는 계산된다")
    void analyze_insufficientPriceData_returnsLowScore() {
        when(stockRepository.findBySectorAndActiveTrue("반도체")).thenReturn(sectorStocks);
        for (Stock s : sectorStocks) {
            when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(
                    eq(s.getId()), any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(Collections.emptyList());
        }
        when(financialStatementRepository.findByStockIdOrderByFiscalYearDescFiscalQuarterDesc(any()))
                .thenReturn(Collections.emptyList());

        var result = analyzer.analyze(stock);

        assertThat(result.score()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("업종 내 1위이고 ROE가 높으면 최고 점수를 리턴한다")
    void analyze_topLeader_returnsHighScore() {
        when(stockRepository.findBySectorAndActiveTrue("반도체")).thenReturn(sectorStocks);

        when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(
                eq(1L), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(createRisingPrices(stock, 20));
        for (int i = 2; i <= 12; i++) {
            when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(
                    eq((long) i), any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(createFlatPrices(sectorStocks.get(i - 1), 20));
        }

        FinancialStatement fs = MockDataFactory.createFinancialStatement(
                stock, 2024, 1, new BigDecimal("5000"), new BigDecimal("50000000000"));
        fs.updateFinancials(
                fs.getRevenue(), fs.getOperatingIncome(), fs.getNetIncome(),
                fs.getEps(), new BigDecimal("25"), fs.getDebtRatio());
        when(financialStatementRepository.findByStockIdOrderByFiscalYearDescFiscalQuarterDesc(eq(1L)))
                .thenReturn(List.of(fs));

        var result = analyzer.analyze(stock);

        assertThat(result.score()).isEqualByComparingTo(new BigDecimal("15"));
        assertThat(result.sectorRank()).isEqualTo(1);
    }

    @Test
    @DisplayName("업종 내 중간 순위면 중간 점수를 리턴한다")
    void analyze_midRank_returnsMidScore() throws Exception {
        Stock midStock = MockDataFactory.createStock("000099", "중간종목", "KOSPI", "반도체");
        setId(midStock, 99L);

        List<Stock> testSectorStocks = new ArrayList<>(sectorStocks);
        testSectorStocks.add(midStock);

        when(stockRepository.findBySectorAndActiveTrue("반도체")).thenReturn(testSectorStocks);

        when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(
                eq(99L), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(createFlatPrices(midStock, 20));
        when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(
                eq(1L), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(createRisingPrices(stock, 20));
        for (int i = 2; i <= 12; i++) {
            when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(
                    eq((long) i), any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(createFlatPrices(sectorStocks.get(i - 1), 20));
        }

        when(financialStatementRepository.findByStockIdOrderByFiscalYearDescFiscalQuarterDesc(any()))
                .thenReturn(Collections.emptyList());

        var result = analyzer.analyze(midStock);

        assertThat(result.score()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(result.score()).isLessThanOrEqualTo(new BigDecimal("15"));
        assertThat(result.sectorRank()).isGreaterThan(1);
    }

    @Test
    @DisplayName("업종 선도주 분석 결과에 선도주 여부가 포함된다")
    void analyze_returnsReason() {
        when(stockRepository.findBySectorAndActiveTrue("반도체")).thenReturn(sectorStocks);

        when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(
                eq(1L), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(createRisingPrices(stock, 20));
        for (int i = 2; i <= 12; i++) {
            when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(
                    eq((long) i), any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(createFlatPrices(sectorStocks.get(i - 1), 20));
        }

        when(financialStatementRepository.findByStockIdOrderByFiscalYearDescFiscalQuarterDesc(any()))
                .thenReturn(Collections.emptyList());

        var result = analyzer.analyze(stock);

        assertThat(result.reason()).contains("13주 상대강도");
        assertThat(result.reason()).contains("ROE");
        assertThat(result.reason()).contains("업종 순위");
    }

    private List<StockPrice> createRisingPrices(Stock s, int count) {
        List<StockPrice> prices = new ArrayList<>();
        BigDecimal basePrice = new BigDecimal("70000");
        for (int i = 0; i < count; i++) {
            BigDecimal close = basePrice.add(new BigDecimal("500").multiply(BigDecimal.valueOf(i)));
            prices.add(MockDataFactory.createRisingPrice(s, LocalDate.now().minusDays(count - i), close));
        }
        return prices;
    }

    private List<StockPrice> createFlatPrices(Stock s, int count) {
        List<StockPrice> prices = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            prices.add(MockDataFactory.createRisingPrice(s, LocalDate.now().minusDays(count - i), new BigDecimal("70000")));
        }
        return prices;
    }
}
