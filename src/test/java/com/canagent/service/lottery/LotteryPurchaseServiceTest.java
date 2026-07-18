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
    // 2026-07-14T00:00:00Z = KST 2026-07-14 09:00 (화요일) → weekStart = 2026-07-13 00:00 KST
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneId.of("UTC"));

    /** 단일 결과를 반환하는 Fake 사이드카. purchaseWeekly/getBalance 모두 구현해 인터페이스 계약 준수. */
    private static class FakeSidecar implements LotterySidecarPort {
        private final SidecarResult result;
        FakeSidecar(SidecarResult result) { this.result = result; }
        @Override public SidecarResult purchaseWeekly(List<GameType> games) { return result; }
        @Override public int getBalance() { throw new UnsupportedOperationException(); }
        @Override public com.canagent.port.dto.SidecarResults checkResults() { throw new UnsupportedOperationException(); }
    }

    /** 요청 게임 목록을 기록하는 추적용 Fake 사이드카. */
    private static class TrackingSidecar implements LotterySidecarPort {
        final List<GameType> requested = new ArrayList<>();
        @Override public SidecarResult purchaseWeekly(List<GameType> games) {
            requested.addAll(games);
            return new SidecarResult(true, 8000, List.of(), List.of());
        }
        @Override public int getBalance() { throw new UnsupportedOperationException(); }
        @Override public com.canagent.port.dto.SidecarResults checkResults() { throw new UnsupportedOperationException(); }
    }

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
        LotterySidecarPort port = new FakeSidecar(new SidecarResult(true, 8000, List.of(
                new PurchasedTicket(GameType.LOTTO645, 1181, "3,7,12,25,33,41", 1000),
                new PurchasedTicket(GameType.WIN720, 240, "3:123456", 1000)), List.of()));
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
        TrackingSidecar port = new TrackingSidecar();
        LotteryPurchaseService svc = new LotteryPurchaseService(port, repo, router, config, clock);

        svc.buyWeekly();

        assertThat(port.requested).isEmpty();
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("구매 후 잔액이 임계 미만이면 예치금 부족을 알린다")
    void alertsLowBalance() {
        when(repo.existsByGameTypeAndPurchasedAtAfter(any(), any())).thenReturn(false);
        LotterySidecarPort port = new FakeSidecar(new SidecarResult(true, 2000, List.of(
                new PurchasedTicket(GameType.LOTTO645, 1181, "3,7,12,25,33,41", 1000)), List.of()));
        LotteryPurchaseService svc = new LotteryPurchaseService(port, repo, router, config, clock);

        svc.buyWeekly();

        assertThat(messages).anyMatch(m -> m.contains("예치금 부족"));
    }

    @Test
    @DisplayName("SidecarError마다 오류 알림을 개별 발송한다")
    void alertsPerSidecarError() {
        when(repo.existsByGameTypeAndPurchasedAtAfter(any(), any())).thenReturn(false);
        LotterySidecarPort port = new FakeSidecar(new SidecarResult(true, 5000, List.of(),
                List.of(new SidecarError("WIN720", "연결 실패"))));
        LotteryPurchaseService svc = new LotteryPurchaseService(port, repo, router, config, clock);

        svc.buyWeekly();

        assertThat(messages).anyMatch(m -> m.contains("복권 구매 실패") && m.contains("WIN720"));
    }

    @Test
    @DisplayName("games 설정이 로또만이면 로또만 사이드카에 요청한다")
    void restrictsToConfiguredGames() {
        config.setGames(List.of(GameType.LOTTO645));
        when(repo.existsByGameTypeAndPurchasedAtAfter(any(), any())).thenReturn(false);
        TrackingSidecar port = new TrackingSidecar();
        LotteryPurchaseService svc = new LotteryPurchaseService(port, repo, router, config, clock);

        svc.buyWeekly();

        assertThat(port.requested).containsExactly(GameType.LOTTO645);
    }
}
