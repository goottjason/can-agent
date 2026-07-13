package com.canagent.service.lottery;

import com.canagent.domain.lottery.GameType;
import com.canagent.domain.lottery.LotteryTicket;
import com.canagent.repository.LotteryTicketRepository;
import com.canagent.service.lottery.dto.LottoDraw;
import com.canagent.service.lottery.dto.Win720Draw;
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
    private LottoResultClient lottoClient;
    private Win720ResultClient winClient;
    private NotificationServiceRouter router;
    private final List<String> messages = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repo = mock(LotteryTicketRepository.class);
        lottoClient = mock(LottoResultClient.class);
        winClient = mock(Win720ResultClient.class);
        router = mock(NotificationServiceRouter.class);
        messages.clear();
        doAnswer(inv -> { messages.add(inv.getArgument(0)); return null; }).when(router).sendText(anyString());
    }

    @Test
    @DisplayName("로또 당첨확인: 등수 반영·저장·알림")
    void checkLotto() {
        LotteryTicket t = new LotteryTicket(GameType.LOTTO645, 1100, "3,7,12,25,33,41", 1000, LocalDateTime.now());
        when(repo.findByGameTypeAndResultCheckedFalse(GameType.LOTTO645)).thenReturn(List.of(t));
        when(lottoClient.getWinningNumbers(1100))
                .thenReturn(new LottoDraw(1100, List.of(3, 7, 12, 25, 33, 41), 10, 2_000_000_000L, true));

        LotteryResultService svc = new LotteryResultService(repo, lottoClient, winClient, router);
        svc.checkLotto();

        assertThat(t.getRank()).isEqualTo(1);
        assertThat(t.isResultChecked()).isTrue();
        verify(repo).save(t);
        assertThat(messages).anyMatch(m -> m.contains("1등"));
    }

    @Test
    @DisplayName("미추첨(success=false)이면 확인·저장하지 않는다")
    void skipWhenNotDrawn() {
        LotteryTicket t = new LotteryTicket(GameType.LOTTO645, 1100, "3,7,12,25,33,41", 1000, LocalDateTime.now());
        when(repo.findByGameTypeAndResultCheckedFalse(GameType.LOTTO645)).thenReturn(List.of(t));
        when(lottoClient.getWinningNumbers(1100)).thenReturn(new LottoDraw(0, List.of(), 0, 0, false));

        LotteryResultService svc = new LotteryResultService(repo, lottoClient, winClient, router);
        svc.checkLotto();

        assertThat(t.isResultChecked()).isFalse();
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("연금 당첨확인: 조:6자리 파싱·등수 반영")
    void checkWin720() {
        LotteryTicket t = new LotteryTicket(GameType.WIN720, 240, "3:123456", 1000, LocalDateTime.now());
        when(repo.findByGameTypeAndResultCheckedFalse(GameType.WIN720)).thenReturn(List.of(t));
        when(winClient.getWinningNumbers(240)).thenReturn(new Win720Draw(240, 3, "123456", "987654", true));

        LotteryResultService svc = new LotteryResultService(repo, lottoClient, winClient, router);
        svc.checkWin720();

        assertThat(t.getRank()).isEqualTo(1);
        verify(repo).save(t);
        assertThat(messages).anyMatch(m -> m.contains("1등"));
    }
}
