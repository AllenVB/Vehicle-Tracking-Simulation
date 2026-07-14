package com.fleet.vts.processing.rules;

import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.event.ViolationEvent;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs every {@link TelemetryRule} bean against a reading (Strategy pattern) and
 * returns the resulting violations.
 *
 * <p>A per-(vehicle, rule) cooldown suppresses repeats: a vehicle that keeps
 * breaching a threshold (e.g. speeding every second) yields at most one violation
 * per the rule's {@code cooldown_seconds} window, instead of one per reading. This
 * keeps violation volume — and the DB / Kafka / WebSocket / notification load it
 * drives — proportional to distinct incidents rather than to the telemetry rate.
 */
@Service
public class RuleEngine {

    private final List<TelemetryRule> rules;
    private final VehicleContextResolver resolver;

    /** Last time a (vehicle, rule) pair produced a violation. */
    private final Cache<String, Instant> lastFired = Caffeine.newBuilder()
            .maximumSize(200_000)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    public RuleEngine(List<TelemetryRule> rules, VehicleContextResolver resolver) {
        this.rules = rules;
        this.resolver = resolver;
    }

    public List<ViolationEvent> evaluate(TelemetryEvent event) {
        VehicleContext ctx = resolver.resolve(event);
        List<ViolationEvent> violations = new ArrayList<>();
        for (TelemetryRule rule : rules) {
            rule.evaluate(event, ctx)
                    .filter(v -> passesCooldown(v, ctx, event.ts()))
                    .ifPresent(violations::add);
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

    /** True if this violation is outside the rule's cooldown window (so it fires). */
    private boolean passesCooldown(ViolationEvent v, VehicleContext ctx, Instant ts) {
        RuleView rule = ctx.rule(v.ruleCode());
        int cooldown = rule == null ? 0 : rule.cooldownSeconds();
        if (cooldown <= 0) {
            return true;
        }
        String key = v.vehicleId() + ":" + v.ruleCode();
        Instant last = lastFired.getIfPresent(key);
        if (last != null && ts.isBefore(last.plusSeconds(cooldown))) {
            return false; // still cooling down -> suppress this repeat
        }
        lastFired.put(key, ts);
        return true;
    }
}
