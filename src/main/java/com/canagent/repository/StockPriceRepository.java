package com.canagent.repository;

import com.canagent.domain.stock.StockPrice;
import org.springframework.data.domain.Pageable;
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

    // 최신 완료 거래일(오늘 미만) — 동기화 지연·휴장일에도 견고한 기준일 산출용
    @Query("SELECT MAX(sp.date) FROM StockPrice sp WHERE sp.date < :today")
    Optional<LocalDate> findLatestTradeDateBefore(@Param("today") LocalDate today);

    // JOIN FETCH로 Stock을 즉시 로딩 — @Scheduled 스레드(세션 없음)의 LazyInitializationException 방지
    @Query("SELECT sp FROM StockPrice sp JOIN FETCH sp.stock " +
            "WHERE sp.date = :date AND sp.volume > 0 " +
            "ORDER BY sp.volume DESC")
    List<StockPrice> findTopByVolumeOnDate(@Param("date") LocalDate date, Pageable pageable);

    @Query("SELECT sp FROM StockPrice sp JOIN FETCH sp.stock " +
            "WHERE sp.date = :date AND sp.changeRate IS NOT NULL " +
            "ORDER BY ABS(sp.changeRate) DESC")
    List<StockPrice> findTopByChangeRateOnDate(@Param("date") LocalDate date, Pageable pageable);
}
