package com.fleet.vts.common.enums;

/**
 * Delivery channels. WEBSOCKET is the real implementation; EMAIL/SMS/PUSH are
 * mocked (logged) in phase 1 behind the NotificationSender strategy.
 */
public enum NotificationChannel {
    WEBSOCKET,
    EMAIL,
    SMS,
    PUSH
}
