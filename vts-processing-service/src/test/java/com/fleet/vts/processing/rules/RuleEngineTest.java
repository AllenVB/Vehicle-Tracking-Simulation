package com.fleet.vts.processing.rules;

import com.fleet.vts.common.enums.RuleType;
import com.fleet.vts.common.enums.Severity;
import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.event.ViolationEvent;
import com.fleet.vts.processing.rules.impl.LowBatteryRule;
import com.fleet.vts.processing.rules.impl.LowFuelRule;
import com.fleet.vts.processing.rules.impl.SpeedLimitRule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Stateless rule evaluation with a fixed, hand-built vehicle context. */
class RuleEngineTest {

    private final VehicleContextResolver resolver = mock(VehicleContextResolver.class);
    private final RuleEngine engine = new RuleEngine(
            List.of(new SpeedLimitRule(), new LowBatteryRule(), new LowFuelRule()), resolver);

    private VehicleContext context() {
        Map<String, RuleView> rules = Map.of(
                "SPEED_LIMIT", new RuleView(1L, "SPEED_LIMIT", RuleType.SPEED_LIMIT, Severity.HIGH, 80, true, 0),
                "LOW_BATTERY", new RuleView(2L, "LOW_BATTERY", RuleType.LOW_BATTERY, Severity.MEDIUM, 20, true, 0),
                "LOW_FUEL", new RuleView(3L, "LOW_FUEL", RuleType.LOW_FUEL, Severity.MEDIUM, 15, true, 0));
        return new VehicleContext(42L, 1L, 5L, 7L, rules);
    }

    private TelemetryEvent event(int speed, int battery, int fuel) {
        return TelemetryEvent.builder()
                .tenantId(1L).vehicleId(42L).deviceId(7L)
                .ts(Instant.parse("2026-07-13T10:00:00Z"))
                .lat(41.0).lon(29.0)
                .speedKmh(speed).heading(90).battery(battery).fuelPct(fuel)
                .engineOn(true).ignition(true).odometerKm(1000L)
                .correlationId("c1")
                .build();
    }

    @Test
    void allThreeRulesFireWhenThresholdsBreached() {
        when(resolver.resolve(any())).thenReturn(context());

        List<ViolationEvent> violations = engine.evaluate(event(100, 10, 8));

        assertEquals(3, violations.size());
        assertTrue(violations.stream().anyMatch(v -> v.ruleType() == RuleType.SPEED_LIMIT && v.value() == 100));
        assertTrue(violations.stream().anyMatch(v -> v.ruleType() == RuleType.LOW_BATTERY));
        assertTrue(violations.stream().anyMatch(v -> v.ruleType() == RuleType.LOW_FUEL));
        // driver attributed from the context
        assertTrue(violations.stream().allMatch(v -> v.driverId() == 5L));
    }

    @Test
    void noViolationsWhenWithinLimits() {
        when(resolver.resolve(any())).thenReturn(context());

        List<ViolationEvent> violations = engine.evaluate(event(60, 90, 70));

        assertTrue(violations.isEmpty());
    }

    @Test
    void speedLimitRespectsResolvedThreshold() {
        // car threshold overridden to 110: 100 km/h is NOT a violation
        Map<String, RuleView> rules = Map.of(
                "SPEED_LIMIT", new RuleView(1L, "SPEED_LIMIT", RuleType.SPEED_LIMIT, Severity.HIGH, 110, true, 0));
        when(resolver.resolve(any())).thenReturn(new VehicleContext(42L, 1L, 5L, 7L, rules));

        assertTrue(engine.evaluate(event(100, 90, 70)).isEmpty());
    }

    @Test
    void repeatViolationSuppressedWithinCooldownWindow() {
        // A vehicle that keeps speeding fires once, then is silent until the
        // 300s cooldown elapses — instead of once per reading.
        RuleEngine local = new RuleEngine(List.of(new SpeedLimitRule()), resolver);
        Map<String, RuleView> rules = Map.of(
                "SPEED_LIMIT", new RuleView(1L, "SPEED_LIMIT", RuleType.SPEED_LIMIT, Severity.HIGH, 80, true, 300));
        when(resolver.resolve(any())).thenReturn(new VehicleContext(42L, 1L, 5L, 7L, rules));

        Instant t0 = Instant.parse("2026-07-13T10:00:00Z");
        assertEquals(1, local.evaluate(speedAt(100, t0)).size());             // first breach fires
        assertTrue(local.evaluate(speedAt(100, t0.plusSeconds(10))).isEmpty()); // 10s later: suppressed
        assertTrue(local.evaluate(speedAt(100, t0.plusSeconds(200))).isEmpty());// still within window
        assertEquals(1, local.evaluate(speedAt(100, t0.plusSeconds(301))).size()); // after window: fires again
    }

    private TelemetryEvent speedAt(int speed, Instant ts) {
        return TelemetryEvent.builder()
                .tenantId(1L).vehicleId(42L).deviceId(7L)
                .ts(ts).lat(41.0).lon(29.0)
                .speedKmh(speed).heading(90).battery(90).fuelPct(70)
                .engineOn(true).ignition(true).odometerKm(1000L)
                .correlationId("c1")
                .build();
    }
}
