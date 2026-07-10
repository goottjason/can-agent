package com.canagent.port;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 미국장 개장·휴장 판정 경계 포트 — 신규 seam(E §1.1·§5).
 *
 * <p>P6(미국 대전환): 워커·컨트롤러의 {@code isTradingHours}(Asia/Seoul 하드로직)를 ET 판정으로 옮길 이음새.
 * 유일 구현 {@code TossMarketCalendarAdapter}(토스 US market-calendar). 실호출 불가 구간에서는
 * {@link #isMarketOpen(Instant)}는 America/New_York 하드로직(9:30~16:00 ET), {@link #isTradingDay(LocalDate)}는
 * 주말 + 캘린더 휴장일로 판정한다(캘린더 미확정 시 주말만). 크론 배선(P7)은 이번 단계 범위 밖.
 */
public interface MarketCalendarPort {

    /** 해당 날짜가 정규 거래일인지(주말·미국 공휴일 제외). */
    boolean isTradingDay(LocalDate date);

    /** 지금이 정규장 개장 시간인지(ET 9:30~16:00, 거래일 한정). */
    boolean isMarketOpen(Instant now);
}
