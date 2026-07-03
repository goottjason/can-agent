package com.canagent.repository;

import com.canagent.domain.analysis.AnalysisScore;
import com.canagent.domain.stock.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AnalysisScoreRepository extends JpaRepository<AnalysisScore, Long> {

    Optional<AnalysisScore> findByStockIdAndAnalysisDate(Long stockId, LocalDate analysisDate);

    List<AnalysisScore> findByAnalysisDate(LocalDate analysisDate);

    List<AnalysisScore> findByAnalysisDateAndTotalScoreGreaterThanEqual(LocalDate analysisDate, int minScore);

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO analysis_scores " +
            "(stock_id, analysis_date, source, can_slim_score, quarterly_score, annual_score, " +
            "supply_demand_score, market_direction_score, industry_leader_score, institutional_score, " +
            "cup_score, cup_pattern, total_score, last_analyzed_at, created_at) " +
            "VALUES (:stockId, :analysisDate, :source, :canSlimScore, :quarterlyScore, :annualScore, " +
            ":supplyDemandScore, :marketDirectionScore, :industryLeaderScore, :institutionalScore, " +
            ":cupScore, :cupPattern, :totalScore, NOW(), NOW()) " +
            "ON CONFLICT (stock_id, analysis_date) DO UPDATE SET " +
            "can_slim_score = EXCLUDED.can_slim_score, " +
            "quarterly_score = EXCLUDED.quarterly_score, " +
            "annual_score = EXCLUDED.annual_score, " +
            "supply_demand_score = EXCLUDED.supply_demand_score, " +
            "market_direction_score = EXCLUDED.market_direction_score, " +
            "industry_leader_score = EXCLUDED.industry_leader_score, " +
            "institutional_score = EXCLUDED.institutional_score, " +
            "cup_score = EXCLUDED.cup_score, " +
            "cup_pattern = EXCLUDED.cup_pattern, " +
            "total_score = EXCLUDED.total_score, " +
            "source = EXCLUDED.source, " +
            "last_analyzed_at = NOW()",
            nativeQuery = true)
    int upsertScore(@Param("stockId") Long stockId,
                    @Param("analysisDate") LocalDate analysisDate,
                    @Param("source") String source,
                    @Param("canSlimScore") int canSlimScore,
                    @Param("quarterlyScore") int quarterlyScore,
                    @Param("annualScore") int annualScore,
                    @Param("supplyDemandScore") int supplyDemandScore,
                    @Param("marketDirectionScore") int marketDirectionScore,
                    @Param("industryLeaderScore") int industryLeaderScore,
                    @Param("institutionalScore") int institutionalScore,
                    @Param("cupScore") int cupScore,
                    @Param("cupPattern") String cupPattern,
                    @Param("totalScore") int totalScore);
}
