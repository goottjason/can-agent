package com.canagent.service.toss;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 토스 일봉 캔들 한 건(어댑터 소비용 얇은 DTO).
 *
 * <p>P5: JSON→도메인 사이의 얇은 매핑 결과. 실제 응답 필드명은 PoC 미확정이라
 * {@link TossCandleClient}의 매핑 한 곳에서 채운다. 가격은 무손실 정밀도를 위해 {@link BigDecimal}.
 */
public final class TossCandle {

    private final LocalDate date;
    private final BigDecimal open;
    private final BigDecimal high;
    private final BigDecimal low;
    private final BigDecimal close;
    private final Long volume;

    public TossCandle(LocalDate date, BigDecimal open, BigDecimal high,
                      BigDecimal low, BigDecimal close, Long volume) {
        this.date = date;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    public LocalDate getDate() { return date; }
    public BigDecimal getOpen() { return open; }
    public BigDecimal getHigh() { return high; }
    public BigDecimal getLow() { return low; }
    public BigDecimal getClose() { return close; }
    public Long getVolume() { return volume; }
}
