package com.fleet.vts.processing.rules.impl;

import com.fleet.vts.common.enums.RuleType;
import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.event.ViolationEvent;
import com.fleet.vts.processing.rules.AbstractTelemetryRule;
import com.fleet.vts.processing.rules.RuleView;
import com.fleet.vts.processing.rules.VehicleContext;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Fires when fuel level drops below the configured threshold. */
@Component
public class LowFuelRule extends AbstractTelemetryRule {

    private static final String CODE = "LOW_FUEL";

    @Override
    public RuleType type() {
        return RuleType.LOW_FUEL;
    }

    @Override
    public Optional<ViolationEvent> evaluate(TelemetryEvent e, VehicleContext ctx) {
        RuleView rule = ctx.rule(CODE);
        if (rule == null || !rule.enabled() || e.fuelPct() == null) {
            return Optional.empty();
        }
        if (e.fuelPct() < rule.threshold()) {
            return Optional.of(violation(e, ctx, rule, e.fuelPct()));
        }
        return Optional.empty();
    }
}
