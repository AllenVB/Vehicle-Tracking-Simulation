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
 * and closes after a stretch without movement (detected on a later reading or by
 * a stream-time punctuator). Emits ONGOING on open and CLOSED with distance,
 * avg/max speed on close.
 */
public class TripRule implements ProcessorSupplier<String, TelemetryEvent, String, TripEvent> {

    public static final String STORE = "trip-state";

    /**
     * How long a vehicle must sit still before its trip is considered over.
     *
     * <p>This is bounded from both sides. It has to be SHORTER than the simulator's arrival
     * dwell (VehicleState.ARRIVAL_DWELL_SECONDS = 120 s) or trips never close at all: the
     * vehicle sets off again before the window elapses, the trip stays open forever, and
     * {@code trip}, {@code trip_point}, {@code stop_event} and driver scoring all stay empty.
     * It also has to be LONGER than a refuelling stop (60 s), so topping up the tank counts as
     * part of the journey rather than ending it.
     *
     * <p>90 s is the middle of that range. It was 5 minutes, sized when arrivals dwelled for
     * 5.5–12 minutes; shortening the dwell to 2 minutes is what forced this down.
     */
    private static final long STOP_MILLIS = Duration.ofSeconds(90).toMillis();

    /**
     * Consecutive readings that are further apart than this are a jump, not driving:
     * a real GPS glitch, an operator teleport, or a restart repositioning the vehicle.
     * At a 1-second tick even 120 km/h covers ~33 m, so 2 km is far beyond any legitimate
     * step. Counting jumps as distance inflates trip length — and with it every
     * km-normalised driver score, which is how a scoreboard quietly becomes nonsense.
     */
    private static final double MAX_STEP_KM = 2.0;

    private final Serde<TripState> stateSerde;

    /**
     * How long past {@link #STOP_MILLIS} the punctuator waits before closing a quiet trip.
     * See {@code AnalyticsProperties.EventTime.tripCloseGrace} for why it exists.
     */
    private final long closeGraceMillis;

    public TripRule(Serde<TripState> stateSerde, Duration closeGrace) {
        this.stateSerde = stateSerde;
        this.closeGraceMillis = closeGrace.toMillis();
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
                    } else if (ts < st.getLastMoveTs()) {
                        // Out of order: a buffered reading arriving behind live ones. Its speed
                        // still belongs to the trip, but walking the position backwards would
                        // add the same stretch of road to the distance a second time.
                        st.setOutOfOrderSamples(st.getOutOfOrderSamples() + 1);
                    } else {
                        double step = GeoUtils.haversineKm(
                                st.getLastLat(), st.getLastLon(), e.lat(), e.lon());
                        if (step <= MAX_STEP_KM) {   // a jump is repositioning, not distance driven
                            st.setDistanceKm(st.getDistanceKm() + step);
                        }
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

            /**
             * Close trips that stopped receiving movement.
             *
             * <p>{@code now} is stream time, which since the switch to event time is the newest
             * reading the partition has seen — from any vehicle. A device out of coverage is
             * therefore indistinguishable here from a vehicle that parked, so the punctuator
             * waits an extra grace before acting. The trip is still recorded as ending when the
             * vehicle last moved, not when the punctuator got around to it: the grace decides
             * when to look, not what to write.
             */
            private void closeStale(long streamTime) {
                List<KeyValue<String, TripState>> toClose = new ArrayList<>();
                try (KeyValueIterator<String, TripState> it = store.all()) {
                    while (it.hasNext()) {
                        KeyValue<String, TripState> kv = it.next();
                        if (kv.value.isOpen()
                                && streamTime - kv.value.getLastMoveTs() >= STOP_MILLIS + closeGraceMillis) {
                            toClose.add(kv);
                        }
                    }
                }
                for (KeyValue<String, TripState> kv : toClose) {
                    long endTs = kv.value.getLastMoveTs() + STOP_MILLIS;
                    context.forward(new Record<>(kv.key,
                            closed(kv.value, endTs, vehicleId(kv.key)), endTs));
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
