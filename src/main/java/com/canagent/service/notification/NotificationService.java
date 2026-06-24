package com.canagent.service.notification;

public interface NotificationService {

    void send(NotificationEvent event);

    String getChannelName();

    boolean isEnabled();
}
