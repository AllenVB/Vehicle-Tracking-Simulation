package com.fleet.vts.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fleet.vts.common.enums.NotificationChannel;
import com.fleet.vts.common.enums.RuleType;
import com.fleet.vts.common.enums.Severity;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the shared event records round-trip through Jackson (records via the
 * canonical constructor, enums, and Instant via JavaTimeModule). This is the
 * contract every service relies on for the JSON serializer.
 */
class EventSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void telemetryEventRoundTrips() throws Exception {
        TelemetryEvent event = TelemetryEvent.builder()
                .tenantId(1L)
                .vehicleId(42L)
                .deviceId(7L)
                .imei("000000000000042")
                .ts(Instant.parse("2026-07-13T10:15:30Z"))
                .lat(41.015137).lon(28.979530)
                .speedKmh(83).heading(90).battery(88).fuelPct(60)
                .engineOn(true).ignition(true)
                .odometerKm(123456L)
                .correlationId("corr-1")
                .build();

        String json = mapper.writeValueAsString(event);
        TelemetryEvent back = mapper.readValue(json, TelemetryEvent.class);

        assertEquals(event, back);
    }

    @Test
    void violationEventRoundTripsWithEnums() throws Exception {
        ViolationEvent event = ViolationEvent.builder()
                .tenantId(1L)
                .vehicleId(42L)
                .driverId(5L)
                .ruleId(3L)
                .ruleCode("SPEED_LIMIT")
                .ruleType(RuleType.SPEED_LIMIT)
                .severity(Severity.HIGH)
                .occurredAt(Instant.parse("2026-07-13T10:15:30Z"))
                .value(112.0).threshold(80.0)
                .lat(41.0).lon(29.0)
                .correlationId("corr-2")
                .build();

        String json = mapper.writeValueAsString(event);
        ViolationEvent back = mapper.readValue(json, ViolationEvent.class);

        assertEquals(event, back);
        assertEquals(RuleType.SPEED_LIMIT, back.ruleType());
        assertEquals(Severity.HIGH, back.severity());
    }

    @Test
    void notificationEventRoundTrips() throws Exception {
        NotificationEvent event = NotificationEvent.builder()
                .tenantId(1L)
                .userId(9L)
                .vehicleId(42L)
                .ruleCode("HARSH_BRAKING")
                .severity(Severity.MEDIUM)
                .channel(NotificationChannel.WEBSOCKET)
                .title("Sert Fren")
                .body("VTS-0042 aracında sert fren tespit edildi.")
                .occurredAt(Instant.parse("2026-07-13T10:15:30Z"))
                .sourceViolationId(1001L)
                .correlationId("corr-3")
                .build();

        String json = mapper.writeValueAsString(event);
        NotificationEvent back = mapper.readValue(json, NotificationEvent.class);

        assertEquals(event, back);
        assertEquals(NotificationChannel.WEBSOCKET, back.channel());
    }
}
