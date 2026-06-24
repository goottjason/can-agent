package com.canagent.service.notification;

import com.canagent.config.NotificationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ConsoleNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(ConsoleNotificationService.class);

    private final NotificationConfig config;

    public ConsoleNotificationService(NotificationConfig config) {
        this.config = config;
    }

    @Override
    public void send(NotificationEvent event) {
        log.info("===== 거래 알림 =====\n{}", event.formatMessage());
    }

    @Override
    public String getChannelName() {
        return "console";
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled() && config.getConsole().isEnabled();
    }
}
