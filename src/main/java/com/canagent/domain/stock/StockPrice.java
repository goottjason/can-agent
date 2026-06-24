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

    public BigDecimal getRange() {
        if (high == null || low == null || high.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return high.subtract(low).divide(high, 4, BigDecimal.ROUND_HALF_UP)
                .multiply(new BigDecimal("100"));
    }
}
