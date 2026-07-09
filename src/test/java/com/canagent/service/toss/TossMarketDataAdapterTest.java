package com.canagent.service.toss;

import com.canagent.domain.stock.Stock;
import com.canagent.domain.stock.StockPrice;
import com.canagent.repository.StockPriceRepository;
import com.canagent.repository.StockRepository;
import com.canagent.service.StockPriceUpserter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("토스 시세 어댑터 (검증 a·b·e: 매핑·백필·skip)")
class TossMarketDataAdapterTest {

    @Mock
    private TossCandleClient candleClient;
    @Mock
    private StockRepository stockRepository;
    @Mock
    private StockPriceRepository stockPriceRepository;

    private TossMarketDataAdapter adapter;

    @BeforeEach
    void setUp() {
        // 실 StockPriceUpserter(공유 헬퍼) + mock 리포지토리로 JSON→StockPrice 매핑 전 구간을 검증.
        StockPriceUpserter upserter = new StockPriceUpserter(stockPriceRepository);
        adapter = new TossMarketDataAdapter(candleClient, stockRepository, upserter);
    }

    private Stock usStock(String ticker) {
        Stock s = new Stock("AAPL", "Apple Inc.", "US", "Technology");
        s.setTicker(ticker);
        return s;
    }

    private TossCandle candle(String date, String o, String h, String l, String c, long v) {
        return new TossCandle(LocalDate.parse(date), new BigDecimal(o), new BigDecimal(h),
                new BigDecimal(l), new BigDecimal(c), v);
    }

