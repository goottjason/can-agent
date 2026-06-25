package com.canagent.service.notification;

import com.canagent.config.NotificationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
@ConditionalOnProperty(name = "notification.telegram.enabled", havingValue = "true")
public class TelegramNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationService.class);

    private final NotificationConfig config;
    private final RestTemplate restTemplate;

    public TelegramNotificationService(NotificationConfig config, RestTemplate restTemplate) {
        this.config = config;
        this.restTemplate = restTemplate;
    }

    @Override
    public void send(NotificationEvent event) {
        if (!isEnabled()) return;

        try {
            String url = String.format("https://api.telegram.org/bot%s/sendMessage",
                    config.getTelegram().getBotToken());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String message = event.formatMessage();
            String jsonBody = String.format(
                    "{\"chat_id\":\"%s\",\"text\":\"%s\",\"parse_mode\":\"HTML\"}",
                    config.getTelegram().getChatId(),
                    escapeJson(message)
            );

            HttpEntity<String> request = new HttpEntity<>(jsonBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.debug("텔레그램 알림 전송 성공");
            } else {
                log.warn("텔레그램 알림 전송 실패: {}", response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("텔레그램 알림 전송 오류: {}", e.getMessage());
        }
    }

    @Override
    public String getChannelName() {
        return "telegram";
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled()
                && config.getTelegram().isEnabled()
                && config.getTelegram().getBotToken() != null
                && !config.getTelegram().getBotToken().isBlank()
                && config.getTelegram().getChatId() != null
                && !config.getTelegram().getChatId().isBlank();
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
