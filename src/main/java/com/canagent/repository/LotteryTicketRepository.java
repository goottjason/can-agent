package com.canagent.repository;

import com.canagent.domain.lottery.GameType;
import com.canagent.domain.lottery.LotteryTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface LotteryTicketRepository extends JpaRepository<LotteryTicket, Long> {

    boolean existsByGameTypeAndPurchasedAtAfter(GameType gameType, LocalDateTime after);

    List<LotteryTicket> findByGameTypeAndResultCheckedFalse(GameType gameType);
}
