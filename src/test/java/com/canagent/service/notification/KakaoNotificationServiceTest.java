package com.canagent.service.notification;

import com.canagent.config.NotificationConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("카카오 알림 서비스 단위테스트")
class KakaoNotificationServiceTest {

    private final RestTemplate restTemplate = new RestTemplate();

    @Test
    @DisplayName("설정이 있으면 활성화된다")
    void isEnabled_withConfig_returnsTrue() {
        NotificationConfig config = new NotificationConfig();
        config.setEnabled(true);
        config.getKakao().setEnabled(true);
        config.getKakao().setWebhookUrl("https://example.com/webhook");

        KakaoNotificationService service = new KakaoNotificationService(config, restTemplate);

        assertThat(service.isEnabled()).isTrue();
        assertThat(service.getChannelName()).isEqualTo("kakao");
    }

    @Test
    @DisplayName("웹훅 URL이 없으면 비활성화된다")
    void isEnabled_noWebhookUrl_returnsFalse() {
        NotificationConfig config = new NotificationConfig();
        config.setEnabled(true);
        config.getKakao().setEnabled(true);

        KakaoNotificationService service = new KakaoNotificationService(config, restTemplate);

        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("카카오가 비활성화되면 비활성화된다")
    void isEnabled_kakaoDisabled_returnsFalse() {
        NotificationConfig config = new NotificationConfig();
        config.setEnabled(true);
        config.getKakao().setEnabled(false);

        KakaoNotificationService service = new KakaoNotificationService(config, restTemplate);

        assertThat(service.isEnabled()).isFalse();
    }
}
