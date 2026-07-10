package com.canagent.service;

import com.canagent.port.MarketCalendarPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("공유 개장판정 유틸(MarketHours) — MarketCalendarPort 단일 소비, 시각 주입")
class MarketHoursTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    /** 실제 어댑터를 소비해 워커·컨트롤러와 동일 판정을 보장(단일 진실 회귀 방지). */
    private MarketCalendarPort port() {
        return new com.canagent.service.toss.TossMarketCalendarAdapter();
    }

    private MarketHours atEt(int y, int mo, int d, int h, int mi) {
        Instant instant = LocalDateTime.of(y, mo, d, h, mi).atZone(ET).toInstant();
        return new MarketHours(port(), Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("정규장 시간 개장 — 주입한 Clock 시각으로 판정")
    void open_duringRegularHours() {
        assertThat(atEt(2026, 7, 10, 10, 0).isTradingHours()).isTrue();
    }

    @Test
    @DisplayName("개장 경계 9:30 포함, 마감 경계 16:00 포함")
    void open_boundaries() {
        assertThat(atEt(2026, 7, 10, 9, 30).isTradingHours()).isTrue();
        assertThat(atEt(2026, 7, 10, 16, 0).isTradingHours()).isTrue();
    }

    @Test
    @DisplayName("개장 전(9:00)·마감 후(16:30) 폐장")
    void closed_offHours() {
        assertThat(atEt(2026, 7, 10, 9, 0).isTradingHours()).isFalse();
        assertThat(atEt(2026, 7, 10, 16, 30).isTradingHours()).isFalse();
    }

    @Test
    @DisplayName("주말은 정규장 시간이어도 폐장")
    void closed_weekend() {
        assertThat(atEt(2026, 7, 11, 10, 0).isTradingHours()).isFalse();
    }

    @Test
    @DisplayName("미국 휴일(독립기념일)은 정규장 시간이어도 폐장")
    void closed_holiday() {
        assertThat(atEt(2025, 7, 4, 10, 0).isTradingHours()).isFalse();
    }

    @Test
    @DisplayName("isTradingDay는 포트 위임 — 휴일·주말 false, 평일 true")
    void tradingDay_delegatesToPort() {
        MarketHours mh = atEt(2026, 7, 10, 10, 0);
        assertThat(mh.isTradingDay(LocalDate.of(2026, 7, 10))).isTrue();
        assertThat(mh.isTradingDay(LocalDate.of(2026, 7, 11))).isFalse();
        assertThat(mh.isTradingDay(LocalDate.of(2025, 7, 4))).isFalse();
    }

    @Test
    @DisplayName("todayEt는 주입한 Clock의 ET 날짜를 반환(존 전환 검증)")
    void todayEt_usesEtZone() {
        // UTC 2026-07-11 02:00 = ET 2026-07-10 22:00 → ET 날짜는 7/10
        Instant instant = LocalDateTime.of(2026, 7, 11, 2, 0).atZone(ZoneOffset.UTC).toInstant();
        MarketHours mh = new MarketHours(port(), Clock.fixed(instant, ZoneOffset.UTC));
        assertThat(mh.todayEt()).isEqualTo(LocalDate.of(2026, 7, 10));
    }
}
