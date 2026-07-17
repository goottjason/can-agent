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
    @DisplayName("미추첨(success=false)이면 저장하지 않지만 미추첨 알림 1건은 발송한다")
    void skipWhenNotDrawn() {
        LotteryTicket t = new LotteryTicket(GameType.LOTTO645, 1100, "3,7,12,25,33,41", 1000, LocalDateTime.now());
        when(repo.findByGameTypeAndResultCheckedFalse(GameType.LOTTO645)).thenReturn(List.of(t));
        when(lottoClient.getWinningNumbers(1100)).thenReturn(new LottoDraw(0, List.of(), 0, 0, false));

        LotteryResultService svc = new LotteryResultService(repo, lottoClient, winClient, router);
        svc.checkLotto();

        assertThat(t.isResultChecked()).isFalse();
        verify(repo, never()).save(any());
        // 발표날 크론이므로 미추첨이어도 알림 1건 발송
        verify(router, times(1)).sendText(anyString());
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).contains("미추첨");
    }

    @Test
    @DisplayName("로또 확인 티켓 0건이어도 발표날 알림 1건을 발송한다")
    void lottoNotifiesEvenWhenNoTickets() {
        when(repo.findByGameTypeAndResultCheckedFalse(GameType.LOTTO645)).thenReturn(List.of());

        LotteryResultService svc = new LotteryResultService(repo, lottoClient, winClient, router);
        svc.checkLotto();

        verify(repo, never()).save(any());
        verify(lottoClient, never()).getWinningNumbers(anyInt());
        verify(router, times(1)).sendText(anyString());
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).contains("확인할 티켓이 없습니다");
    }

    @Test
    @DisplayName("연금 확인 티켓 0건이어도 발표날 알림 1건을 발송한다")
    void win720NotifiesEvenWhenNoTickets() {
        when(repo.findByGameTypeAndResultCheckedFalse(GameType.WIN720)).thenReturn(List.of());

        LotteryResultService svc = new LotteryResultService(repo, lottoClient, winClient, router);
        svc.checkWin720();

        verify(repo, never()).save(any());
        verify(winClient, never()).getWinningNumbers(anyInt());
        verify(router, times(1)).sendText(anyString());
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).contains("확인할 티켓이 없습니다");
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

    @Test
    @DisplayName("숫자 파싱 실패 티켓은 건너뛰고 다음 티켓 처리를 계속한다")
    void malformedTicketDoesNotAbortBatch() {
        LotteryTicket bad = new LotteryTicket(GameType.LOTTO645, 1100, "3,x,12,25,33,41", 1000, LocalDateTime.now());
        LotteryTicket good = new LotteryTicket(GameType.LOTTO645, 1100, "3,7,12,25,33,41", 1000, LocalDateTime.now());
        when(repo.findByGameTypeAndResultCheckedFalse(GameType.LOTTO645)).thenReturn(List.of(bad, good));
        when(lottoClient.getWinningNumbers(1100))
                .thenReturn(new LottoDraw(1100, List.of(3, 7, 12, 25, 33, 41), 10, 2_000_000_000L, true));

        LotteryResultService svc = new LotteryResultService(repo, lottoClient, winClient, router);
        // 배치 전체가 예외로 중단되지 않아야 한다
        svc.checkLotto();

        assertThat(bad.isResultChecked()).isFalse();   // 파싱 실패 — 상태 변경 없음
        assertThat(good.getRank()).isEqualTo(1);        // 다음 티켓은 정상 처리
        verify(repo, never()).save(bad);
        verify(repo).save(good);
        assertThat(messages).anyMatch(m -> m.contains("1등"));
    }

    @Test
    @DisplayName("같은 회차 티켓 2개 — 당첨번호 API는 1회만 호출된다(캐시)")
    void sameRoundCachedDraw() {
        LotteryTicket t1 = new LotteryTicket(GameType.LOTTO645, 1100, "3,7,12,25,33,41", 1000, LocalDateTime.now());
        LotteryTicket t2 = new LotteryTicket(GameType.LOTTO645, 1100, "1,2,3,4,5,6", 1000, LocalDateTime.now());
        when(repo.findByGameTypeAndResultCheckedFalse(GameType.LOTTO645)).thenReturn(List.of(t1, t2));
        when(lottoClient.getWinningNumbers(1100))
                .thenReturn(new LottoDraw(1100, List.of(3, 7, 12, 25, 33, 41), 10, 2_000_000_000L, true));

        LotteryResultService svc = new LotteryResultService(repo, lottoClient, winClient, router);
        svc.checkLotto();

        // 같은 회차이므로 API 1회만 호출
        verify(lottoClient, times(1)).getWinningNumbers(1100);
        // 두 티켓 모두 처리
        assertThat(t1.isResultChecked()).isTrue();
        assertThat(t2.isResultChecked()).isTrue();
        verify(repo).save(t1);
        verify(repo).save(t2);
    }
}
