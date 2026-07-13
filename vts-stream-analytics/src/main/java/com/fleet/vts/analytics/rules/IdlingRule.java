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
 * IDLING: engine on and speed 0 continuously for 10 minutes. The store keeps the
 * timestamp idling began; a sentinel (-1) marks that a violation was already
 * emitted so it fires once per idle stretch. Any movement resets the state.
 */
public class IdlingRule implements ProcessorSupplier<String, TelemetryEvent, String, ViolationEvent> {

    public static final String STORE = "idling-since";
    private static final long IDLE_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final long ALERTED = -1L;

    @Override
    public Processor<String, TelemetryEvent, String, ViolationEvent> get() {
        return new Processor<>() {
            private KeyValueStore<String, Long> store;
            private ProcessorContext<String, ViolationEvent> context;

            @Override
            public void init(ProcessorContext<String, ViolationEvent> context) {
                this.context = context;
                this.store = context.getStateStore(STORE);
            }

            @Override
            public void process(Record<String, TelemetryEvent> record) {
                TelemetryEvent e = record.value();
                if (e == null || e.ts() == null) {
                    return;
                }
                boolean idling = Boolean.TRUE.equals(e.engineOn())
                        && e.speedKmh() != null && e.speedKmh() == 0;
                if (!idling) {
                    store.delete(record.key());
                    return;
                }
                long ts = e.ts().toEpochMilli();
                Long since = store.get(record.key());
                if (since == null) {
                    store.put(record.key(), ts);
                } else if (since != ALERTED && ts - since >= IDLE_MILLIS) {
                    ViolationEvent v = Violations.of(e, RuleType.IDLING, Severity.LOW,
                            (ts - since) / 1000.0, (double) (IDLE_MILLIS / 1000));
                    context.forward(new Record<>(record.key(), v, record.timestamp()));
                    store.put(record.key(), ALERTED);
                }
            }
        };
    }

    @Override
    public Set<StoreBuilder<?>> stores() {
        return Set.of(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(STORE), Serdes.String(), Serdes.Long()));
    }
}
