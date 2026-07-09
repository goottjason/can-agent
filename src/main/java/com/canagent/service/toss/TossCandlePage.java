package com.canagent.service.toss;

import java.util.Collections;
import java.util.List;

/**
 * 토스 캔들 한 페이지: 캔들 목록 + 다음(더 과거) 페이지 커서.
 *
 * <p>{@code nextBefore}가 null/blank면 더 과거 데이터가 없다는 신호로 페이지네이션을 종료한다.
 * 커서는 토스가 준 값을 그대로 다음 요청의 {@code before}로 되돌려 보내는 <b>불투명 토큰</b>이다
 * (우리가 날짜를 파생하지 않음 — 형식 변동에 견고).
 */
public final class TossCandlePage {

    private final List<TossCandle> candles;
    private final String nextBefore;

    public TossCandlePage(List<TossCandle> candles, String nextBefore) {
        this.candles = candles == null ? Collections.emptyList() : candles;
        this.nextBefore = nextBefore;
    }

    public static TossCandlePage empty() {
        return new TossCandlePage(Collections.emptyList(), null);
    }

    public List<TossCandle> getCandles() { return candles; }

    public String getNextBefore() { return nextBefore; }

    public boolean hasMore() {
        return nextBefore != null && !nextBefore.isBlank();
    }
}
