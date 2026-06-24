package com.canagent.repository;

import com.canagent.domain.stock.StockPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockPriceRepository extends JpaRepository<StockPrice, Long> {

    List<StockPrice> findByStockIdOrderByDateDesc(Long stockId);

    List<StockPrice> findByStockIdAndDateBetweenOrderByDateAsc(
            Long stockId, LocalDate startDate, LocalDate endDate);

    Optional<StockPrice> findTopByStockIdOrderByDateDesc(Long stockId);
}
