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

    @Column(nullable = false)
    private Integer fiscalQuarter;

    @Column(nullable = false)
    private LocalDate reportDate;

    @Column(precision = 19, scale = 2)
    private BigDecimal revenue;

    @Column(precision = 19, scale = 2)
    private BigDecimal operatingIncome;

    @Column(precision = 19, scale = 2)
    private BigDecimal netIncome;

    @Column(precision = 19, scale = 2)
    private BigDecimal eps;

    @Column(precision = 19, scale = 2)
    private BigDecimal roe;

    @Column(precision = 5, scale = 2)
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
