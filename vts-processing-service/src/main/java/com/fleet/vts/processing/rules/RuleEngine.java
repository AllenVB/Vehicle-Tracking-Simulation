package com.fleet.vts.processing.rules;

import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.event.ViolationEvent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs every {@link TelemetryRule} bean against a reading (Strategy pattern) and
 * returns the resulting violations.
 */
@Service
public class RuleEngine {

    private final List<TelemetryRule> rules;
    private final VehicleContextResolver resolver;

    public RuleEngine(List<TelemetryRule> rules, VehicleContextResolver resolver) {
        this.rules = rules;
        this.resolver = resolver;
    }

    public List<ViolationEvent> evaluate(TelemetryEvent event) {
        VehicleContext ctx = resolver.resolve(event);
        List<ViolationEvent> violations = new ArrayList<>();
        for (TelemetryRule rule : rules) {
            rule.evaluate(event, ctx).ifPresent(violations::add);
        }
        return violations;
    }

    public List<ViolationEvent> evaluateBatch(List<TelemetryEvent> events) {
        List<ViolationEvent> all = new ArrayList<>();
        for (TelemetryEvent event : events) {
            all.addAll(evaluate(event));
        }
        return all;
    }
}
