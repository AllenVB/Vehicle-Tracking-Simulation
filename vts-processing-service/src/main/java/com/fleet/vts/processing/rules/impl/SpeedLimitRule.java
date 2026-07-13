package com.fleet.vts.processing.rules.impl;

import com.fleet.vts.common.enums.RuleType;
import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.event.ViolationEvent;
import com.fleet.vts.processing.rules.AbstractTelemetryRule;
import com.fleet.vts.processing.rules.RuleView;
import com.fleet.vts.processing.rules.VehicleContext;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Fires when instantaneous speed exceeds the (scope-resolved) limit. */
@Component
public class SpeedLimitRule extends AbstractTelemetryRule {

    private static final String CODE = "SPEED_LIMIT";

    @Override
    public RuleType type() {
        return RuleType.SPEED_LIMIT;
    }

    @Override
    public Optional<ViolationEvent> evaluate(TelemetryEvent e, VehicleContext ctx) {
        RuleView rule = ctx.rule(CODE);
        if (rule == null || !rule.enabled() || e.speedKmh() == null) {
            return Optional.empty();
        }
        if (e.speedKmh() > rule.threshold()) {
            return Optional.of(violation(e, ctx, rule, e.speedKmh()));
        }
        return Optional.empty();
    }
}
