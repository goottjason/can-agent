package com.canagent.service.toss;

import com.canagent.port.MarketCalendarPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/**
 * 미국장 개장·휴장 판정 — {@link MarketCalendarPort} 유일 구현(E §5).
 *
 * <p>P7(미국 대전환): {@link #isMarketOpen}은 America/New_York 하드로직(정규장 9:30~16:00 ET, DST 자동),
 * {@link #isTradingDay}는 주말 + <b>정적 NYSE 휴일 집합</b>(연방·거래소 휴일)으로 판정한다. 독립기념일 등에
 * 주말만 보면 오주문이 나므로 정적 폴백은 필수 안전장치다(durable fallback).
 *
 * <p>토스 US market-calendar 실연동은 실호출 확정 후 이 정적 집합을 덮어쓰거나 교차검증하도록 교체한다
 * (=== PoC 미확정 ===). 폴백은 규칙 계산이라 연도 무관하게 성립한다.
 */
@Component
public class TossMarketCalendarAdapter implements MarketCalendarPort {

    private static final Logger log = LoggerFactory.getLogger(TossMarketCalendarAdapter.class);

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(16, 0);

    @Override
    public boolean isTradingDay(LocalDate date) {
        if (date == null) return false;
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        // 정적 NYSE 휴일 폴백(durable) — 토스 US market-calendar 미연동 구간에서도 휴장일 오주문 방지.
        // === PoC 미확정 ===: 실호출 확정 시 토스 캘린더로 교차검증/교체.
        return !isExchangeHoliday(date);
    }

    @Override
    public boolean isMarketOpen(Instant now) {
        if (now == null) return false;
        ZonedDateTime et = now.atZone(ET);
        if (!isTradingDay(et.toLocalDate())) {
            return false;
        }
        LocalTime t = et.toLocalTime();
        return !t.isBefore(MARKET_OPEN) && !t.isAfter(MARKET_CLOSE);
    }

    /**
     * NYSE/NASDAQ 정규 휴장일 판정(정적 규칙). 대상: 신정·MLK·워싱턴탄신일·성금요일·메모리얼데이·
     * 준틴스·독립기념일·노동절·추수감사절·성탄절. 고정일 휴일이 토·일이면 거래소 관측 규칙
     * (토→직전 금, 일→직후 월)로 대체 휴장한다. 단, 신정이 앞 연도로 넘어가는 12/31 대체는 두지 않는다.
     */
    private boolean isExchangeHoliday(LocalDate date) {
        // 고정일 휴일(관측 대체 포함)
        if (isObservedFixedHoliday(date, MonthDay.of(1, 1))) return true;    // 신정
        if (isObservedFixedHoliday(date, MonthDay.of(6, 19))) return true;   // 준틴스(2021~)
        if (isObservedFixedHoliday(date, MonthDay.of(7, 4))) return true;    // 독립기념일
        if (isObservedFixedHoliday(date, MonthDay.of(12, 25))) return true;  // 성탄절

        // 요일기반 휴일
        if (date.equals(nthWeekdayOfMonth(date.getYear(), 1, DayOfWeek.MONDAY, 3))) return true;   // MLK: 1월 셋째 월
        if (date.equals(nthWeekdayOfMonth(date.getYear(), 2, DayOfWeek.MONDAY, 3))) return true;   // 워싱턴탄신일: 2월 셋째 월
        if (date.equals(lastWeekdayOfMonth(date.getYear(), 5, DayOfWeek.MONDAY))) return true;     // 메모리얼데이: 5월 마지막 월
        if (date.equals(nthWeekdayOfMonth(date.getYear(), 9, DayOfWeek.MONDAY, 1))) return true;   // 노동절: 9월 첫 월
        if (date.equals(nthWeekdayOfMonth(date.getYear(), 11, DayOfWeek.THURSDAY, 4))) return true;// 추수감사절: 11월 넷째 목

        // 성금요일(부활절 이틀 전) — 거래소 휴장(연방 공휴일 아님)
        return date.equals(goodFriday(date.getYear()));
    }

    /** 고정일 휴일을 거래소 관측 규칙(토→직전 금, 일→직후 월)까지 반영해 해당 date가 휴장인지 판정. */
    private boolean isObservedFixedHoliday(LocalDate date, MonthDay holiday) {
        LocalDate actual = holiday.atYear(date.getYear());
        LocalDate observed = observed(actual);
        return date.equals(observed);
    }

    /** 고정일 휴일의 관측일: 토요일이면 직전 금요일, 일요일이면 다음 월요일, 평일이면 그대로. */
    private LocalDate observed(LocalDate actual) {
        DayOfWeek dow = actual.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY) return actual.minusDays(1);
        if (dow == DayOfWeek.SUNDAY) return actual.plusDays(1);
        return actual;
    }

    private LocalDate nthWeekdayOfMonth(int year, int month, DayOfWeek dow, int n) {
        return LocalDate.of(year, month, 1)
                .with(TemporalAdjusters.dayOfWeekInMonth(n, dow));
    }

    private LocalDate lastWeekdayOfMonth(int year, int month, DayOfWeek dow) {
        return LocalDate.of(year, month, 1)
                .with(TemporalAdjusters.lastInMonth(dow));
    }

    /** 성금요일 = 부활절 - 2일. 부활절은 Anonymous Gregorian(Meeus/Jones/Butcher) 알고리즘으로 계산. */
    private LocalDate goodFriday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day).minusDays(2);
    }
}
