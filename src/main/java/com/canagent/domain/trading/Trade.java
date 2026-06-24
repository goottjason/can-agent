package com.canagent.domain.trading;

import com.canagent.domain.stock.Stock;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "trades")
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id", nullable = false)
    private Stock stock;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TradeType tradeType;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(precision = 5, scale = 2)
    private BigDecimal commission;

    @Column(precision = 5, scale = 2)
    private BigDecimal profitRate;

    private String reason;

    @Column(nullable = false)
    private LocalDateTime tradeDateTime;

    protected Trade() {}

    public Trade(Stock stock, TradeType tradeType, int quantity,
                 BigDecimal price, String reason) {
        this.stock = stock;
        this.tradeType = tradeType;
        this.quantity = quantity;
        this.price = price;
        this.totalAmount = price.multiply(new BigDecimal(quantity));
        this.reason = reason;
        this.tradeDateTime = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Stock getStock() { return stock; }
    public TradeType getTradeType() { return tradeType; }
    public Integer getQuantity() { return quantity; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public BigDecimal getCommission() { return commission; }
    public BigDecimal getProfitRate() { return profitRate; }
    public String getReason() { return reason; }
    public LocalDateTime getTradeDateTime() { return tradeDateTime; }

    public void setCommission(BigDecimal commission) {
        this.commission = commission;
    }

    public void setProfitRate(BigDecimal profitRate) {
        this.profitRate = profitRate;
    }
}
