package com.canagent.service.toss;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("토스 마켓 캘린더 어댑터 (ET 개장·주말 판정)")
class TossMarketCalendarAdapterTest {

    private final TossMarketCalendarAdapter adapter = new TossMarketCalendarAdapter();
    private static final ZoneId ET = ZoneId.of("America/New_York");

    @Test
    @DisplayName("평일은 거래일, 주말은 아니다")
    void tradingDay_weekdayVsWeekend() {
        assertThat(adapter.isTradingDay(LocalDate.of(2026, 7, 10))).isTrue();  // 금
        assertThat(adapter.isTradingDay(LocalDate.of(2026, 7, 11))).isFalse(); // 토
        assertThat(adapter.isTradingDay(LocalDate.of(2026, 7, 12))).isFalse(); // 일
    }

    @Test
    @DisplayName("정규장 시간(ET 10:00) 개장, 개장 전(09:00)·마감 후(16:30) 폐장")
    void marketOpen_etRegularHours() {
        // ET 기준 시각을 UTC Instant로 변환해 판정(DST 자동)
        assertThat(adapter.isMarketOpen(etInstant(2026, 7, 10, 10, 0))).isTrue();
        assertThat(adapter.isMarketOpen(etInstant(2026, 7, 10, 9, 0))).isFalse();
        assertThat(adapter.isMarketOpen(etInstant(2026, 7, 10, 16, 30))).isFalse();
    }

    @Test
    @DisplayName("개장 경계 9:30 포함, 마감 경계 16:00 포함")
    void marketOpen_boundaries() {
        assertThat(adapter.isMarketOpen(etInstant(2026, 7, 10, 9, 30))).isTrue();
        assertThat(adapter.isMarketOpen(etInstant(2026, 7, 10, 16, 0))).isTrue();
    }

    @Test
    @DisplayName("주말은 정규장 시간이어도 폐장")
    void marketOpen_weekendClosed() {
        assertThat(adapter.isMarketOpen(etInstant(2026, 7, 11, 10, 0))).isFalse(); // 토 10:00 ET
    }

    private static java.time.Instant etInstant(int y, int mo, int d, int h, int mi) {
        return LocalDateTime.of(y, mo, d, h, mi).atZone(ET).toInstant();
    }
}
