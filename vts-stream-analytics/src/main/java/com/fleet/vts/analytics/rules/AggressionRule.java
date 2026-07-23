package com.fleet.vts.analytics.rules;

import com.fleet.vts.analytics.state.AggressionState;
import com.fleet.vts.common.enums.Severity;
import com.fleet.vts.common.event.ViolationEvent;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.ProcessorSupplier;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * COMPOUND EVENT PROCESSING: "aggressive driving" is an escalation neither underlying rule raises
 * on its own — {@code threshold} escalation-worthy violations (harsh braking, sustained speeding)
 * from one vehicle within a rolling {@code window}. It reads the merged violation stream and, when
 * a vehicle crosses the threshold, emits one CRITICAL {@link ViolationEvent}.
 *
 * <p>Written as a Processor rather than a windowed aggregation on purpose: a fixed tumbling window
 * misses two acts that straddle its boundary (a brake at 13:06 and another at 13:11 fall in
 * different ten-minute buckets and never meet), so the alert fired seldom and unpredictably. A
 * per-key store of recent timestamps gives the true "within N minutes of each other" reading, and
 * a {@code cooldown} keeps one bad stretch from re-firing on every violation that follows.
 *
 * <p>The alert reuses ViolationEvent with a sentinel ruleId so it rides the existing
 * violation&nbsp;&rarr;&nbsp;WebSocket relay to the operator's map without the persister storing
 * it (its parts are each already persisted) or the trip score counting it twice.
 */
public class AggressionRule implements ProcessorSupplier<String, ViolationEvent, String, ViolationEvent> {

    public static final String STORE = "aggression-state";

    private final Serde<AggressionState> stateSerde;
    private final long windowMillis;
    private final long cooldownMillis;
    private final int threshold;
    private final long sentinelRuleId;
    private final String code;

    public AggressionRule(Serde<AggressionState> stateSerde, Duration window, Duration cooldown,
                          int threshold, long sentinelRuleId, String code) {
        this.stateSerde = stateSerde;
        this.windowMillis = window.toMillis();
        this.cooldownMillis = cooldown.toMillis();
        this.threshold = threshold;
        this.sentinelRuleId = sentinelRuleId;
        this.code = code;
    }

    @Override
    public Processor<String, ViolationEvent, String, ViolationEvent> get() {
        return new Processor<>() {
            private KeyValueStore<String, AggressionState> store;
            private ProcessorContext<String, ViolationEvent> context;

            @Override
            public void init(ProcessorContext<String, ViolationEvent> context) {
                this.context = context;
                this.store = context.getStateStore(STORE);
            }

            @Override
            public void process(Record<String, ViolationEvent> record) {
                ViolationEvent e = record.value();
                if (e == null) {
                    return;
                }
                long ts = record.timestamp();
                AggressionState st = store.get(record.key());
                if (st == null) {
                    st = new AggressionState();
                }

                long cutoff = ts - windowMillis;
                st.getTimes().removeIf(t -> t < cutoff);   // drop anything now outside the window
                st.getTimes().add(ts);
                if (e.tenantId() != null) {
                    st.setTenantId(e.tenantId());
                }
                if (e.lat() != null) {
                    st.setLat(e.lat());
                }
                if (e.lon() != null) {
                    st.setLon(e.lon());
                }

                if (st.getTimes().size() >= threshold && ts - st.getLastAlertTs() >= cooldownMillis) {
                    context.forward(new Record<>(record.key(),
                            escalation(record.key(), st, ts), record.timestamp()));
                    st.setLastAlertTs(ts);
                }
                store.put(record.key(), st);
            }

            private ViolationEvent escalation(String vehicleId, AggressionState st, long ts) {
                return ViolationEvent.builder()
                        .tenantId(st.getTenantId())
                        .vehicleId(Long.valueOf(vehicleId))
                        .ruleId(sentinelRuleId)
                        .ruleCode(code)
                        .severity(Severity.CRITICAL)
                        .occurredAt(Instant.ofEpochMilli(ts))
                        .value((double) st.getTimes().size())
                        .lat(st.getLat())
                        .lon(st.getLon())
                        .build();
            }
        };
    }

    @Override
    public Set<StoreBuilder<?>> stores() {
        return Set.of(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(STORE), Serdes.String(), stateSerde));
    }
}
