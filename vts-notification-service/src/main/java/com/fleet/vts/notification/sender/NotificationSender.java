package com.fleet.vts.notification.sender;

import com.fleet.vts.common.enums.NotificationChannel;

/**
 * Strategy for delivering a notification over one channel. WebSocket is real;
 * Email/SMS/Push are mocked (logged) in phase 1.
 */
public interface NotificationSender {

    NotificationChannel channel();

    /** @return true if the notification was dispatched successfully. */
    boolean send(NotificationMessage message);
}
