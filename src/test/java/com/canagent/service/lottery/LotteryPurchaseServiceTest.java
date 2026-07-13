package com.canagent.service.lottery;

import com.canagent.config.LotteryConfig;
import com.canagent.domain.lottery.GameType;
import com.canagent.domain.lottery.LotteryTicket;
import com.canagent.port.LotterySidecarPort;
import com.canagent.port.dto.PurchasedTicket;
import com.canagent.port.dto.SidecarResult;
import com.canagent.repository.LotteryTicketRepository;
import com.canagent.service.notification.NotificationServiceRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("복권 주간 구매 서비스 단위테스트")
class LotteryPurchaseServiceTest {

    private LotteryTicketRepository repo;
    private NotificationServiceRouter router;
    private LotteryConfig config;
    private final List<String> messages = new ArrayList<>();
    // 2026-07-14(화) 09:00 KST = 2026-07-14T00:00:00Z
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneId.of("UTC"));

    @BeforeEach
    void setUp() {
        repo = mock(LotteryTicketRepository.class);
        router = mock(NotificationServiceRouter.class);
        config = new LotteryConfig();
        config.setBalanceThreshold(3000);
        messages.clear();
        doAnswer(inv -> { messages.add(inv.getArgument(0)); return null; })
                .when(router).sendText(anyString());
    }

    @Test
    @DisplayName("이번 주 미구매 게임만 구매하고 티켓을 저장·알림한다")
    void buysPendingAndNotifies() {
        when(repo.existsByGameTypeAndPurchasedAtAfter(any(), any())).thenReturn(false);
        LotterySidecarPort port = games -> new SidecarResult(true, 8000, List.of(
                new PurchasedTicket(GameType.LOTTO645, 1181, "3,7,12,25,33,41", 1000),
                new PurchasedTicket(GameType.WIN720, 240, "3:123456", 1000)), List.of());
        LotteryPurchaseService svc = new LotteryPurchaseService(port, repo, router, config, clock);

        svc.buyWeekly();

        verify(repo, times(2)).save(any(LotteryTicket.class));
        assertThat(messages).anyMatch(m -> m.contains("구매"));
        assertThat(messages).noneMatch(m -> m.contains("예치금 부족"));
    }

    @Test
    @DisplayName("두 게임 모두 이번 주 구매됨이면 스킵한다")
    void skipsWhenAllPurchased() {
        when(repo.existsByGameTypeAndPurchasedAtAfter(any(), any())).thenReturn(true);
        List<GameType> requested = new ArrayList<>();
        LotterySidecarPort port = games -> { requested.addAll(games); return new SidecarResult(true, 8000, List.of(), List.of()); };
        LotteryPurchaseService svc = new LotteryPurchaseService(port, repo, router, config, clock);

        svc.buyWeekly();

        assertThat(requested).isEmpty();
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("구매 후 잔액이 임계 미만이면 예치금 부족을 알린다")
    void alertsLowBalance() {
        when(repo.existsByGameTypeAndPurchasedAtAfter(any(), any())).thenReturn(false);
        LotterySidecarPort port = games -> new SidecarResult(true, 2000, List.of(
                new PurchasedTicket(GameType.LOTTO645, 1181, "3,7,12,25,33,41", 1000)), List.of());
        LotteryPurchaseService svc = new LotteryPurchaseService(port, repo, router, config, clock);

        svc.buyWeekly();

        assertThat(messages).anyMatch(m -> m.contains("예치금 부족"));
    }
}
