package com.fleet.vts.processing.rules.impl;

import com.fleet.vts.common.enums.RuleType;
import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.event.ViolationEvent;
import com.fleet.vts.processing.rules.AbstractTelemetryRule;
import com.fleet.vts.processing.rules.RuleView;
import com.fleet.vts.processing.rules.VehicleContext;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Fires when battery drops below the configured threshold. */
@Component
public class LowBatteryRule extends AbstractTelemetryRule {

    private static final String CODE = "LOW_BATTERY";

    @Override
    public RuleType type() {
        return RuleType.LOW_BATTERY;
    }

    @Override
    public Optional<ViolationEvent> evaluate(TelemetryEvent e, VehicleContext ctx) {
        RuleView rule = ctx.rule(CODE);
        if (rule == null || !rule.enabled() || e.battery() == null) {
            return Optional.empty();
        }
        if (e.battery() < rule.threshold()) {
            return Optional.of(violation(e, ctx, rule, e.battery()));
        }
        return Optional.empty();
    }
}
