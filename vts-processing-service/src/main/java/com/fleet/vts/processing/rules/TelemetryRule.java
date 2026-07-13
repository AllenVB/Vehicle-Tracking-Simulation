package com.fleet.vts.processing.rules;

import com.fleet.vts.common.enums.RuleType;
import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.event.ViolationEvent;

import java.util.Optional;

/**
 * A stateless rule evaluated against a single reading. Implementations are
 * Spring beans; the {@link RuleEngine} injects them all (Strategy pattern).
 */
public interface TelemetryRule {

    RuleType type();

    Optional<ViolationEvent> evaluate(TelemetryEvent event, VehicleContext context);
}
