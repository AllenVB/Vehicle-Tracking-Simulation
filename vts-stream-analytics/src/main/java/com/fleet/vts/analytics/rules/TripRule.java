package com.fleet.vts.analytics.rules;

import com.fleet.vts.analytics.GeoUtils;
import com.fleet.vts.analytics.state.TripState;
import com.fleet.vts.common.enums.TripStatus;
import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.event.TripEvent;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.ProcessorSupplier;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * TRIP_DETECTION: a trip opens when the vehicle starts moving with ignition on
 * and closes after 5 minutes without movement (detected on a later reading or by
 * a stream-time punctuator). Emits ONGOING on open and CLOSED with distance,
 * avg/max speed on close.
 */
public class TripRule implements ProcessorSupplier<String, TelemetryEvent, String, TripEvent> {

    public static final String STORE = "trip-state";
    private static final long STOP_MILLIS = Duration.ofMinutes(5).toMillis();

    private final Serde<TripState> stateSerde;

    public TripRule(Serde<TripState> stateSerde) {
        this.stateSerde = stateSerde;
    }

    @Override
    public Processor<String, TelemetryEvent, String, TripEvent> get() {
        return new Processor<>() {
            private KeyValueStore<String, TripState> store;
            private ProcessorContext<String, TripEvent> context;

            @Override
            public void init(ProcessorContext<String, TripEvent> context) {
                this.context = context;
                this.store = context.getStateStore(STORE);
                context.schedule(Duration.ofMinutes(1), PunctuationType.STREAM_TIME, this::closeStale);
            }

            @Override
            public void process(Record<String, TelemetryEvent> record) {
                TelemetryEvent e = record.value();
                if (e == null || e.ts() == null) {
                    return;
                }
                long ts = e.ts().toEpochMilli();
                boolean moving = Boolean.TRUE.equals(e.ignition())
                        && e.speedKmh() != null && e.speedKmh() > 0;
                TripState st = store.get(record.key());

                if (moving) {
                    if (st == null || !st.isOpen()) {
                        st = openTrip(e, ts);
                        context.forward(new Record<>(record.key(),
                                ongoing(st, e.vehicleId()), record.timestamp()));
                    } else {
                        st.setDistanceKm(st.getDistanceKm()
                                + GeoUtils.haversineKm(st.getLastLat(), st.getLastLon(), e.lat(), e.lon()));
                        st.setLastLat(e.lat());
                        st.setLastLon(e.lon());
                        st.setLastMoveTs(ts);
                    }
                    st.setMaxSpeed(Math.max(st.getMaxSpeed(), e.speedKmh()));
                    st.setSpeedSum(st.getSpeedSum() + e.speedKmh());
                    st.setSampleCount(st.getSampleCount() + 1);
                    store.put(record.key(), st);
                } else if (st != null && st.isOpen()) {
                    if (ts - st.getLastMoveTs() >= STOP_MILLIS) {
                        context.forward(new Record<>(record.key(),
                                closed(st, ts, vehicleId(record.key())), record.timestamp()));
                        store.delete(record.key());
                    } else {
                        store.put(record.key(), st);
                    }
                }
            }

            /** Close trips that stopped receiving movement (stream time advanced). */
            private void closeStale(long now) {
                List<KeyValue<String, TripState>> toClose = new ArrayList<>();
                try (KeyValueIterator<String, TripState> it = store.all()) {
                    while (it.hasNext()) {
                        KeyValue<String, TripState> kv = it.next();
                        if (kv.value.isOpen() && now - kv.value.getLastMoveTs() >= STOP_MILLIS) {
                            toClose.add(kv);
                        }
                    }
                }
                for (KeyValue<String, TripState> kv : toClose) {
                    context.forward(new Record<>(kv.key, closed(kv.value, now, vehicleId(kv.key)), now));
                    store.delete(kv.key);
                }
            }

            private TripState openTrip(TelemetryEvent e, long ts) {
                TripState st = new TripState();
                st.setOpen(true);
                st.setTenantId(e.tenantId());
                st.setStartTs(ts);
                st.setStartLat(e.lat());
                st.setStartLon(e.lon());
                st.setLastLat(e.lat());
                st.setLastLon(e.lon());
                st.setLastMoveTs(ts);
                return st;
            }

            /** The stream is keyed by vehicleId; the punctuator has only the key to go on. */
            private Long vehicleId(String key) {
                try {
                    return Long.valueOf(key);
                } catch (NumberFormatException e) {
                    return null;
                }
            }

            private TripEvent ongoing(TripState st, Long vehicleId) {
                return TripEvent.builder()
                        .tenantId(st.getTenantId())
                        .vehicleId(vehicleId)
                        .status(TripStatus.ONGOING)
                        .startedAt(Instant.ofEpochMilli(st.getStartTs()))
                        .startLat(st.getStartLat())
                        .startLon(st.getStartLon())
                        .build();
            }

            private TripEvent closed(TripState st, long endTs, Long vehicleId) {
                double avg = st.getSampleCount() == 0 ? 0 : st.getSpeedSum() / st.getSampleCount();
                return TripEvent.builder()
                        .tenantId(st.getTenantId())
                        .vehicleId(vehicleId)
                        .status(TripStatus.CLOSED)
                        .startedAt(Instant.ofEpochMilli(st.getStartTs()))
                        .endedAt(Instant.ofEpochMilli(endTs))
                        .startLat(st.getStartLat())
                        .startLon(st.getStartLon())
                        .endLat(st.getLastLat())
                        .endLon(st.getLastLon())
                        .distanceKm(st.getDistanceKm())
                        .avgSpeedKmh(avg)
                        .maxSpeedKmh(st.getMaxSpeed())
                        .violationCount(0)
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
