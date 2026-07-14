package com.canagent.domain.stock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StockPrice 장중 스팟 갱신(applyIntradaySpot) 불변식 단위테스트")
class StockPriceTest {

    private StockPrice candle(String open, String high, String low, String close, long volume) {
        return new StockPrice(null, LocalDate.of(2026, 7, 14),
                new BigDecimal(open), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(close), volume);
    }

    @Test
    @DisplayName("close를 최신 스팟으로 갱신하고 open·volume은 보존한다")
    void updatesCloseKeepsOpenAndVolume() {
        StockPrice sp = candle("100", "110", "90", "105", 12345L);

        sp.applyIntradaySpot(new BigDecimal("108"));

        assertThat(sp.getClose()).isEqualByComparingTo("108");
        assertThat(sp.getOpen()).isEqualByComparingTo("100");
        assertThat(sp.getVolume()).isEqualTo(12345L);
    }

    @Test
    @DisplayName("스팟이 기존 high보다 크면 high를 확장한다")
    void expandsHighWhenSpotAbove() {
        StockPrice sp = candle("100", "110", "90", "105", 0L);

        sp.applyIntradaySpot(new BigDecimal("120"));

        assertThat(sp.getHigh()).isEqualByComparingTo("120");
        assertThat(sp.getLow()).isEqualByComparingTo("90");
    }

    @Test
    @DisplayName("스팟이 기존 low보다 작으면 low를 확장한다")
    void expandsLowWhenSpotBelow() {
        StockPrice sp = candle("100", "110", "90", "105", 0L);

        sp.applyIntradaySpot(new BigDecimal("80"));

        assertThat(sp.getLow()).isEqualByComparingTo("80");
        assertThat(sp.getHigh()).isEqualByComparingTo("110");
    }

    @Test
    @DisplayName("스팟이 high~low 범위 내면 high·low를 건드리지 않는다")
    void keepsHighLowWhenSpotWithinRange() {
        StockPrice sp = candle("100", "110", "90", "105", 0L);

        sp.applyIntradaySpot(new BigDecimal("100"));

        assertThat(sp.getHigh()).isEqualByComparingTo("110");
        assertThat(sp.getLow()).isEqualByComparingTo("90");
        assertThat(sp.getClose()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("null 스팟은 무시한다(기존 값 불변)")
    void ignoresNullSpot() {
        StockPrice sp = candle("100", "110", "90", "105", 42L);

        sp.applyIntradaySpot(null);

        assertThat(sp.getClose()).isEqualByComparingTo("105");
        assertThat(sp.getHigh()).isEqualByComparingTo("110");
        assertThat(sp.getLow()).isEqualByComparingTo("90");
        assertThat(sp.getVolume()).isEqualTo(42L);
    }
}
