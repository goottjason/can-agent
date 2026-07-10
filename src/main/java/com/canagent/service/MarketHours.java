package com.canagent.service;

import com.canagent.port.MarketCalendarPort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 개장·거래일 판정 공유 유틸(E §5·P7). 워커·컨트롤러의 {@code isTradingHours} 3중복을 제거하고
 * {@link MarketCalendarPort}를 유일 소비처로 삼는다(단일 진실) — ET 9:30~16:00 + 주말 + 미국 휴일.
 *
 * <p>{@link Clock}을 주입받아 "지금"을 판정하므로 테스트가 특정 순간을 고정할 수 있다(America/New_York 존은
 * 포트 내부·Clock instant가 담당하므로 여기선 UTC Instant만 흐른다).
 */
@Service
public class MarketHours {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final MarketCalendarPort marketCalendarPort;
    private final Clock clock;

    public MarketHours(MarketCalendarPort marketCalendarPort, Clock clock) {
        this.marketCalendarPort = marketCalendarPort;
        this.clock = clock;
    }

    /** 지금이 정규장 개장 시간인지(ET 9:30~16:00 + 거래일). 주입한 Clock 기준. */
    public boolean isTradingHours() {
        return marketCalendarPort.isMarketOpen(clock.instant());
    }

    /** 임의 순간이 정규장 개장 시간인지 — 검사 흐름 중 특정 시각 재판정용. */
    public boolean isTradingHours(Instant at) {
        return marketCalendarPort.isMarketOpen(at);
    }

    /** 해당 날짜가 거래일인지(주말·미국 휴일 제외). */
    public boolean isTradingDay(LocalDate date) {
        return marketCalendarPort.isTradingDay(date);
    }

    /** 현재 ET 날짜(장중 데이터 UPSERT·기준일 산출용). */
    public LocalDate todayEt() {
        return LocalDate.now(clock.withZone(ET));
    }

    /** 현재 ET 로컬 일시(검사 타임스탬프·로그용). */
    public LocalDateTime nowEt() {
        return LocalDateTime.now(clock.withZone(ET));
    }

    /** 현재 순간(로그·타임스탬프용). */
    public Instant now() {
        return clock.instant();
    }
}
