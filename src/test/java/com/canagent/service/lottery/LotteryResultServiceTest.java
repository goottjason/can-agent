package com.canagent.service.lottery;

import com.canagent.domain.lottery.GameType;
import com.canagent.domain.lottery.LotteryTicket;
import com.canagent.port.LotterySidecarPort;
import com.canagent.port.dto.SidecarError;
import com.canagent.port.dto.SidecarResult;
import com.canagent.port.dto.SidecarResults;
import com.canagent.port.dto.TicketResult;
import com.canagent.repository.LotteryTicketRepository;
import com.canagent.service.notification.NotificationServiceRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("복권 당첨확인 서비스 단위테스트")
class LotteryResultServiceTest {

    private LotteryTicketRepository repo;
    private NotificationServiceRouter router;
    private final List<String> messages = new ArrayList<>();

    /** 고정된 SidecarResults를 반환하는 Fake 사이드카. */
    private static class FakeSidecar implements LotterySidecarPort {
        private final SidecarResults results;
        FakeSidecar(SidecarResults results) { this.results = results; }
        @Override public SidecarResult purchaseWeekly(List<GameType> games) { throw new UnsupportedOperationException(); }
        @Override public int getBalance() { throw new UnsupportedOperationException(); }
        @Override public SidecarResults checkResults() { return results; }
    }

    private LotteryResultService svc(SidecarResults results) {
        return new LotteryResultService(repo, new FakeSidecar(results), router);
    }

    @BeforeEach
    void setUp() {
        repo = mock(LotteryTicketRepository.class);
        router = mock(NotificationServiceRouter.class);
        messages.clear();
        doAnswer(inv -> { messages.add(inv.getArgument(0)); return null; }).when(router).sendText(anyString());
    }

