package com.canagent.service.notification;

import com.canagent.config.NotificationConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("텔레그램 알림 서비스 단위테스트")
class TelegramNotificationServiceTest {

    private final RestTemplate restTemplate = new RestTemplate();

    @Test
    @DisplayName("설정이 있으면 활성화된다")
    void isEnabled_withConfig_returnsTrue() {
        NotificationConfig config = new NotificationConfig();
        config.setEnabled(true);
        config.getTelegram().setEnabled(true);
        config.getTelegram().setBotToken("test-token");
        config.getTelegram().setChatId("123456");

        TelegramNotificationService service = new TelegramNotificationService(config, restTemplate);

        assertThat(service.isEnabled()).isTrue();
        assertThat(service.getChannelName()).isEqualTo("telegram");
    }

    @Test
    @DisplayName("봇 토큰이 없으면 비활성화된다")
    void isEnabled_noBotToken_returnsFalse() {
        NotificationConfig config = new NotificationConfig();
        config.setEnabled(true);
        config.getTelegram().setEnabled(true);
        config.getTelegram().setChatId("123456");

        TelegramNotificationService service = new TelegramNotificationService(config, restTemplate);

        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("챗 ID가 없으면 비활성화된다")
    void isEnabled_noChatId_returnsFalse() {
        NotificationConfig config = new NotificationConfig();
        config.setEnabled(true);
        config.getTelegram().setEnabled(true);
        config.getTelegram().setBotToken("test-token");

        TelegramNotificationService service = new TelegramNotificationService(config, restTemplate);

        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("텔레그램이 비활성화되면 비활성화된다")
    void isEnabled_telegramDisabled_returnsFalse() {
        NotificationConfig config = new NotificationConfig();
        config.setEnabled(true);
        config.getTelegram().setEnabled(false);

        TelegramNotificationService service = new TelegramNotificationService(config, restTemplate);

        assertThat(service.isEnabled()).isFalse();
    }
}
