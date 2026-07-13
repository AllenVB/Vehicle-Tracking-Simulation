package com.fleet.vts.common.event;

import com.fleet.vts.common.enums.NotificationChannel;
import com.fleet.vts.common.enums.Severity;
import lombok.Builder;

import java.time.Instant;

/**
 * A driver-facing notification on {@code vehicle.notification}, produced by the
 * notification service after applying preferences, quiet hours and cooldown.
 * The gateway relays WEBSOCKET ones to the user's private STOMP queue.
 */
@Builder
public record NotificationEvent(
        Long tenantId,
        Long userId,
        Long driverId,
        Long vehicleId,
        String ruleCode,
        Severity severity,
        NotificationChannel channel,
        String title,
        String body,
        Instant occurredAt,
        Long sourceViolationId,
        String correlationId
) {
}
