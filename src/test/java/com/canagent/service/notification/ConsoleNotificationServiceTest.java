package com.canagent.service.notification;

import com.canagent.config.NotificationConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("콘솔 알림 서비스 단위테스트")
class ConsoleNotificationServiceTest {

    @Test
    @DisplayName("활성화된 상태면 채널명을 리턴한다")
    void isEnabled_active_returnsTrue() {
        NotificationConfig config = new NotificationConfig();
        config.setEnabled(true);
        config.getConsole().setEnabled(true);

        ConsoleNotificationService service = new ConsoleNotificationService(config);

        assertThat(service.isEnabled()).isTrue();
        assertThat(service.getChannelName()).isEqualTo("console");
    }

    @Test
    @DisplayName("전체 알림이 비활성화되면 비활성화된다")
    void isEnabled_globalDisabled_returnsFalse() {
        NotificationConfig config = new NotificationConfig();
        config.setEnabled(false);
        config.getConsole().setEnabled(true);

        ConsoleNotificationService service = new ConsoleNotificationService(config);

        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("콘솔 알림이 비활성화되면 비활성화된다")
    void isEnabled_consoleDisabled_returnsFalse() {
        NotificationConfig config = new NotificationConfig();
        config.setEnabled(true);
        config.getConsole().setEnabled(false);

        ConsoleNotificationService service = new ConsoleNotificationService(config);

        assertThat(service.isEnabled()).isFalse();
    }
}
