package com.canagent.service;

import com.canagent.domain.stock.Stock;
import com.canagent.domain.stock.StockPrice;
import com.canagent.repository.StockPriceRepository;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("장중 현재가 멱등 upsert-갱신(upsertIntradaySpot) 단위테스트")
class StockPriceUpserterIntradayTest {

    @Mock
    private StockPriceRepository stockPriceRepository;

    private StockPriceUpserter upserter;

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 14);

    @BeforeEach
    void setUp() {
        upserter = new StockPriceUpserter(stockPriceRepository);
        when(stockPriceRepository.save(any(StockPrice.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private Stock stock() {
        return new Stock("000001", "테스트", "NYSE", "IT");
    }

    private StockPrice candle(Stock s, String o, String h, String l, String c, long v) {
        return new StockPrice(s, TODAY, new BigDecimal(o), new BigDecimal(h),
                new BigDecimal(l), new BigDecimal(c), v);
    }

    @Test
    @DisplayName("오늘 행이 없으면 O=H=L=C=현재가·volume 0 임시행을 INSERT 한다")
    void absentRow_insertsTemporaryRow() {
        Stock stock = stock();
        when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(any(), any(), any()))
                .thenReturn(Collections.emptyList());

        upserter.upsertIntradaySpot(stock, TODAY, new BigDecimal("100"));

        ArgumentCaptor<StockPrice> captor = ArgumentCaptor.forClass(StockPrice.class);
        verify(stockPriceRepository, times(1)).save(captor.capture());
        StockPrice saved = captor.getValue();
        assertThat(saved.getOpen()).isEqualByComparingTo("100");
        assertThat(saved.getHigh()).isEqualByComparingTo("100");
        assertThat(saved.getLow()).isEqualByComparingTo("100");
        assertThat(saved.getClose()).isEqualByComparingTo("100");
        assertThat(saved.getVolume()).isEqualTo(0L);
    }

    @Test
    @DisplayName("오늘 행이 있으면 새 INSERT 없이 그 행을 갱신 저장한다(예외 없음, 중복 없음)")
    void existingRow_updatesInPlaceNoNewInsert() {
        Stock stock = stock();
        // 첫 사이클이 만든 임시행: O=H=L=C=100, volume 0
        StockPrice existing = candle(stock, "100", "100", "100", "100", 0L);
        when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(any(), any(), any()))
                .thenReturn(List.of(existing));

        assertThatCode(() ->
                upserter.upsertIntradaySpot(stock, TODAY, new BigDecimal("120"))
        ).doesNotThrowAnyException();

        ArgumentCaptor<StockPrice> captor = ArgumentCaptor.forClass(StockPrice.class);
        verify(stockPriceRepository, times(1)).save(captor.capture());
        // 저장된 것은 조회된 기존 인스턴스여야 한다(새 엔티티 아님 → 유니크 위반 없음)
        assertThat(captor.getValue()).isSameAs(existing);
        // close 갱신, high 확장(100→120), open·low·volume 보존
        assertThat(existing.getClose()).isEqualByComparingTo("120");
        assertThat(existing.getHigh()).isEqualByComparingTo("120");
        assertThat(existing.getLow()).isEqualByComparingTo("100");
        assertThat(existing.getOpen()).isEqualByComparingTo("100");
        assertThat(existing.getVolume()).isEqualTo(0L);
    }

    @Test
    @DisplayName("같은 (stock, today) 2회 호출: 조회→갱신 경로만 타고 INSERT는 1회뿐(당일 첫 호출)")
    void twoCalls_onlyOneInsertThenUpdate() {
        Stock stock = stock();
        StockPrice inserted = candle(stock, "100", "100", "100", "100", 0L);
        // 1회차: 행 없음 → INSERT, 2회차: 방금 INSERT 된 행 존재 → 갱신
        when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(any(), any(), any()))
                .thenReturn(Collections.emptyList())
                .thenReturn(List.of(inserted));

        assertThatCode(() -> {
            upserter.upsertIntradaySpot(stock, TODAY, new BigDecimal("100"));
            upserter.upsertIntradaySpot(stock, TODAY, new BigDecimal("120"));
        }).doesNotThrowAnyException();

        // 2회 호출이지만 새 엔티티 생성은 1회(첫 호출). 2회차는 기존행 갱신.
        verify(stockPriceRepository, times(2)).save(any(StockPrice.class));
        assertThat(inserted.getClose()).isEqualByComparingTo("120");
        assertThat(inserted.getHigh()).isEqualByComparingTo("120");
    }

    @Test
    @DisplayName("정식 캔들(open·volume 있음)이 있으면 스팟 갱신이 open·volume·high를 보존한다")
    void officialCandle_openVolumeHighPreserved() {
        Stock stock = stock();
        // 캔들 동기화 경로가 채운 정식 행: open 200, high 210, low 195, volume 500000
        StockPrice official = candle(stock, "200", "210", "195", "205", 500000L);
        when(stockPriceRepository.findByStockIdAndDateBetweenOrderByDateAsc(any(), any(), any()))
                .thenReturn(List.of(official));

        upserter.upsertIntradaySpot(stock, TODAY, new BigDecimal("190"));

        verify(stockPriceRepository, never()).save(org.mockito.ArgumentMatchers.argThat(
                sp -> sp != official)); // 새 엔티티 저장 금지
        assertThat(official.getClose()).isEqualByComparingTo("190"); // close 갱신
        assertThat(official.getLow()).isEqualByComparingTo("190");   // low 확장(195→190)
        assertThat(official.getHigh()).isEqualByComparingTo("210");  // high 보존
        assertThat(official.getOpen()).isEqualByComparingTo("200");  // 시가 불변
        assertThat(official.getVolume()).isEqualTo(500000L);         // 거래량 불변
    }
}
