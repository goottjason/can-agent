package com.canagent.domain.stock;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "stock_prices", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"stock_id", "date"})
})
public class StockPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id", nullable = false)
    private Stock stock;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal open;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal high;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal low;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal close;

    @Column(nullable = false)
    private Long volume;

    @Column(precision = 19, scale = 2)
    private BigDecimal changeRate;

    protected StockPrice() {}

    public StockPrice(Stock stock, LocalDate date, BigDecimal open,
                      BigDecimal high, BigDecimal low, BigDecimal close,
                      Long volume) {
        this.stock = stock;
        this.date = date;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    public Long getId() { return id; }
    public Stock getStock() { return stock; }
    public LocalDate getDate() { return date; }
    public BigDecimal getOpen() { return open; }
    public BigDecimal getHigh() { return high; }
    public BigDecimal getLow() { return low; }
    public BigDecimal getClose() { return close; }
    public Long getVolume() { return volume; }
    public BigDecimal getChangeRate() { return changeRate; }

    public void setChangeRate(BigDecimal changeRate) {
        this.changeRate = changeRate;
    }

    /**
     * 장중 스팟 현재가를 반영한다: close를 최신 스팟으로 갱신하고 high/low를 확장한다.
     * open·volume은 보존한다(정식 캔들/시가 불변). null 스팟은 무시한다.
     */
    public void applyIntradaySpot(BigDecimal price) {
        if (price == null) {
            return;
        }
        this.close = price;
        if (this.high == null || price.compareTo(this.high) > 0) {
            this.high = price;
        }
        if (this.low == null || price.compareTo(this.low) < 0) {
            this.low = price;
        }
    }

    public BigDecimal getRange() {
        if (high == null || low == null || high.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return high.subtract(low).divide(high, 4, BigDecimal.ROUND_HALF_UP)
                .multiply(new BigDecimal("100"));
    }
}
