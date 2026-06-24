package com.canagent.repository;

import com.canagent.domain.trading.Trade;
import com.canagent.domain.trading.TradeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TradeRepository extends JpaRepository<Trade, Long> {

    List<Trade> findByStockIdAndTradeTypeOrderByTradeDateTimeDesc(Long stockId, TradeType tradeType);

    List<Trade> findAllByOrderByTradeDateTimeDesc();
}
