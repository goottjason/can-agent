package com.canagent.repository;

import com.canagent.domain.stock.StockPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    @Query(value = "SELECT sp.* FROM stock_prices sp " +
            "WHERE sp.date = :date AND sp.volume > 0 " +
            "ORDER BY sp.volume DESC LIMIT :limit", nativeQuery = true)
    List<StockPrice> findTopByVolumeOnDate(@Param("date") LocalDate date, @Param("limit") int limit);

    @Query(value = "SELECT sp.* FROM stock_prices sp " +
            "WHERE sp.date = :date AND sp.change_rate IS NOT NULL " +
            "ORDER BY ABS(sp.change_rate) DESC LIMIT :limit", nativeQuery = true)
    List<StockPrice> findTopByChangeRateOnDate(@Param("date") LocalDate date, @Param("limit") int limit);
}
