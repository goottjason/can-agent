package com.canagent.repository;

import com.canagent.domain.stock.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockRepository extends JpaRepository<Stock, Long> {

    Optional<Stock> findByCode(String code);

    List<Stock> findByActiveTrue();

    List<Stock> findByMarketAndActiveTrue(String market);

    List<Stock> findBySectorAndActiveTrue(String sector);

    Optional<Stock> findByCodeAndActiveTrue(String code);

    List<Stock> findByNameContainingIgnoreCase(String name);

    List<Stock> findByCodeContainingOrNameContaining(String code, String name);

    List<Stock> findByCodeContainingOrNameContainingAndActiveTrue(String code, String name);

    boolean existsByCode(String code);

    long countByActiveTrue();

    long countByMarketAndActiveTrue(String market);
}
