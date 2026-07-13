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

import java.util.Set;

/**
 * HARSH_BRAKING: a drop of more than 40 km/h between consecutive readings of the
 * same vehicle. Previous speed is kept in a per-vehicle KeyValueStore (RocksDB).
 */
public class HarshBrakingRule implements ProcessorSupplier<String, TelemetryEvent, String, ViolationEvent> {

    public static final String STORE = "harsh-braking-prev-speed";
    private static final int DROP_THRESHOLD = -40;

    @Override
    public Processor<String, TelemetryEvent, String, ViolationEvent> get() {
        return new Processor<>() {
            private KeyValueStore<String, Integer> store;
            private ProcessorContext<String, ViolationEvent> context;

            @Override
            public void init(ProcessorContext<String, ViolationEvent> context) {
                this.context = context;
                this.store = context.getStateStore(STORE);
            }

            @Override
            public void process(Record<String, TelemetryEvent> record) {
                TelemetryEvent e = record.value();
                if (e == null || e.speedKmh() == null) {
                    return;
                }
                Integer previous = store.get(record.key());
                if (previous != null) {
                    int delta = e.speedKmh() - previous;
                    if (delta < DROP_THRESHOLD) {
                        ViolationEvent v = Violations.of(e, RuleType.HARSH_BRAKING,
                                Severity.HIGH, delta, (double) DROP_THRESHOLD);
                        context.forward(new Record<>(record.key(), v, record.timestamp()));
                    }
                }
                store.put(record.key(), e.speedKmh());
            }
        };
    }

    @Override
    public Set<StoreBuilder<?>> stores() {
        return Set.of(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(STORE), Serdes.String(), Serdes.Integer()));
    }
}
