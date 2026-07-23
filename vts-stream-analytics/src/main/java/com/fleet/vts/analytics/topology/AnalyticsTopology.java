package com.fleet.vts.analytics.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.vts.analytics.config.AnalyticsProperties;
import com.fleet.vts.analytics.geofence.GeofenceRegistry;
import com.fleet.vts.analytics.rules.GeofenceRule;
import com.fleet.vts.analytics.rules.HarshBrakingRule;
import com.fleet.vts.analytics.rules.IdlingRule;
import com.fleet.vts.analytics.rules.TripRule;
import com.fleet.vts.analytics.rules.VehicleRuleRegistry;
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
    private final AnalyticsProperties.EventTime eventTime;
    private final VehicleRuleRegistry rules;
    private final Serde<TelemetryEvent> telemetrySerde;
    private final Serde<ViolationEvent> violationSerde;
    private final Serde<GeofenceEvent> geofenceSerde;
    private final Serde<TripEvent> tripSerde;
    private final Serde<SpeedWindowAgg> speedAggSerde;
    private final Serde<GeofenceState> geofenceStateSerde;
    private final Serde<TripState> tripStateSerde;

    public AnalyticsTopology(GeofenceRegistry geofenceRegistry, VehicleRuleRegistry rules,
                            ObjectMapper mapper, AnalyticsProperties properties) {
        this.geofenceRegistry = geofenceRegistry;
        this.rules = rules;
        this.eventTime = properties.getEventTime();
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

        Produced<String, ViolationEvent> toViolation = Produced.with(Serdes.String(), violationSerde);

        // Each rule filters on its own applicability rather than sharing one "not a
        // helicopter" stream. The blanket filter was applied to braking, speeding and
        // geofences but not to idling, so helicopters collected idling violations while
        // being exempt from everything else. Now the question each rule asks is the one it
        // means -- "does this rule apply to this vehicle's type?" -- answered from the same
        // rule_assignment rows the processing service reads.
        KStream<String, TelemetryEvent> harshBraking =
                raw.filter((k, e) -> rules.applies(RuleType.HARSH_BRAKING.name(), k));
        KStream<String, TelemetryEvent> idling =
                raw.filter((k, e) -> rules.applies(RuleType.IDLING.name(), k));
        KStream<String, TelemetryEvent> geofence =
                raw.filter((k, e) -> rules.applies(RuleType.GEOFENCE_ENTER.name(), k));
        KStream<String, TelemetryEvent> sustainedSpeeding =
                raw.filter((k, e) -> rules.applies(RuleType.SUSTAINED_SPEEDING.name(), k));

        // Stateful rules (Processor API + RocksDB state stores)
        harshBraking.process(new HarshBrakingRule(rules)).to(Topics.VIOLATION, toViolation);
        idling.process(new IdlingRule()).to(Topics.VIOLATION, toViolation);
        geofence.process(new GeofenceRule(geofenceRegistry, geofenceStateSerde))
                .to(Topics.GEOFENCE_EVENT, Produced.with(Serdes.String(), geofenceSerde));

        // Trips stay on the full stream: a flight is a trip, and a trip is not a violation.
        raw.process(new TripRule(tripStateSerde, eventTime.getTripCloseGrace(),
                        eventTime.getMaxTripDuration()))
                .to(Topics.TRIP, Produced.with(Serdes.String(), tripSerde));

        // SUSTAINED_SPEEDING: 5-min TUMBLING window; fire once per window when
        // >= 80% of readings exceed the limit.
        //
        // This was a hopping window advancing every minute, which meant a vehicle
        // cruising over the limit emitted a violation every single minute — with a
        // third of the fleet permanently speeding that alone was ~33 events/min and
        // dominated the violation stream. A tumbling window emits at most once per
        // 5 minutes per vehicle, matching the rule's cooldown_seconds (300).
        sustainedSpeeding.filter((k, e) -> e != null && e.speedKmh() != null)
                .groupByKey(Grouped.with(Serdes.String(), telemetrySerde))
                // Grace was zero, which was correct only while event time and arrival time were
                // the same thing. With a device channel they are not: a reading recorded at 09:58
                // can arrive at 10:07, and with no grace it is silently dropped from the 09:55
                // window it belongs to. The cost is that the window now emits that much later.
                .windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofMinutes(5), eventTime.getGrace()))
                .aggregate(SpeedWindowAgg::empty,
                        (k, e, agg) -> agg.add(e, rules.speedLimit(k)),
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
        double limit = rules.speedLimit(vehicleId);   // per-type: car 110, motorcycle 90, truck 80
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
