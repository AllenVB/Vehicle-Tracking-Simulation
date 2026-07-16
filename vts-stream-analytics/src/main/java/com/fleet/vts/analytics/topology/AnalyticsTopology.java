package com.fleet.vts.analytics.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.vts.analytics.geofence.GeofenceRegistry;
import com.fleet.vts.analytics.rules.GeofenceRule;
import com.fleet.vts.analytics.rules.HarshBrakingRule;
import com.fleet.vts.analytics.rules.HelicopterRegistry;
import com.fleet.vts.analytics.rules.IdlingRule;
import com.fleet.vts.analytics.rules.SpeedLimitRegistry;
import com.fleet.vts.analytics.rules.TripRule;
import com.fleet.vts.analytics.state.GeofenceState;
import com.fleet.vts.analytics.state.SpeedWindowAgg;
import com.fleet.vts.analytics.state.TripState;
import com.fleet.vts.common.enums.RuleType;
import com.fleet.vts.common.enums.Severity;
import com.fleet.vts.common.event.GeofenceEvent;
import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.event.TripEvent;
import com.fleet.vts.common.event.ViolationEvent;
import com.fleet.vts.common.topic.Topics;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Suppressed;
import org.apache.kafka.streams.kstream.Suppressed.BufferConfig;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

import static com.fleet.vts.analytics.config.Serdes.json;

/**
 * The Kafka Streams topology. Reads {@code vehicle.telemetry.raw} once and fans
 * it into the stateful rules, writing to violation / geofence / trip topics.
 */
@Component
public class AnalyticsTopology {

    private static final int MIN_WINDOW_SAMPLES = 5;
    private static final double SUSTAINED_RATIO = 0.8;

    private final GeofenceRegistry geofenceRegistry;
    private final SpeedLimitRegistry speedLimits;
    private final HelicopterRegistry helicopters;
    private final Serde<TelemetryEvent> telemetrySerde;
    private final Serde<ViolationEvent> violationSerde;
    private final Serde<GeofenceEvent> geofenceSerde;
    private final Serde<TripEvent> tripSerde;
    private final Serde<SpeedWindowAgg> speedAggSerde;
    private final Serde<GeofenceState> geofenceStateSerde;
    private final Serde<TripState> tripStateSerde;

    public AnalyticsTopology(GeofenceRegistry geofenceRegistry, SpeedLimitRegistry speedLimits,
                            HelicopterRegistry helicopters, ObjectMapper mapper) {
        this.geofenceRegistry = geofenceRegistry;
        this.speedLimits = speedLimits;
        this.helicopters = helicopters;
        this.telemetrySerde = json(TelemetryEvent.class, mapper);
        this.violationSerde = json(ViolationEvent.class, mapper);
        this.geofenceSerde = json(GeofenceEvent.class, mapper);
        this.tripSerde = json(TripEvent.class, mapper);
        this.speedAggSerde = json(SpeedWindowAgg.class, mapper);
        this.geofenceStateSerde = json(GeofenceState.class, mapper);
        this.tripStateSerde = json(TripState.class, mapper);
    }

    @Autowired
    public void buildPipeline(StreamsBuilder builder) {
        KStream<String, TelemetryEvent> raw = builder.stream(Topics.TELEMETRY_RAW,
                Consumed.with(Serdes.String(), telemetrySerde));

        // Helicopters fly, so the road-based rules below skip them. Trip detection and
        // idling run on the full stream (a flight is a trip; a landed helicopter can idle).
        KStream<String, TelemetryEvent> road = raw.filter((k, e) -> !helicopters.isHelicopter(k));

        Produced<String, ViolationEvent> toViolation = Produced.with(Serdes.String(), violationSerde);

        // Stateful rules (Processor API + RocksDB state stores)
        road.process(new HarshBrakingRule()).to(Topics.VIOLATION, toViolation);
        raw.process(new IdlingRule()).to(Topics.VIOLATION, toViolation);
        road.process(new GeofenceRule(geofenceRegistry, geofenceStateSerde))
                .to(Topics.GEOFENCE_EVENT, Produced.with(Serdes.String(), geofenceSerde));
        raw.process(new TripRule(tripStateSerde))
                .to(Topics.TRIP, Produced.with(Serdes.String(), tripSerde));

        // SUSTAINED_SPEEDING: 5-min TUMBLING window; fire once per window when
        // >= 80% of readings exceed the limit.
        //
        // This was a hopping window advancing every minute, which meant a vehicle
        // cruising over the limit emitted a violation every single minute — with a
        // third of the fleet permanently speeding that alone was ~33 events/min and
        // dominated the violation stream. A tumbling window emits at most once per
        // 5 minutes per vehicle, matching the rule's cooldown_seconds (300).
        road.filter((k, e) -> e != null && e.speedKmh() != null)
                .groupByKey(Grouped.with(Serdes.String(), telemetrySerde))
                .windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofMinutes(5), Duration.ZERO))
                .aggregate(SpeedWindowAgg::empty,
                        (k, e, agg) -> agg.add(e, speedLimits.forVehicle(k)),
                        Materialized.<String, SpeedWindowAgg, org.apache.kafka.streams.state.WindowStore<org.apache.kafka.common.utils.Bytes, byte[]>>
                                        as("sustained-speeding-store")
                                .withKeySerde(Serdes.String())
                                .withValueSerde(speedAggSerde))
                .suppress(Suppressed.untilWindowCloses(BufferConfig.unbounded()))
                .toStream()
                .filter((wk, agg) -> agg != null
                        && agg.total() >= MIN_WINDOW_SAMPLES
                        && agg.ratioOver() >= SUSTAINED_RATIO)
                .map((wk, agg) -> KeyValue.pair(wk.key(), sustainedViolation(wk.key(), agg, wk.window().end())))
                .to(Topics.VIOLATION, toViolation);
    }

    private ViolationEvent sustainedViolation(String vehicleId, SpeedWindowAgg agg, long windowEnd) {
        double limit = speedLimits.forVehicle(vehicleId);   // per-type: car 110, motorcycle 90, truck 80
        return ViolationEvent.builder()
                .tenantId(agg.tenantId())
                .vehicleId(Long.valueOf(vehicleId))
                .ruleCode(RuleType.SUSTAINED_SPEEDING.name())
                .ruleType(RuleType.SUSTAINED_SPEEDING)
                .severity(Severity.HIGH)
                .occurredAt(Instant.ofEpochMilli(windowEnd))
                .value(agg.ratioOver() * 100.0)
                .threshold(limit)
                .lat(agg.lastLat())
                .lon(agg.lastLon())
                .build();
    }
}
