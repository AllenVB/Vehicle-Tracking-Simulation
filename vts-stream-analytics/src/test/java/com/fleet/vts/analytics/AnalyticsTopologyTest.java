package com.fleet.vts.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fleet.vts.analytics.config.Serdes;
import com.fleet.vts.analytics.geofence.GeofenceRegistry;
import com.fleet.vts.analytics.rules.VehicleRuleRegistry;
import com.fleet.vts.analytics.topology.AnalyticsTopology;
import com.fleet.vts.common.enums.GeofenceEventType;
import com.fleet.vts.common.enums.RuleType;
import com.fleet.vts.common.enums.TripStatus;
import com.fleet.vts.common.event.GeofenceEvent;
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
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Topology unit tests using TopologyTestDriver (no Kafka broker needed). */
class AnalyticsTopologyTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private TopologyTestDriver driver;
    private TestInputTopic<String, TelemetryEvent> input;

    /**
     * Test aracı 42: kara aracı, yani her kural uygulanır. Bir kuralın anahtarının
     * bulunmaması o kuralın araca uygulanmadığı anlamına gelir; eşiksiz (pencere tabanlı)
     * kurallar NaN taşır. Sert fren -40'ta tutuldu — tipe göre eşikler devreye girmeden
     * önceki değer, böylece mevcut testlerin beklentisi korunur.
     */
    private static final Map<String, Double> LAND_VEHICLE_42 = Map.of(
            "SPEED_LIMIT:42", 80.0,
            "SUSTAINED_SPEEDING:42", 80.0,
            "HARSH_BRAKING:42", -40.0,
            "IDLING:42", Double.NaN,
            "GEOFENCE_ENTER:42", Double.NaN);

    private void start(GeofenceRegistry registry) {
        start(registry, LAND_VEHICLE_42);
    }

    private void start(GeofenceRegistry registry, Map<String, Double> applicableRules) {
        StreamsBuilder builder = new StreamsBuilder();
        new AnalyticsTopology(registry, new VehicleRuleRegistry(applicableRules), mapper)
                .buildPipeline(builder);
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "analytics-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        driver = new TopologyTestDriver(builder.build(), props);
        input = driver.createInputTopic(Topics.TELEMETRY_RAW,
                new StringSerializer(), Serdes.json(TelemetryEvent.class, mapper).serializer());
    }

    private GeofenceRegistry emptyRegistry() {
        return new GeofenceRegistry(List.of());
    }

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.close();
        }
    }

    private TelemetryEvent event(long vehicleId, int speed, boolean engineOn, boolean ignition,
                                 double lat, double lon, Instant ts) {
        return TelemetryEvent.builder()
                .tenantId(1L).vehicleId(vehicleId).deviceId(7L)
                .imei("000000000000001").ts(ts)
                .lat(lat).lon(lon).speedKmh(speed).heading(90)
                .battery(90).fuelPct(80).engineOn(engineOn).ignition(ignition)
                .odometerKm(1000L).correlationId("c1").build();
    }

    private TestOutputTopic<String, ViolationEvent> violations() {
        return driver.createOutputTopic(Topics.VIOLATION,
                new StringDeserializer(), Serdes.json(ViolationEvent.class, mapper).deserializer());
    }

    /**
     * Helicopter 99: no road rule applies to it, so no key for it exists. Vehicle 42 stays
     * a land vehicle in the same fleet — the point is that they are told apart, not that
     * the topology is empty.
     */
    private static final Map<String, Double> LAND_42_AND_HELICOPTER_99 = Map.of(
            "SPEED_LIMIT:42", 80.0,
            "SUSTAINED_SPEEDING:42", 80.0,
            "HARSH_BRAKING:42", -40.0,
            "IDLING:42", Double.NaN,
            "GEOFENCE_ENTER:42", Double.NaN);

    @Test
    void helicopterProducesNoRoadViolations() {
        // The complaint this fixes: helicopters collected violations meant for road
        // vehicles. A helicopter decelerating hard and hovering is flying, not braking
        // badly or idling at a kerb.
        start(emptyRegistry(), LAND_42_AND_HELICOPTER_99);
        Instant t = Instant.parse("2026-07-13T10:00:00Z");

        // A drop far past any braking threshold.
        input.pipeInput("99", event(99, 250, true, true, 41.01, 28.97, t), t);
        input.pipeInput("99", event(99, 40, true, true, 41.01, 28.97, t.plusSeconds(5)), t.plusSeconds(5));
        // Stationary with the engine on, well past the 10-minute idling window.
        input.pipeInput("99", event(99, 0, true, true, 41.01, 28.97, t.plusSeconds(10)), t.plusSeconds(10));
        input.pipeInput("99", event(99, 0, true, true, 41.01, 28.97, t.plusSeconds(1200)), t.plusSeconds(1200));

        assertTrue(violations().readValuesToList().isEmpty(),
                "a helicopter must not produce road violations");
    }

    @Test
    void landVehicleStillProducesRoadViolationsAlongsideHelicopters() {
        // Guards the other direction: the exemption must not silence the fleet.
        start(emptyRegistry(), LAND_42_AND_HELICOPTER_99);
        Instant t = Instant.parse("2026-07-13T10:00:00Z");

        input.pipeInput("99", event(99, 250, true, true, 41.01, 28.97, t), t);
        input.pipeInput("99", event(99, 40, true, true, 41.01, 28.97, t.plusSeconds(1)), t.plusSeconds(1));
        input.pipeInput("42", event(42, 90, true, true, 41.0, 29.0, t), t);
        input.pipeInput("42", event(42, 40, true, true, 41.0, 29.0, t.plusSeconds(5)), t.plusSeconds(5));

        List<ViolationEvent> out = violations().readValuesToList();
        assertEquals(1, out.size(), "only the land vehicle's brake is a violation");
        assertEquals(42L, out.get(0).vehicleId());
        assertEquals(RuleType.HARSH_BRAKING, out.get(0).ruleType());
    }

    @Test
    void harshBrakingThresholdComesFromTheVehiclesType() {
        // A 35 km/h drop is harsh for a truck (-30) and unremarkable for a motorcycle
        // (-50). One shared -40 could not express that.
        Map<String, Double> byType = Map.of("HARSH_BRAKING:7", -30.0, "HARSH_BRAKING:8", -50.0);
        start(emptyRegistry(), byType);
        Instant t = Instant.parse("2026-07-13T10:00:00Z");

        input.pipeInput("7", event(7, 80, true, true, 41.0, 29.0, t), t);
        input.pipeInput("7", event(7, 45, true, true, 41.0, 29.0, t.plusSeconds(1)), t.plusSeconds(1));
        input.pipeInput("8", event(8, 80, true, true, 41.0, 29.0, t), t);
        input.pipeInput("8", event(8, 45, true, true, 41.0, 29.0, t.plusSeconds(1)), t.plusSeconds(1));

        List<ViolationEvent> out = violations().readValuesToList();
        assertEquals(1, out.size(), "same 35 km/h drop: harsh for the truck, not for the motorcycle");
        assertEquals(7L, out.get(0).vehicleId());
        assertEquals(-30.0, out.get(0).threshold());
    }

    @Test
    void harshBrakingFiresOnSuddenDrop() {
        start(emptyRegistry());
        Instant t = Instant.parse("2026-07-13T10:00:00Z");
        input.pipeInput("42", event(42, 90, true, true, 41.0, 29.0, t), t);
        input.pipeInput("42", event(42, 40, true, true, 41.0, 29.0, t.plusSeconds(5)), t.plusSeconds(5));

        List<ViolationEvent> out = violations().readValuesToList();
        assertTrue(out.stream().anyMatch(v -> v.ruleType() == RuleType.HARSH_BRAKING),
                "expected a harsh braking violation");
    }

    @Test
    void harshBrakingIsDebouncedWithinCooldownWindow() {
        // Braking hard again seconds later must NOT produce a second violation;
        // once the 120s cooldown has passed it fires again.
        start(emptyRegistry());
        Instant t = Instant.parse("2026-07-13T10:00:00Z");

        input.pipeInput("42", event(42, 90, true, true, 41.0, 29.0, t), t);
        input.pipeInput("42", event(42, 40, true, true, 41.0, 29.0, t.plusSeconds(1)), t.plusSeconds(1)); // fires
        input.pipeInput("42", event(42, 90, true, true, 41.0, 29.0, t.plusSeconds(2)), t.plusSeconds(2));
        input.pipeInput("42", event(42, 40, true, true, 41.0, 29.0, t.plusSeconds(3)), t.plusSeconds(3)); // suppressed
        input.pipeInput("42", event(42, 90, true, true, 41.0, 29.0, t.plusSeconds(4)), t.plusSeconds(4));
        input.pipeInput("42", event(42, 40, true, true, 41.0, 29.0, t.plusSeconds(5)), t.plusSeconds(5)); // suppressed

        long during = violations().readValuesToList().stream()
                .filter(v -> v.ruleType() == RuleType.HARSH_BRAKING).count();
        assertEquals(1, during, "three hard brakes inside the cooldown must yield one violation");

        // Past the 300s window the next hard brake fires again.
        input.pipeInput("42", event(42, 90, true, true, 41.0, 29.0, t.plusSeconds(310)), t.plusSeconds(310));
        input.pipeInput("42", event(42, 40, true, true, 41.0, 29.0, t.plusSeconds(311)), t.plusSeconds(311));

        long after = violations().readValuesToList().stream()
                .filter(v -> v.ruleType() == RuleType.HARSH_BRAKING).count();
        assertEquals(1, after, "a hard brake after the cooldown fires again");
    }

    @Test
    void noHarshBrakingOnGentleSlowdown() {
        start(emptyRegistry());
        Instant t = Instant.parse("2026-07-13T10:00:00Z");
        input.pipeInput("42", event(42, 60, true, true, 41.0, 29.0, t), t);
        input.pipeInput("42", event(42, 40, true, true, 41.0, 29.0, t.plusSeconds(5)), t.plusSeconds(5));

        assertFalse(violations().readValuesToList().stream()
                .anyMatch(v -> v.ruleType() == RuleType.HARSH_BRAKING));
    }

    @Test
    void idlingFiresAfterTenMinutes() {
        start(emptyRegistry());
        Instant t = Instant.parse("2026-07-13T10:00:00Z");
        input.pipeInput("42", event(42, 0, true, false, 41.0, 29.0, t), t);
        input.pipeInput("42", event(42, 0, true, false, 41.0, 29.0, t.plusSeconds(605)), t.plusSeconds(605));

        assertTrue(violations().readValuesToList().stream()
                .anyMatch(v -> v.ruleType() == RuleType.IDLING), "expected an idling violation");
    }

    @Test
    void geofenceEnterAndExitAreEmitted() {
        GeometryFactory gf = new GeometryFactory();
        Polygon zone = gf.createPolygon(new Coordinate[]{
                new Coordinate(28.99, 40.99), new Coordinate(29.01, 40.99),
                new Coordinate(29.01, 41.01), new Coordinate(28.99, 41.01),
                new Coordinate(28.99, 40.99)});
        start(new GeofenceRegistry(List.of(new GeofenceRegistry.Area(100L, "Test Zone", 1L, zone))));

        TestOutputTopic<String, GeofenceEvent> geofence = driver.createOutputTopic(
                Topics.GEOFENCE_EVENT, new StringDeserializer(),
                Serdes.json(GeofenceEvent.class, mapper).deserializer());

        Instant t = Instant.parse("2026-07-13T10:00:00Z");
        input.pipeInput("42", event(42, 30, true, true, 41.0, 29.0, t), t);                 // inside
        input.pipeInput("42", event(42, 30, true, true, 42.0, 30.0, t.plusSeconds(5)), t.plusSeconds(5)); // outside

        List<GeofenceEvent> out = geofence.readValuesToList();
        assertTrue(out.stream().anyMatch(g -> g.eventType() == GeofenceEventType.ENTER));
        assertTrue(out.stream().anyMatch(g -> g.eventType() == GeofenceEventType.EXIT));
    }

    @Test
    void tripOpensAndClosesAfterStop() {
        start(emptyRegistry());
        TestOutputTopic<String, TripEvent> trips = driver.createOutputTopic(
                Topics.TRIP, new StringDeserializer(),
                Serdes.json(TripEvent.class, mapper).deserializer());

        Instant t = Instant.parse("2026-07-13T10:00:00Z");
        input.pipeInput("42", event(42, 50, true, true, 41.0, 29.0, t), t);                  // move -> open
        input.pipeInput("42", event(42, 55, true, true, 41.01, 29.01, t.plusSeconds(60)), t.plusSeconds(60));
        input.pipeInput("42", event(42, 0, true, true, 41.01, 29.01, t.plusSeconds(400)), t.plusSeconds(400)); // stopped >5min

        List<TripEvent> out = trips.readValuesToList();
        assertTrue(out.stream().anyMatch(tp -> tp.status() == TripStatus.ONGOING));
        assertTrue(out.stream().anyMatch(tp -> tp.status() == TripStatus.CLOSED
                && tp.distanceKm() != null && tp.distanceKm() > 0), "expected a closed trip with distance");
    }
}
