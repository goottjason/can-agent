package com.canagent.repository;

import com.canagent.domain.trading.Trade;
import com.canagent.domain.trading.TradeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TradeRepository extends JpaRepository<Trade, Long> {

    List<Trade> findByStockIdAndTradeTypeOrderByTradeDateTimeDesc(Long stockId, TradeType tradeType);

    List<Trade> findAllByOrderByTradeDateTimeDesc();

    List<Trade> findByTradeDateTimeAfterOrderByTradeDateTimeDesc(LocalDateTime dateTime);

    @Query("SELECT COALESCE(SUM(t.totalAmount), 0) FROM Trade t WHERE t.tradeType = :type AND t.tradeDateTime >= :since")
    BigDecimal sumTotalAmountByTypeAndSince(@Param("type") TradeType type, @Param("since") LocalDateTime since);

    long countByTradeDateTimeAfter(LocalDateTime dateTime);

    long countByTradeType(TradeType tradeType);

    @Query("SELECT COALESCE(SUM(t.totalAmount), 0) FROM Trade t WHERE t.tradeType = :type")
    BigDecimal sumTotalAmountByTradeType(@Param("type") TradeType type);

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.tradeType = com.canagent.domain.trading.TradeType.SELL AND t.profitRate IS NOT NULL AND t.profitRate > 0")
    long countWinningSellTrades();
}
