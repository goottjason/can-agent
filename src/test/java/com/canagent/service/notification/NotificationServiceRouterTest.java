package com.canagent.service.notification;

import com.canagent.config.NotificationConfig;
import com.canagent.MockDataFactory;
import com.canagent.domain.stock.Stock;
import com.canagent.domain.trading.Trade;
import com.canagent.domain.trading.TradeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("알림 라우터 단위테스트")
class NotificationServiceRouterTest {

    private NotificationConfig config;
    private ConsoleNotificationService consoleService;
    private NotificationServiceRouter router;

    @BeforeEach
    void setUp() {
        config = new NotificationConfig();
        config.setEnabled(true);
        config.getConsole().setEnabled(true);
        config.getTelegram().setEnabled(false);
        config.getKakao().setEnabled(false);

        consoleService = spy(new ConsoleNotificationService(config));
        router = new NotificationServiceRouter(List.of(consoleService));
    }

    @Test
    @DisplayName("활성화된 채널로 알림을 전송한다")
    void sendNotification_enabledChannel_sends() {
        Stock stock = MockDataFactory.createSamsungStock();
        Trade trade = new Trade(stock, TradeType.BUY, new BigDecimal("10"), new BigDecimal("70000"), "테스트");
        NotificationEvent event = NotificationEvent.fromTrade(trade);

        router.sendNotification(event);

        verify(consoleService, times(1)).send(event);
    }

    @Test
    @DisplayName("비활성화된 채널로는 알림을 전송하지 않는다")
    void sendNotification_disabledChannel_doesNotSend() {
        config.getConsole().setEnabled(false);
        ConsoleNotificationService disabledService = spy(new ConsoleNotificationService(config));
        NotificationServiceRouter disabledRouter = new NotificationServiceRouter(List.of(disabledService));

        Stock stock = MockDataFactory.createSamsungStock();
        Trade trade = new Trade(stock, TradeType.BUY, new BigDecimal("10"), new BigDecimal("70000"), "테스트");
        NotificationEvent event = NotificationEvent.fromTrade(trade);

        disabledRouter.sendNotification(event);

        verify(disabledService, never()).send(event);
    }

    @Test
    @DisplayName("활성화된 채널 목록을 리턴한다")
    void getEnabledChannels_returnsEnabledList() {
        List<String> channels = router.getEnabledChannels();

        assertThat(channels).contains("console");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("sendText는 활성 채널에만 전달한다")
    void sendText_fansOutToEnabledOnly() {
        java.util.List<String> received = new java.util.ArrayList<>();
        NotificationService enabled = new NotificationService() {
            public void send(NotificationEvent e) {}
            public void sendText(String m) { received.add(m); }
            public String getChannelName() { return "on"; }
            public boolean isEnabled() { return true; }
        };
        NotificationService disabled = new NotificationService() {
            public void send(NotificationEvent e) {}
            public void sendText(String m) { received.add("SHOULD_NOT"); }
            public String getChannelName() { return "off"; }
            public boolean isEnabled() { return false; }
        };
        NotificationServiceRouter router = new NotificationServiceRouter(java.util.List.of(enabled, disabled));

        router.sendText("예치금 부족");

        org.assertj.core.api.Assertions.assertThat(received).containsExactly("예치금 부족");
    }

    @Test
    @DisplayName("전체 알림 전송 실패해도 다른 채널에 영향을 주지 않는다")
    void sendNotification_oneFailsOthersStillCalled() {
        NotificationService failingService = mock(NotificationService.class);
        when(failingService.isEnabled()).thenReturn(true);
        when(failingService.getChannelName()).thenReturn("failing");
        doThrow(new RuntimeException("실패")).when(failingService).send(any());

        NotificationServiceRouter multiRouter = new NotificationServiceRouter(
                List.of(failingService, consoleService));

        Stock stock = MockDataFactory.createSamsungStock();
        Trade trade = new Trade(stock, TradeType.BUY, new BigDecimal("10"), new BigDecimal("70000"), "테스트");
        NotificationEvent event = NotificationEvent.fromTrade(trade);

        multiRouter.sendNotification(event);

        verify(consoleService, times(1)).send(event);
    }
}
