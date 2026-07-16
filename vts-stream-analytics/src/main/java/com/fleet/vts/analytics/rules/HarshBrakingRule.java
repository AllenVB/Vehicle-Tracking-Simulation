package com.fleet.vts.analytics.rules;

import com.fleet.vts.analytics.Violations;
import com.fleet.vts.common.enums.RuleType;
import com.fleet.vts.common.enums.Severity;
import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.event.ViolationEvent;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.ProcessorSupplier;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;

import java.time.Duration;
import java.util.Set;

/**
 * HARSH_BRAKING: a drop of more than 40 km/h between consecutive readings of the
 * same vehicle. Previous speed is kept in a per-vehicle KeyValueStore (RocksDB).
 *
 * <p>Emission is debounced per vehicle: a driver who brakes hard repeatedly yields
 * at most one violation per {@link #COOLDOWN_MILLIS} window (mirrors the rule's
 * {@code cooldown_seconds} = 120). Without this, a 1-second telemetry tick turns
 * every qualifying drop into an event and floods the violation stream.
 */
public class HarshBrakingRule implements ProcessorSupplier<String, TelemetryEvent, String, ViolationEvent> {

    public static final String STORE = "harsh-braking-prev-speed";
    public static final String COOLDOWN_STORE = "harsh-braking-cooldown";
    private static final int DROP_THRESHOLD = -40;
    private static final long COOLDOWN_MILLIS = Duration.ofSeconds(300).toMillis();

    @Override
    public Processor<String, TelemetryEvent, String, ViolationEvent> get() {
        return new Processor<>() {
            private KeyValueStore<String, Integer> prevSpeed;
            private KeyValueStore<String, Long> lastFired;
            private ProcessorContext<String, ViolationEvent> context;

            @Override
            public void init(ProcessorContext<String, ViolationEvent> context) {
                this.context = context;
                this.prevSpeed = context.getStateStore(STORE);
                this.lastFired = context.getStateStore(COOLDOWN_STORE);
            }

            @Override
            public void process(Record<String, TelemetryEvent> record) {
                TelemetryEvent e = record.value();
                if (e == null || e.speedKmh() == null) {
                    return;
                }
                Integer previous = prevSpeed.get(record.key());
                if (previous != null) {
                    int delta = e.speedKmh() - previous;
                    if (delta < DROP_THRESHOLD && passesCooldown(record.key(), record.timestamp())) {
                        ViolationEvent v = Violations.of(e, RuleType.HARSH_BRAKING,
                                Severity.HIGH, delta, (double) DROP_THRESHOLD);
                        context.forward(new Record<>(record.key(), v, record.timestamp()));
                    }
                }
                prevSpeed.put(record.key(), e.speedKmh());
            }

            /** True when this vehicle is outside its cooldown window (so it fires). */
            private boolean passesCooldown(String vehicleId, long ts) {
                Long last = lastFired.get(vehicleId);
                if (last != null && ts - last < COOLDOWN_MILLIS) {
                    return false;
                }
                lastFired.put(vehicleId, ts);
                return true;
            }
        };
    }

    @Override
    public Set<StoreBuilder<?>> stores() {
        return Set.of(
                Stores.keyValueStoreBuilder(
                        Stores.persistentKeyValueStore(STORE), Serdes.String(), Serdes.Integer()),
                Stores.keyValueStoreBuilder(
                        Stores.persistentKeyValueStore(COOLDOWN_STORE), Serdes.String(), Serdes.Long()));
    }
}
