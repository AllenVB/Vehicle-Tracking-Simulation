package com.fleet.vts.processing.processing;

import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.event.ViolationEvent;
import com.fleet.vts.processing.cache.PositionCache;
import com.fleet.vts.processing.forward.ProcessedForwarder;
import com.fleet.vts.processing.persistence.LastPositionWriter;
import com.fleet.vts.processing.persistence.TelemetryWriter;
import com.fleet.vts.processing.persistence.ViolationWriter;
import com.fleet.vts.processing.rules.RuleEngine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a consumed batch through the pipeline, in order: bulk telemetry insert,
 * last-position UPSERT, Redis cache, stateless rules, violations+outbox (one
 * transaction), then forward to the processed topic.
 */
@Component
public class TelemetryProcessor {

    private final TelemetryWriter telemetryWriter;
    private final LastPositionWriter lastPositionWriter;
    private final PositionCache positionCache;
    private final RuleEngine ruleEngine;
    private final ViolationWriter violationWriter;
    private final ProcessedForwarder processedForwarder;
    private final Counter persisted;
    private final Counter violationsProduced;
    private final Counter late;
    private final Timer lateness;

    /**
     * Older than this and a reading is history, not a position.
     *
     * <p>It still goes to the hypertable, the rules and the trip — what it does not do is
     * refresh the "where is it now" cache. A minute is generous next to a one-second tick and
     * short enough that a device coming back from a gap cannot make the fleet appear to be
     * somewhere it left an hour ago.
     */
    private static final Duration LIVE_WINDOW = Duration.ofMinutes(1);

    public TelemetryProcessor(TelemetryWriter telemetryWriter,
                              LastPositionWriter lastPositionWriter,
                              PositionCache positionCache,
                              RuleEngine ruleEngine,
                              ViolationWriter violationWriter,
                              ProcessedForwarder processedForwarder,
                              MeterRegistry registry) {
        this.telemetryWriter = telemetryWriter;
        this.lastPositionWriter = lastPositionWriter;
        this.positionCache = positionCache;
        this.ruleEngine = ruleEngine;
        this.violationWriter = violationWriter;
        this.processedForwarder = processedForwarder;
        this.persisted = Counter.builder("telemetry.persisted")
                .description("Telemetry rows written to the hypertable").register(registry);
        this.violationsProduced = Counter.builder("violation.produced")
                .description("Violations produced by stateless rules").register(registry);
        this.late = Counter.builder("telemetry.late")
                .description("Readings that arrived too late to count as a current position")
                .register(registry);
        this.lateness = Timer.builder("telemetry.lateness")
                .description("Gap between when a reading was recorded and when it was processed")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public void process(List<TelemetryEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        telemetryWriter.insertBatch(events);
        persisted.increment(events.size());

        Instant now = Instant.now();
        for (TelemetryEvent e : events) {
            if (e.ts() != null) {
                lateness.record(Duration.between(e.ts(), now).isNegative()
                        ? Duration.ZERO : Duration.between(e.ts(), now));
            }
        }

        List<TelemetryEvent> latest = latestPerVehicle(events);
        // The table's UPSERT refuses to move a row backwards in time on its own; the cache
        // has no such guard, so the filter is applied before it.
        lastPositionWriter.upsertBatch(latest);

        List<TelemetryEvent> current = new ArrayList<>(latest.size());
        for (TelemetryEvent e : latest) {
            if (e.ts() == null || e.ts().isAfter(now.minus(LIVE_WINDOW))) {
                current.add(e);
            } else {
                late.increment();
            }
        }
        positionCache.cacheLatest(current);

        List<ViolationEvent> violations = ruleEngine.evaluateBatch(events);
        if (!violations.isEmpty()) {
            violationWriter.persistBatch(violations);
            violationsProduced.increment(violations.size());
        }

        processedForwarder.forward(events);
    }

    /** Keep only the newest reading per vehicle (for last-position + cache). */
    private List<TelemetryEvent> latestPerVehicle(List<TelemetryEvent> events) {
        Map<Long, TelemetryEvent> latest = new LinkedHashMap<>();
        for (TelemetryEvent e : events) {
            latest.merge(e.vehicleId(), e,
                    (a, b) -> a.ts() == null || (b.ts() != null && b.ts().isAfter(a.ts())) ? b : a);
        }
        return new ArrayList<>(latest.values());
    }
}
