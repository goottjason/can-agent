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

    @Test
    @DisplayName("미국 거래소 휴일(고정일)은 거래일 아님 — 정적 폴백")
    void tradingDay_fixedHolidays() {
        assertThat(adapter.isTradingDay(LocalDate.of(2026, 1, 1))).isFalse();   // 신정
        assertThat(adapter.isTradingDay(LocalDate.of(2026, 6, 19))).isFalse();  // 준틴스
        assertThat(adapter.isTradingDay(LocalDate.of(2025, 7, 4))).isFalse();   // 독립기념일(금)
        assertThat(adapter.isTradingDay(LocalDate.of(2026, 12, 25))).isFalse(); // 성탄절
    }

    @Test
    @DisplayName("고정 휴일이 주말이면 관측 대체일(평일)로 휴장 — 2021-07-05(월, 7/4 일 대체)")
    void tradingDay_fixedHolidayObservedShift() {
        assertThat(adapter.isTradingDay(LocalDate.of(2021, 7, 5))).isFalse();   // 독립기념일 관측(월)
        assertThat(adapter.isTradingDay(LocalDate.of(2021, 12, 24))).isFalse(); // 성탄절 관측(금, 12/25 토)
        assertThat(adapter.isTradingDay(LocalDate.of(2021, 12, 31))).isTrue();  // 신정(2022-01-01 토) → 앞 연도 12/31로 밀지 않음
    }

    @Test
    @DisplayName("미국 거래소 휴일(요일기반)은 거래일 아님 — MLK·워싱턴탄신·메모리얼·노동절·추수감사절")
    void tradingDay_floatingHolidays() {
        assertThat(adapter.isTradingDay(LocalDate.of(2026, 1, 19))).isFalse();  // MLK: 1월 셋째 월
        assertThat(adapter.isTradingDay(LocalDate.of(2026, 2, 16))).isFalse();  // 워싱턴탄신일: 2월 셋째 월
        assertThat(adapter.isTradingDay(LocalDate.of(2026, 5, 25))).isFalse();  // 메모리얼데이: 5월 마지막 월
        assertThat(adapter.isTradingDay(LocalDate.of(2026, 9, 7))).isFalse();   // 노동절: 9월 첫 월
        assertThat(adapter.isTradingDay(LocalDate.of(2026, 11, 26))).isFalse(); // 추수감사절: 11월 넷째 목
    }

    @Test
    @DisplayName("성금요일(부활절 이틀 전)은 거래소 휴장 — 2026-04-03")
    void tradingDay_goodFriday() {
        assertThat(adapter.isTradingDay(LocalDate.of(2026, 4, 3))).isFalse();   // 2026 성금요일
        assertThat(adapter.isTradingDay(LocalDate.of(2025, 4, 18))).isFalse();  // 2025 성금요일
    }

    @Test
    @DisplayName("휴일이 아닌 평일은 정상 거래일 — 회귀 방지")
    void tradingDay_ordinaryWeekdayStillOpen() {
        assertThat(adapter.isTradingDay(LocalDate.of(2026, 7, 10))).isTrue();   // 금(휴일 아님)
        assertThat(adapter.isTradingDay(LocalDate.of(2026, 3, 17))).isTrue();
    }

    @Test
    @DisplayName("휴일 당일은 정규장 시간이어도 폐장 — 독립기념일 10:00 ET")
    void marketOpen_holidayClosed() {
        assertThat(adapter.isMarketOpen(etInstant(2025, 7, 4, 10, 0))).isFalse(); // 독립기념일 금 10:00 ET
    }

    @Test
    @DisplayName("DST 전후 개장 판정 — 봄(2026-03-08 전환일 다음 거래일)·가을(2026-11-01 이후) ET 10:00 개장")
    void marketOpen_dstTransitions() {
        // 2026 DST: 3/8 시작, 11/1 종료. 전환 직후 첫 평일 정규장이 ET로 정확히 판정되는지.
        assertThat(adapter.isMarketOpen(etInstant(2026, 3, 9, 10, 0))).isTrue();  // 봄 전환 다음날(월)
        assertThat(adapter.isMarketOpen(etInstant(2026, 11, 2, 10, 0))).isTrue(); // 가을 전환 다음날(월)
    }

    private static java.time.Instant etInstant(int y, int mo, int d, int h, int mi) {
        return LocalDateTime.of(y, mo, d, h, mi).atZone(ET).toInstant();
    }
}
