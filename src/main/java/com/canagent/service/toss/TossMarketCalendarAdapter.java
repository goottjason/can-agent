package com.canagent.service.toss;

import com.canagent.port.MarketCalendarPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 미국장 개장·휴장 판정 — {@link MarketCalendarPort} 유일 구현(E §5).
 *
 * <p>P6(미국 대전환): 실호출 불가 구간이라 캘린더(토스 US market-calendar)는 아직 붙이지 않고,
 * {@link #isMarketOpen}은 America/New_York 하드로직(정규장 9:30~16:00 ET, DST 자동), {@link #isTradingDay}는
 * 주말 판정만 한다. 미국 공휴일 캘린더 연동은 실호출 확정 후(=== PoC 미확정 ===). 크론 배선(P7)은 별도 단계.
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
        // === PoC 미확정 ===: 미국 공휴일 캘린더(토스 US market-calendar) 미연동 — 실호출 확정 후 휴장일 반영.
        return true;
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
}
