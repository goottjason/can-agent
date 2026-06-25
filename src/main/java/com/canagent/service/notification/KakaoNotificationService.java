package com.canagent.service.notification;

import com.canagent.config.NotificationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
@ConditionalOnProperty(name = "notification.kakao.enabled", havingValue = "true")
public class KakaoNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(KakaoNotificationService.class);

    private final NotificationConfig config;
    private final RestTemplate restTemplate;

    public KakaoNotificationService(NotificationConfig config, RestTemplate restTemplate) {
        this.config = config;
        this.restTemplate = restTemplate;
    }

    @Override
    public void send(NotificationEvent event) {
        if (!isEnabled()) return;

        try {
            String url = config.getKakao().getWebhookUrl();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String message = event.formatMessage();
            String jsonBody = String.format(
                    "{\"msg_type\":\"text\",\"text\":\"%s\"}",
                    escapeJson(message)
            );

            HttpEntity<String> request = new HttpEntity<>(jsonBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.debug("카카오 알림 전송 성공");
            } else {
                log.warn("카카오 알림 전송 실패: {}", response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("카카오 알림 전송 오류: {}", e.getMessage());
        }
    }

    @Override
    public String getChannelName() {
        return "kakao";
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled()
                && config.getKakao().isEnabled()
                && config.getKakao().getWebhookUrl() != null
                && !config.getKakao().getWebhookUrl().isBlank();
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