    /** 신규 저장을 흉내: 중복 없음 + save는 인자를 그대로 반환. */
    private void stubFreshSave() {
        when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(stockPriceRepository.save(any(StockPrice.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("(a) 캔들 → StockPrice OHLCV 매핑·저장")
    void mapsCandlesToStockPrice() {
        Stock stock = usStock("AAPL");
        when(stockRepository.findByActiveTrue()).thenReturn(List.of(stock));
        stubFreshSave();
        when(candleClient.getDailyCandles(eq("AAPL"), anyInt(), isNull()))
                .thenReturn(new TossCandlePage(List.of(
                        candle("2024-03-15", "170.00", "172.50", "169.80", "171.20", 52000000L)), null));

        int saved = adapter.syncAllActiveStocks(LocalDate.of(2024, 3, 15), LocalDate.of(2024, 3, 15));

        assertThat(saved).isEqualTo(1);
        ArgumentCaptor<StockPrice> cap = ArgumentCaptor.forClass(StockPrice.class);
        verify(stockPriceRepository).save(cap.capture());
        StockPrice sp = cap.getValue();
        assertThat(sp.getDate()).isEqualTo(LocalDate.of(2024, 3, 15));
        assertThat(sp.getOpen()).isEqualByComparingTo("170.00");
        assertThat(sp.getHigh()).isEqualByComparingTo("172.50");
        assertThat(sp.getLow()).isEqualByComparingTo("169.80");
        assertThat(sp.getClose()).isEqualByComparingTo("171.20");
        assertThat(sp.getVolume()).isEqualTo(52000000L);
    }

    @Test
    @DisplayName("(b) before/nextBefore로 startDate까지 여러 페이지 백필")
    void paginatesBackToStartDate() {
        Stock stock = usStock("AAPL");
        when(stockRepository.findByActiveTrue()).thenReturn(List.of(stock));
        stubFreshSave();
        // page1(nextBefore 커서 있음) → page2(nextBefore null)
        when(candleClient.getDailyCandles(eq("AAPL"), anyInt(), isNull()))
                .thenReturn(new TossCandlePage(List.of(
                        candle("2024-03-15", "1", "1", "1", "1", 1),
                        candle("2024-03-14", "1", "1", "1", "1", 1),
                        candle("2024-03-13", "1", "1", "1", "1", 1)), "CURSOR-13"));
        when(candleClient.getDailyCandles(eq("AAPL"), anyInt(), eq("CURSOR-13")))
                .thenReturn(new TossCandlePage(List.of(
                        candle("2024-03-12", "1", "1", "1", "1", 1),
                        candle("2024-03-11", "1", "1", "1", "1", 1)), null));

        int saved = adapter.syncAllActiveStocks(LocalDate.of(2024, 3, 11), LocalDate.of(2024, 3, 15));

        assertThat(saved).isEqualTo(5);
        verify(candleClient).getDailyCandles(eq("AAPL"), anyInt(), isNull());
        verify(candleClient).getDailyCandles(eq("AAPL"), anyInt(), eq("CURSOR-13"));
    }

    @Test
    @DisplayName("startDate 이전 캔들에 닿으면 그 페이지에서 백필 종료(다음 페이지 미조회)")
    void stopsAtStartDateWithinPage() {
        Stock stock = usStock("AAPL");
        when(stockRepository.findByActiveTrue()).thenReturn(List.of(stock));
        stubFreshSave();
        when(candleClient.getDailyCandles(eq("AAPL"), anyInt(), isNull()))
                .thenReturn(new TossCandlePage(List.of(
                        candle("2024-03-15", "1", "1", "1", "1", 1),
                        candle("2024-03-14", "1", "1", "1", "1", 1),
                        candle("2024-03-13", "1", "1", "1", "1", 1)), "CURSOR-13"));

        int saved = adapter.syncAllActiveStocks(LocalDate.of(2024, 3, 14), LocalDate.of(2024, 3, 15));

        // 03-15, 03-14 저장. 03-13 < startDate → 종료. 다음 페이지(CURSOR-13) 미조회.
        assertThat(saved).isEqualTo(2);
        verify(candleClient, never()).getDailyCandles(eq("AAPL"), anyInt(), eq("CURSOR-13"));
    }

    @Test
    @DisplayName("endDate보다 최신 캔들은 저장하지 않는다")
    void skipsCandlesNewerThanEndDate() {
        Stock stock = usStock("AAPL");
        when(stockRepository.findByActiveTrue()).thenReturn(List.of(stock));
        stubFreshSave();
        when(candleClient.getDailyCandles(eq("AAPL"), anyInt(), isNull()))
                .thenReturn(new TossCandlePage(List.of(
                        candle("2024-03-20", "1", "1", "1", "1", 1), // endDate 이후 → skip
                        candle("2024-03-15", "1", "1", "1", "1", 1)), null));

        int saved = adapter.syncAllActiveStocks(LocalDate.of(2024, 3, 15), LocalDate.of(2024, 3, 15));

        assertThat(saved).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 있는 날짜는 중복 저장하지 않는다(dedup)")
    void dedupExisting() {
        Stock stock = usStock("AAPL");
        when(stockRepository.findByActiveTrue()).thenReturn(List.of(stock));
        // 존재하는 것으로 스텁 → upsert가 null 반환
        when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(any(), any(), any()))
                .thenReturn(List.of(new StockPrice(stock, LocalDate.of(2024, 3, 15),
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1L)));
        when(candleClient.getDailyCandles(eq("AAPL"), anyInt(), isNull()))
                .thenReturn(new TossCandlePage(List.of(
                        candle("2024-03-15", "170", "170", "170", "170", 1)), null));

        int saved = adapter.syncAllActiveStocks(LocalDate.of(2024, 3, 15), LocalDate.of(2024, 3, 15));

        assertThat(saved).isEqualTo(0);
        verify(stockPriceRepository, never()).save(any());
    }

    @Test
    @DisplayName("(e) ticker 없는 종목은 캔들 호출 없이 skip")
    void skipsStockWithoutTicker() {
        Stock noTicker = new Stock("005930", "삼성전자", "KOSPI", "반도체"); // ticker null
        when(stockRepository.findByActiveTrue()).thenReturn(List.of(noTicker));

        int saved = adapter.syncAllActiveStocks(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 15));

        assertThat(saved).isEqualTo(0);
        verify(candleClient, never()).getDailyCandles(any(), anyInt(), any());
        verify(stockPriceRepository, never()).save(any());
    }

    @Test
    @DisplayName("활성 종목이 없으면 0건")
    void noActiveStocks() {
        when(stockRepository.findByActiveTrue()).thenReturn(Collections.emptyList());
        int saved = adapter.syncAllActiveStocks(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 15));
        assertThat(saved).isEqualTo(0);
    }
}
