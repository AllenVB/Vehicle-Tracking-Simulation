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
import org.springframework.stereotype.Component;

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
    }

    public void process(List<TelemetryEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        telemetryWriter.insertBatch(events);
        persisted.increment(events.size());

        List<TelemetryEvent> latest = latestPerVehicle(events);
        lastPositionWriter.upsertBatch(latest);
        positionCache.cacheLatest(latest);

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
