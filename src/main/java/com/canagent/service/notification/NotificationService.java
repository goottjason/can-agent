package com.canagent.service.notification;

public interface NotificationService {

    void send(NotificationEvent event);

    /** 일반 텍스트 알림(복권 등 비매매 알림). */
    void sendText(String message);

    String getChannelName();

    boolean isEnabled();
}