    @Test
    @DisplayName("WIN 티켓: 당첨 반영(rank>0)·winner·저장·당첨 요약 알림")
    void checkLottoWin() {
        LotteryTicket t = new LotteryTicket(GameType.LOTTO645, 1233, "3,7,12,25,33,41", 1000, LocalDateTime.now());
        when(repo.findByGameTypeAndResultCheckedFalse(GameType.LOTTO645)).thenReturn(List.of(t));
        SidecarResults sr = new SidecarResults(true, List.of(
                new TicketResult(GameType.LOTTO645, 1233, "WIN", 5000, "2026-07-18")), List.of());

        svc(sr).checkLotto();

        assertThat(t.getRank()).isEqualTo(1);
        assertThat(t.isWinner()).isTrue();
        assertThat(t.isResultChecked()).isTrue();
        assertThat(t.getPrizeLabel()).contains("5,000");
        verify(repo).save(t);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).contains("당첨");
    }

    @Test
    @DisplayName("LOSE 티켓: 낙첨 반영(rank 0)·저장·낙첨 요약 알림")
    void checkLottoLose() {
        LotteryTicket t = new LotteryTicket(GameType.LOTTO645, 1233, "1,2,3,4,5,6", 1000, LocalDateTime.now());
        when(repo.findByGameTypeAndResultCheckedFalse(GameType.LOTTO645)).thenReturn(List.of(t));
        SidecarResults sr = new SidecarResults(true, List.of(
                new TicketResult(GameType.LOTTO645, 1233, "LOSE", 0, "2026-07-18")), List.of());

        svc(sr).checkLotto();

        assertThat(t.getRank()).isEqualTo(0);
        assertThat(t.isWinner()).isFalse();
        assertThat(t.isResultChecked()).isTrue();
        verify(repo).save(t);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).contains("낙첨");
    }

    @Test
    @DisplayName("PENDING 상태 티켓은 저장하지 않지만 미추첨 알림 1건은 발송한다")
    void pendingNotSaved() {
        LotteryTicket t = new LotteryTicket(GameType.LOTTO645, 1233, "3,7,12,25,33,41", 1000, LocalDateTime.now());
        when(repo.findByGameTypeAndResultCheckedFalse(GameType.LOTTO645)).thenReturn(List.of(t));
        SidecarResults sr = new SidecarResults(true, List.of(
                new TicketResult(GameType.LOTTO645, 1233, "PENDING", 0, "2026-07-18")), List.of());

        svc(sr).checkLotto();

        assertThat(t.isResultChecked()).isFalse();
        verify(repo, never()).save(any());
        verify(router, times(1)).sendText(anyString());
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).contains("미추첨");
    }

    @Test
    @DisplayName("사이드카 결과에 매칭 회차가 없으면 저장하지 않고 미추첨 알림 1건")
    void noMatchNotSaved() {
        LotteryTicket t = new LotteryTicket(GameType.LOTTO645, 1233, "3,7,12,25,33,41", 1000, LocalDateTime.now());
        when(repo.findByGameTypeAndResultCheckedFalse(GameType.LOTTO645)).thenReturn(List.of(t));
        // 다른 회차 결과만 존재 → 매칭 없음
        SidecarResults sr = new SidecarResults(true, List.of(
                new TicketResult(GameType.LOTTO645, 1200, "LOSE", 0, "2026-06-01")), List.of());

        svc(sr).checkLotto();

        assertThat(t.isResultChecked()).isFalse();
        verify(repo, never()).save(any());
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).contains("미추첨");
    }

    @Test
    @DisplayName("로또 확인 티켓 0건이어도 발표날 알림 1건을 발송한다")
    void lottoNotifiesEvenWhenNoTickets() {
        when(repo.findByGameTypeAndResultCheckedFalse(GameType.LOTTO645)).thenReturn(List.of());

        svc(new SidecarResults(true, List.of(), List.of())).checkLotto();

        verify(repo, never()).save(any());
        verify(router, times(1)).sendText(anyString());
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).contains("확인할 티켓이 없습니다");
    }

    @Test
    @DisplayName("연금 확인 티켓 0건이어도 발표날 알림 1건을 발송한다")
    void win720NotifiesEvenWhenNoTickets() {
        when(repo.findByGameTypeAndResultCheckedFalse(GameType.WIN720)).thenReturn(List.of());

        svc(new SidecarResults(true, List.of(), List.of())).checkWin720();

        verify(repo, never()).save(any());
        verify(router, times(1)).sendText(anyString());
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).contains("확인할 티켓이 없습니다");
    }

    @Test
    @DisplayName("연금 WIN 티켓도 사이드카 결과로 당첨 반영·저장한다")
    void checkWin720Win() {
        LotteryTicket t = new LotteryTicket(GameType.WIN720, 324, "3:123456", 1000, LocalDateTime.now());
        when(repo.findByGameTypeAndResultCheckedFalse(GameType.WIN720)).thenReturn(List.of(t));
        SidecarResults sr = new SidecarResults(true, List.of(
                new TicketResult(GameType.WIN720, 324, "WIN", 1_000_000, "2026-07-18")), List.of());

        svc(sr).checkWin720();

        assertThat(t.getRank()).isEqualTo(1);
        assertThat(t.isWinner()).isTrue();
        verify(repo).save(t);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).contains("당첨");
    }

    @Test
    @DisplayName("checkLotto는 다른 게임(WIN720) 결과를 무시하고 LOTTO645만 처리한다")
    void checkLottoFiltersGameType() {
        LotteryTicket t = new LotteryTicket(GameType.LOTTO645, 324, "3,7,12,25,33,41", 1000, LocalDateTime.now());
        when(repo.findByGameTypeAndResultCheckedFalse(GameType.LOTTO645)).thenReturn(List.of(t));
        // 같은 회차 번호지만 게임이 다른 결과만 존재 → 매칭 없음(미추첨)
        SidecarResults sr = new SidecarResults(true, List.of(
                new TicketResult(GameType.WIN720, 324, "WIN", 5000, "2026-07-18")), List.of());

        svc(sr).checkLotto();

        assertThat(t.isResultChecked()).isFalse();
        verify(repo, never()).save(any());
        assertThat(messages.get(0)).contains("미추첨");
    }

    @Test
    @DisplayName("사이드카 실패(ok=false)면 실패 알림을 보내고 저장·조회를 하지 않는다")
    void sidecarFailure() {
        SidecarResults sr = new SidecarResults(false, List.of(),
                List.of(new SidecarError("ALL", "로그인 실패")));

        svc(sr).checkLotto();

        verify(repo, never()).findByGameTypeAndResultCheckedFalse(any());
        verify(repo, never()).save(any());
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).contains("실패");
        assertThat(messages.get(0)).contains("로그인 실패");
    }
}
