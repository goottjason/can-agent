package com.canagent.service.lottery;

import com.canagent.config.LotteryConfig;
import com.canagent.domain.lottery.GameType;
import com.canagent.domain.lottery.LotteryTicket;
import com.canagent.port.LotterySidecarPort;
import com.canagent.port.dto.PurchasedTicket;
import com.canagent.port.dto.SidecarError;
import com.canagent.port.dto.SidecarResult;
import com.canagent.repository.LotteryTicketRepository;
import com.canagent.service.notification.NotificationServiceRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.List;

@Service
public class LotteryPurchaseService {

    private static final Logger log = LoggerFactory.getLogger(LotteryPurchaseService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final LotterySidecarPort port;
    private final LotteryTicketRepository repository;
    private final NotificationServiceRouter router;
    private final LotteryConfig config;
    private final Clock clock;

    public LotteryPurchaseService(LotterySidecarPort port, LotteryTicketRepository repository,
                                  NotificationServiceRouter router, LotteryConfig config, Clock clock) {
        this.port = port;
        this.repository = repository;
        this.router = router;
        this.config = config;
        this.clock = clock;
    }

    public void buyWeekly() {
        ZonedDateTime nowKst = ZonedDateTime.now(clock).withZoneSameInstant(KST);
        LocalDateTime weekStart = nowKst.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();

        List<GameType> pending = Arrays.stream(GameType.values())
                .filter(g -> !repository.existsByGameTypeAndPurchasedAtAfter(g, weekStart))
                .toList();

        if (pending.isEmpty()) {
            log.info("이번 주 복권 이미 구매됨 — 스킵");
            return;
        }

        log.info("복권 구매 요청: {}", pending);
        SidecarResult result = port.purchaseWeekly(pending);

        LocalDateTime purchasedAt = nowKst.toLocalDateTime();
        for (PurchasedTicket t : result.tickets()) {
            repository.save(new LotteryTicket(t.gameType(), t.roundNo(), t.numbers(), t.amount(), purchasedAt));
        }

        if (!result.tickets().isEmpty()) {
            router.sendText(formatPurchase(result));
        }
        for (SidecarError e : result.errors()) {
            router.sendText("❌ 복권 구매 실패: " + e.gameType() + " — " + e.reason());
        }
        if (result.balanceAfter() < config.getBalanceThreshold()) {
            router.sendText("⚠️ 예치금 부족: 현재 " + result.balanceAfter() + "원 (임계 "
                    + config.getBalanceThreshold() + "원). 충전이 필요합니다.");
        }
    }

    private String formatPurchase(SidecarResult result) {
        StringBuilder sb = new StringBuilder("🎫 복권 구매 완료\n");
        for (PurchasedTicket t : result.tickets()) {
            String name = t.gameType() == GameType.LOTTO645 ? "로또6/45" : "연금복권720+";
            sb.append("• ").append(name).append(" ").append(t.roundNo()).append("회 [")
              .append(t.numbers()).append("] ").append(t.amount()).append("원\n");
        }
        sb.append("• 잔액: ").append(result.balanceAfter()).append("원");
        return sb.toString();
    }
}
