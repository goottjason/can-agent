package com.canagent.domain.analysis;

import com.canagent.domain.stock.Stock;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "analysis_scores", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"stock_id", "analysis_date"})
})
public class AnalysisScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id", nullable = false)
    private Stock stock;

    @Column(name = "analysis_date", nullable = false)
    private LocalDate analysisDate;

    @Column(nullable = false, length = 20)
    private String source;

    @Column(name = "can_slim_score")
    private int canSlimScore;

    @Column(name = "quarterly_score")
    private int quarterlyScore;

    @Column(name = "annual_score")
    private int annualScore;

    @Column(name = "supply_demand_score")
    private int supplyDemandScore;

    @Column(name = "market_direction_score")
    private int marketDirectionScore;

    @Column(name = "industry_leader_score")
    private int industryLeaderScore;

    @Column(name = "institutional_score")
    private int institutionalScore;

    @Column(name = "cup_score")
    private int cupScore;

    @Column(name = "cup_pattern", length = 20)
    private String cupPattern;

    @Column(name = "total_score")
    private int totalScore;

    @Column(name = "last_analyzed_at")
    private LocalDateTime lastAnalyzedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected AnalysisScore() {}

    public AnalysisScore(Stock stock, LocalDate analysisDate, String source,
                         int canSlimScore, int quarterlyScore, int annualScore,
                         int supplyDemandScore, int marketDirectionScore,
                         int industryLeaderScore, int institutionalScore,
                         int cupScore, String cupPattern, int totalScore) {
        this.stock = stock;
        this.analysisDate = analysisDate;
        this.source = source;
        this.canSlimScore = canSlimScore;
        this.quarterlyScore = quarterlyScore;
        this.annualScore = annualScore;
        this.supplyDemandScore = supplyDemandScore;
        this.marketDirectionScore = marketDirectionScore;
        this.industryLeaderScore = industryLeaderScore;
        this.institutionalScore = institutionalScore;
        this.cupScore = cupScore;
        this.cupPattern = cupPattern;
        this.totalScore = totalScore;
        this.lastAnalyzedAt = LocalDateTime.now();
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Stock getStock() { return stock; }
    public LocalDate getAnalysisDate() { return analysisDate; }
    public String getSource() { return source; }
    public int getCanSlimScore() { return canSlimScore; }
    public int getQuarterlyScore() { return quarterlyScore; }
    public int getAnnualScore() { return annualScore; }
    public int getSupplyDemandScore() { return supplyDemandScore; }
    public int getMarketDirectionScore() { return marketDirectionScore; }
    public int getIndustryLeaderScore() { return industryLeaderScore; }
    public int getInstitutionalScore() { return institutionalScore; }
    public int getCupScore() { return cupScore; }
    public String getCupPattern() { return cupPattern; }
    public int getTotalScore() { return totalScore; }
    public LocalDateTime getLastAnalyzedAt() { return lastAnalyzedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void updateScores(int canSlimScore, int quarterlyScore, int annualScore,
                             int supplyDemandScore, int marketDirectionScore,
                             int industryLeaderScore, int institutionalScore,
                             int cupScore, String cupPattern, int totalScore, String source) {
        this.canSlimScore = canSlimScore;
        this.quarterlyScore = quarterlyScore;
        this.annualScore = annualScore;
        this.supplyDemandScore = supplyDemandScore;
        this.marketDirectionScore = marketDirectionScore;
        this.industryLeaderScore = industryLeaderScore;
        this.institutionalScore = institutionalScore;
        this.cupScore = cupScore;
        this.cupPattern = cupPattern;
        this.totalScore = totalScore;
        this.source = source;
        this.lastAnalyzedAt = LocalDateTime.now();
    }
}
