package com.fleet.vts.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fleet.vts.analytics.config.AnalyticsProperties;
import com.fleet.vts.analytics.config.EventTimeExtractor;
import com.fleet.vts.analytics.config.Serdes;
import com.fleet.vts.analytics.geofence.GeofenceRegistry;
import com.fleet.vts.analytics.rules.VehicleRuleRegistry;
import com.fleet.vts.analytics.topology.AnalyticsTopology;
import com.fleet.vts.common.enums.TripStatus;
import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.event.TripEvent;
import com.fleet.vts.common.event.ViolationEvent;
import com.fleet.vts.common.topic.Topics;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What changes when the pipeline reads event time instead of arrival time.
 *
 * <p>Each test here is a case the platform got wrong for as long as its only source was a
 * simulator posting readings the moment it made them. They are written as a pair wherever
 * possible: the same input, once with grace and once without, so the difference is the
 * assertion rather than a claim in a comment.
 */
class EventTimeTopologyTest {

    private static final Instant T0 = Instant.parse("2026-07-22T09:00:00Z");

    /** 42 is the vehicle under test; 43 exists only to push stream time forward. */
    private static final Map<String, Double> TWO_LAND_VEHICLES = Map.of(
            "SPEED_LIMIT:42", 90.0,
            "SUSTAINED_SPEEDING:42", 90.0,
            "HARSH_BRAKING:42", -40.0,
            "IDLING:42", Double.NaN,
            "GEOFENCE_ENTER:42", Double.NaN,
            "SPEED_LIMIT:43", 90.0,
            "SUSTAINED_SPEEDING:43", 90.0,
            "HARSH_BRAKING:43", -40.0,
            "IDLING:43", Double.NaN,
            "GEOFENCE_ENTER:43", Double.NaN);

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private TopologyTestDriver driver;
    private TestInputTopic<String, TelemetryEvent> input;

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.close();
        }
    }

    // ── Trips survive a coverage gap ─────────────────────────────────────────

    @Test
    void aDeviceOutOfCoverageDoesNotSplitItsTripInTwo() {
        start(grace(Duration.ofMinutes(15)));

        driveOneMinute("42", T0);
        // Ten minutes of silence from 42 while the rest of the fleet keeps reporting: this is
        // what pushes stream time past the stop window without 42 having stopped.
        driveOneMinute("43", T0.plus(Duration.ofMinutes(10)));
        // 42 reconnects and empties its buffer — readings from the minutes it was away.
        driveOneMinute("42", T0.plus(Duration.ofMinutes(2)));

        List<TripEvent> trips = tripsFor("42");
        assertThat(trips).hasSize(1);
        assertThat(trips.get(0).status()).isEqualTo(TripStatus.ONGOING);
    }

    @Test
    void withoutGraceTheSameGapClosesTheTripEarly() {
        start(grace(Duration.ZERO));

        driveOneMinute("42", T0);
        driveOneMinute("43", T0.plus(Duration.ofMinutes(10)));
        driveOneMinute("42", T0.plus(Duration.ofMinutes(2)));

        List<TripEvent> trips = tripsFor("42");
        // One journey, recorded as two: closed while the vehicle was still driving, then
        // reopened by its own buffered readings. Every distance and score built on this is
        // wrong, and nothing anywhere reports an error.
        assertThat(trips).extracting(TripEvent::status)
                .containsExactly(TripStatus.ONGOING, TripStatus.CLOSED, TripStatus.ONGOING);
    }

    // ── Windows count a reading in the window it belongs to ──────────────────

    @Test
    void aLateReadingCountsTowardsTheWindowItWasRecordedIn() {
        start(grace(Duration.ofMinutes(15)));

        // Six readings over the limit, all inside the 09:00–09:05 window. The last one is
        // handed over eight minutes after that window closed.
        for (int i = 0; i < 5; i++) {
            send("42", speeding(T0.plusSeconds(i * 10L)));
        }
        driveOneMinute("43", T0.plus(Duration.ofMinutes(8)));
        send("42", speeding(T0.plusSeconds(70)));

        // Push stream time past window end + grace so the suppressed window emits.
        driveOneMinute("43", T0.plus(Duration.ofMinutes(25)));

        List<ViolationEvent> violations = violationsFor("42");
        assertThat(violations).isNotEmpty();
        ViolationEvent sustained = violations.stream()
                .filter(v -> "SUSTAINED_SPEEDING".equals(v.ruleCode()))
                .findFirst()
                .orElseThrow();
        // Attributed to the window the driving happened in, not to when the record arrived.
        assertThat(sustained.occurredAt()).isEqualTo(T0.plus(Duration.ofMinutes(5)));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private AnalyticsProperties grace(Duration grace) {
        AnalyticsProperties properties = new AnalyticsProperties();
        properties.getEventTime().setGrace(grace);
        properties.getEventTime().setTripCloseGrace(grace);
        return properties;
    }

    private void start(AnalyticsProperties properties) {
        StreamsBuilder builder = new StreamsBuilder();
        new AnalyticsTopology(new GeofenceRegistry(List.of()),
                new VehicleRuleRegistry(TWO_LAND_VEHICLES), mapper, properties)
                .buildPipeline(builder);

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "event-time-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        props.put(StreamsConfig.DEFAULT_TIMESTAMP_EXTRACTOR_CLASS_CONFIG, EventTimeExtractor.class);

        driver = new TopologyTestDriver(builder.build(), props);
        input = driver.createInputTopic(Topics.TELEMETRY_RAW,
                new StringSerializer(), Serdes.json(TelemetryEvent.class, mapper).serializer());
    }

    /** Six readings ten seconds apart, all moving — one minute of ordinary driving. */
    private void driveOneMinute(String vehicleId, Instant from) {
        for (int i = 0; i < 6; i++) {
            send(vehicleId, moving(Long.parseLong(vehicleId), 70, from.plusSeconds(i * 10L)));
        }
    }

    private void send(String vehicleId, TelemetryEvent event) {
        input.pipeInput(vehicleId, event);
    }

    private TelemetryEvent speeding(Instant ts) {
        return moving(42L, 130, ts);
    }

    private TelemetryEvent moving(long vehicleId, int speedKmh, Instant ts) {
        return TelemetryEvent.builder()
                .tenantId(1L).vehicleId(vehicleId).deviceId(7L)
                .imei("00000000000000" + vehicleId).ts(ts)
                .lat(39.90).lon(32.80).speedKmh(speedKmh).heading(90)
                .battery(90).fuelPct(80).engineOn(true).ignition(true)
                .odometerKm(1000L).correlationId("c1").build();
    }

    private List<TripEvent> tripsFor(String vehicleId) {
        TestOutputTopic<String, TripEvent> topic = driver.createOutputTopic(Topics.TRIP,
                new StringDeserializer(), Serdes.json(TripEvent.class, mapper).deserializer());
        return topic.readKeyValuesToList().stream()
                .filter(kv -> vehicleId.equals(kv.key))
                .map(kv -> kv.value)
                .toList();
    }

    private List<ViolationEvent> violationsFor(String vehicleId) {
        TestOutputTopic<String, ViolationEvent> topic = driver.createOutputTopic(Topics.VIOLATION,
                new StringDeserializer(), Serdes.json(ViolationEvent.class, mapper).deserializer());
        return topic.readKeyValuesToList().stream()
                .filter(kv -> vehicleId.equals(kv.key))
                .map(kv -> kv.value)
                .toList();
    }
}
