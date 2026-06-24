package com.canagent.domain.analysis;

import com.canagent.domain.stock.Stock;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "cup_and_handle_patterns")
public class CupAndHandlePattern {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id", nullable = false)
    private Stock stock;

    @Column(nullable = false)
    private LocalDate cupStartDate;

    @Column(nullable = false)
    private LocalDate cupEndDate;

    @Column(nullable = false)
    private BigDecimal cupDepthPercent;

    @Column(nullable = false)
    private BigDecimal cupHighPrice;

    @Column(nullable = false)
    private BigDecimal cupLowPrice;

    private LocalDate handleStartDate;

    private LocalDate handleEndDate;

    private BigDecimal handleDepthPercent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PatternStatus status;

    private LocalDate breakoutDate;

    private BigDecimal breakoutPrice;

    private BigDecimal targetPrice;

    protected CupAndHandlePattern() {}

    public CupAndHandlePattern(Stock stock, LocalDate cupStartDate, LocalDate cupEndDate,
                               BigDecimal cupDepthPercent, BigDecimal cupHighPrice,
                               BigDecimal cupLowPrice) {
        this.stock = stock;
        this.cupStartDate = cupStartDate;
        this.cupEndDate = cupEndDate;
        this.cupDepthPercent = cupDepthPercent;
        this.cupHighPrice = cupHighPrice;
        this.cupLowPrice = cupLowPrice;
        this.status = PatternStatus.CUP_FORMING;
    }

    public Long getId() { return id; }
    public Stock getStock() { return stock; }
    public LocalDate getCupStartDate() { return cupStartDate; }
    public LocalDate getCupEndDate() { return cupEndDate; }
    public BigDecimal getCupDepthPercent() { return cupDepthPercent; }
    public BigDecimal getCupHighPrice() { return cupHighPrice; }
    public BigDecimal getCupLowPrice() { return cupLowPrice; }
    public LocalDate getHandleStartDate() { return handleStartDate; }
    public LocalDate getHandleEndDate() { return handleEndDate; }
    public BigDecimal getHandleDepthPercent() { return handleDepthPercent; }
    public PatternStatus getStatus() { return status; }
    public LocalDate getBreakoutDate() { return breakoutDate; }
    public BigDecimal getBreakoutPrice() { return breakoutPrice; }
    public BigDecimal getTargetPrice() { return targetPrice; }

    public void startHandle(LocalDate handleStartDate) {
        this.handleStartDate = handleStartDate;
        this.status = PatternStatus.HANDLE_FORMING;
    }

    public void completeHandle(LocalDate handleEndDate, BigDecimal handleDepthPercent) {
        this.handleEndDate = handleEndDate;
        this.handleDepthPercent = handleDepthPercent;
        this.status = PatternStatus.HANDLE_COMPLETE;
    }

    public void breakout(LocalDate breakoutDate, BigDecimal breakoutPrice) {
        this.breakoutDate = breakoutDate;
        this.breakoutPrice = breakoutPrice;
        this.targetPrice = cupHighPrice.add(cupHighPrice.subtract(cupLowPrice));
        this.status = PatternStatus.BREAKOUT;
    }

    public void invalidate() {
        this.status = PatternStatus.INVALIDATED;
    }
}
