package com.fleet.vts.processing.rules;

import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.event.ViolationEvent;

/** Shared helper for building a {@link ViolationEvent} from a reading + rule. */
public abstract class AbstractTelemetryRule implements TelemetryRule {

    protected ViolationEvent violation(TelemetryEvent e, VehicleContext ctx, RuleView rule,
                                       double measuredValue) {
        return ViolationEvent.builder()
                .tenantId(ctx.tenantId())
                .vehicleId(ctx.vehicleId())
                .driverId(ctx.driverId())
                .deviceId(ctx.deviceId())
                .ruleId(rule.ruleId())
                .ruleCode(rule.code())
                .ruleType(rule.type())
                .severity(rule.severity())
                .occurredAt(e.ts())
                .value(measuredValue)
                .threshold(rule.threshold())
                .lat(e.lat())
                .lon(e.lon())
                .correlationId(e.correlationId())
                .build();
    }
}
