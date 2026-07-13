package com.fleet.vts.notification.sender;

import com.fleet.vts.common.enums.NotificationChannel;
import com.fleet.vts.common.enums.Severity;

import java.time.Instant;

/** A resolved notification ready to be dispatched over a specific channel. */
public record NotificationMessage(
        Long tenantId,
        Long userId,
        Long driverId,
        Long vehicleId,
        String ruleCode,
        Severity severity,
        NotificationChannel channel,
        String title,
        String body,
        Long sourceViolationId,
        Instant occurredAt) {
}
