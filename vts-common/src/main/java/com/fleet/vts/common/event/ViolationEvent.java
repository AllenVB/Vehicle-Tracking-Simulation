package com.fleet.vts.common.event;

import com.fleet.vts.common.enums.RuleType;
import com.fleet.vts.common.enums.Severity;
import lombok.Builder;

import java.time.Instant;

/**
 * A rule violation published to {@code vehicle.violation} by the processing
 * service (stateless rules) or the analytics topology (stateful rules).
 * {@code driverId} is the driver attributed at {@code occurredAt} via the
 * temporal vehicle-driver assignment.
 */
@Builder
public record ViolationEvent(
        Long tenantId,
        Long vehicleId,
        Long driverId,
        Long deviceId,
        Long ruleId,
        String ruleCode,
        RuleType ruleType,
        Severity severity,
        Instant occurredAt,
        Double value,
        Double threshold,
        Double lat,
        Double lon,
        Long tripId,
        String correlationId
) {
}
