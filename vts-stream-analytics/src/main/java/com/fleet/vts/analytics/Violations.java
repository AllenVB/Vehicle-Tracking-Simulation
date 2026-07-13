package com.fleet.vts.analytics;

import com.fleet.vts.common.enums.RuleType;
import com.fleet.vts.common.enums.Severity;
import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.event.ViolationEvent;

/** Builds a {@link ViolationEvent} from a reading. Driver attribution is left to
 *  downstream (stream side has no DB context); rule id is null (matched by code). */
public final class Violations {

    private Violations() {
    }

    public static ViolationEvent of(TelemetryEvent e, RuleType type, Severity severity,
                                    double value, Double threshold) {
        return ViolationEvent.builder()
                .tenantId(e.tenantId())
                .vehicleId(e.vehicleId())
                .deviceId(e.deviceId())
                .ruleCode(type.name())
                .ruleType(type)
                .severity(severity)
                .occurredAt(e.ts())
                .value(value)
                .threshold(threshold)
                .lat(e.lat())
                .lon(e.lon())
                .correlationId(e.correlationId())
                .build();
    }
}
