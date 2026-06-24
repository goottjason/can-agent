package com.canagent.repository;

import com.canagent.domain.portfolio.Portfolio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PortfolioRepository extends JpaRepository<Portfolio, Long> {

    List<Portfolio> findByActiveTrue();

    Optional<Portfolio> findByStockIdAndActiveTrue(Long stockId);
}
