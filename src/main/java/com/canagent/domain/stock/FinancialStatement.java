package com.canagent.domain.stock;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "financial_statements", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"stock_id", "fiscal_year", "fiscal_quarter"})
})
public class FinancialStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id", nullable = false)
    private Stock stock;

    @Column(nullable = false)
    private Integer fiscalYear;

    // P3: 연간행(ANNUAL) 수용을 위해 nullable 허용으로 완화.
    // 기존 KR 데이터는 값이 채워져 있어 무영향.
    private Integer fiscalQuarter;

    // P3 additive: SEC 보고 유형(QUARTER=10-Q / ANNUAL=10-K). KR 기존행은 null.
    // 분석기의 fiscalQuarter 기반 판정은 유지(periodType 전환은 P4 이관).
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private FiscalPeriodType periodType;

    // P3 additive: 재무 통화(예: USD, KRW). KR 기존행은 null.
    @Column(length = 10)
    private String currency;

    @Column(nullable = false)
    private LocalDate reportDate;

    @Column(precision = 19, scale = 2)
    private BigDecimal revenue;

    @Column(precision = 19, scale = 2)
    private BigDecimal operatingIncome;

    @Column(precision = 19, scale = 2)
    private BigDecimal netIncome;

    // P3: 미국 EPS 소수 정밀 대응 scale 2→4 (precision 19 유지).
    @Column(precision = 19, scale = 4)
    private BigDecimal eps;

    @Column(precision = 19, scale = 2)
    private BigDecimal roe;

    // P3: 자본잠식·고부채 오버플로 방지 precision 5→12 (scale 2 유지).
    @Column(precision = 12, scale = 2)
    private BigDecimal debtRatio;

    protected FinancialStatement() {}

    public FinancialStatement(Stock stock, Integer fiscalYear, Integer fiscalQuarter,
                              LocalDate reportDate) {
        this.stock = stock;
        this.fiscalYear = fiscalYear;
        this.fiscalQuarter = fiscalQuarter;
        this.reportDate = reportDate;
    }

    public Long getId() { return id; }
    public Stock getStock() { return stock; }
    public Integer getFiscalYear() { return fiscalYear; }
    public Integer getFiscalQuarter() { return fiscalQuarter; }
    public LocalDate getReportDate() { return reportDate; }
    public BigDecimal getRevenue() { return revenue; }
    public BigDecimal getOperatingIncome() { return operatingIncome; }
    public BigDecimal getNetIncome() { return netIncome; }
    public BigDecimal getEps() { return eps; }
    public BigDecimal getRoe() { return roe; }
    public BigDecimal getDebtRatio() { return debtRatio; }

    // --- P3 additive 접근자 ---
    public FiscalPeriodType getPeriodType() { return periodType; }
    public String getCurrency() { return currency; }

    public void setPeriodType(FiscalPeriodType periodType) { this.periodType = periodType; }
    public void setCurrency(String currency) { this.currency = currency; }

    public void updateFinancials(BigDecimal revenue, BigDecimal operatingIncome,
                                 BigDecimal netIncome, BigDecimal eps,
                                 BigDecimal roe, BigDecimal debtRatio) {
        this.revenue = revenue;
        this.operatingIncome = operatingIncome;
        this.netIncome = netIncome;
        this.eps = eps;
        this.roe = roe;
        this.debtRatio = debtRatio;
    }
}
