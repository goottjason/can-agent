package com.canagent.repository;

import com.canagent.domain.portfolio.Portfolio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PortfolioRepository extends JpaRepository<Portfolio, Long> {

    List<Portfolio> findByActiveTrue();

    Optional<Portfolio> findByStockIdAndActiveTrue(Long stockId);

    long countByActiveTrue();

    @Query("SELECT COALESCE(SUM(p.totalBuyAmount), 0) FROM Portfolio p WHERE p.active = true")
    BigDecimal sumTotalBuyAmountByActiveTrue();

    @Query("SELECT COALESCE(SUM(p.currentPrice * p.quantity), 0) FROM Portfolio p WHERE p.active = true")
    BigDecimal sumTotalCurrentValueByActiveTrue();

    @Query("SELECT COALESCE(SUM(p.profitAmount), 0) FROM Portfolio p WHERE p.active = true")
    BigDecimal sumTotalProfitAmountByActiveTrue();

    @Query("SELECT p FROM Portfolio p WHERE p.active = true AND p.profitRate < :threshold")
    List<Portfolio> findLossPortfolios(@Param("threshold") BigDecimal threshold);

    @Query("SELECT p FROM Portfolio p WHERE p.active = true ORDER BY p.profitRate DESC")
    List<Portfolio> findTopProfitPortfolios();
}
