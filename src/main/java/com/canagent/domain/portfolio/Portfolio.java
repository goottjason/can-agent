package com.canagent.domain.portfolio;

import com.canagent.domain.stock.Stock;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "portfolios")
public class Portfolio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id", nullable = false)
    private Stock stock;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal averageBuyPrice;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalBuyAmount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal currentPrice;

    @Column(precision = 19, scale = 2)
    private BigDecimal profitAmount;

    @Column(precision = 5, scale = 2)
    private BigDecimal profitRate;

    @Column(nullable = false)
    private boolean active;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    protected Portfolio() {}

    public Portfolio(Stock stock, BigDecimal quantity, BigDecimal averageBuyPrice) {
        this.stock = stock;
        this.quantity = quantity;
        this.averageBuyPrice = averageBuyPrice;
        this.totalBuyAmount = averageBuyPrice.multiply(quantity);
        this.currentPrice = averageBuyPrice;
        this.profitAmount = BigDecimal.ZERO;
        this.profitRate = BigDecimal.ZERO;
        this.active = true;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Stock getStock() { return stock; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getAverageBuyPrice() { return averageBuyPrice; }
    public BigDecimal getTotalBuyAmount() { return totalBuyAmount; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public BigDecimal getProfitAmount() { return profitAmount; }
    public BigDecimal getProfitRate() { return profitRate; }
    public boolean isActive() { return active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void updateCurrentPrice(BigDecimal currentPrice) {
        this.currentPrice = currentPrice;
        this.profitAmount = currentPrice.subtract(averageBuyPrice).multiply(quantity);
        this.profitRate = currentPrice.subtract(averageBuyPrice)
                .divide(averageBuyPrice, 4, BigDecimal.ROUND_HALF_UP)
                .multiply(new BigDecimal("100"));
        this.updatedAt = LocalDateTime.now();
    }

    public void addQuantity(BigDecimal quantity, BigDecimal buyPrice) {
        BigDecimal totalAmount = this.averageBuyPrice.multiply(this.quantity)
                .add(buyPrice.multiply(quantity));
        this.quantity = this.quantity.add(quantity);
        this.averageBuyPrice = totalAmount.divide(this.quantity, 2, BigDecimal.ROUND_HALF_UP);
        this.totalBuyAmount = this.averageBuyPrice.multiply(this.quantity);
        this.updatedAt = LocalDateTime.now();
    }

    public void reduceQuantity(BigDecimal quantity) {
        this.quantity = this.quantity.subtract(quantity);
        this.totalBuyAmount = this.averageBuyPrice.multiply(this.quantity);
        this.updatedAt = LocalDateTime.now();
        if (this.quantity.compareTo(BigDecimal.ZERO) <= 0) {
            this.active = false;
        }
    }

    /** 청산 처리 — 하드삭제 대신 active=false로 비활성화(실계좌 동기화 등). */
    public void deactivate() {
        this.active = false;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 실계좌 보유(소스오브트루스)로 수량·평단을 덮어쓴다(실계좌 동기화).
     * 부분 청산/추가매수 누적이 아닌 브로커 값 그대로의 재조정이다.
     */
    public void reconcileTo(BigDecimal quantity, BigDecimal averageBuyPrice) {
        this.quantity = quantity;
        this.averageBuyPrice = averageBuyPrice;
        this.totalBuyAmount = averageBuyPrice.multiply(quantity);
        this.updatedAt = LocalDateTime.now();
    }
}
