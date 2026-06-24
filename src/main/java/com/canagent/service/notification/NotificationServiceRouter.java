package com.canagent.service.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NotificationServiceRouter {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceRouter.class);

    private final List<NotificationService> services;

    public NotificationServiceRouter(List<NotificationService> services) {
        this.services = services;
        log.info("알림 채널 초기화: {}",
                services.stream()
                        .filter(NotificationService::isEnabled)
                        .map(NotificationService::getChannelName)
                        .toList());
    }

    public void sendNotification(NotificationEvent event) {
        for (NotificationService service : services) {
            if (service.isEnabled()) {
                try {
                    service.send(event);
                } catch (Exception e) {
                    log.error("알림 전송 실패 ({}): {}",
                            service.getChannelName(), e.getMessage());
                }
            }
        }
    }

    public List<String> getEnabledChannels() {
        return services.stream()
                .filter(NotificationService::isEnabled)
                .map(NotificationService::getChannelName)
                .toList();
    }
}
