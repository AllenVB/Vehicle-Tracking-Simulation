package com.fleet.vts.ingestion.application;

import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.topic.Topics;
import com.fleet.vts.ingestion.adapter.in.web.TelemetryRequest;
import com.fleet.vts.ingestion.domain.VehicleRef;
import com.fleet.vts.ingestion.port.in.BatchIngestResult;
import com.fleet.vts.ingestion.port.in.IngestResult;
import com.fleet.vts.ingestion.port.out.TelemetryPublisherPort;
import com.fleet.vts.ingestion.port.out.VehicleLookupPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Routing behaviour of the ingestion core with the ports mocked. */
class TelemetryIngestionServiceTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    private final VehicleLookupPort lookup = mock(VehicleLookupPort.class);
    private final TelemetryPublisherPort publisher = mock(TelemetryPublisherPort.class);
    private final TelemetryIngestionService service =
            new TelemetryIngestionService(lookup, publisher, validator, new SimpleMeterRegistry());

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    private TelemetryRequest request(String imei, Double lat) {
        return new TelemetryRequest(imei, null, lat, 29.0, 55, 90, 80, 60,
                true, true, 1000L, null);
    }

    @Test
    void knownImeiIsPublishedToRawKeyedByVehicleId() {
        when(lookup.findByImei("imei-1")).thenReturn(Optional.of(new VehicleRef(42L, 1L, 7L)));

        IngestResult result = service.ingestOne(request("imei-1", 41.0));

        assertTrue(result.accepted());
        ArgumentCaptor<TelemetryEvent> event = ArgumentCaptor.forClass(TelemetryEvent.class);
        verify(publisher).publishRaw(event.capture());
        assertEquals(42L, event.getValue().vehicleId());
        assertEquals(1L, event.getValue().tenantId());
        verify(publisher, never()).publishDlq(any(), any());
    }

    @Test
    void unknownImeiIsDeadLettered() {
        when(lookup.findByImei("ghost")).thenReturn(Optional.empty());

        IngestResult result = service.ingestOne(request("ghost", 41.0));

        assertFalse(result.accepted());
        verify(publisher).publishDlq(any(), eq("UNKNOWN_IMEI"));
        verify(publisher, never()).publishRaw(any());
    }

    @Test
    void missingTimestampIsStampedWithServerTime() {
        when(lookup.findByImei("imei-2")).thenReturn(Optional.of(new VehicleRef(1L, 1L, 1L)));
        Instant before = Instant.now();

        service.ingestOne(request("imei-2", 41.0));

        ArgumentCaptor<TelemetryEvent> event = ArgumentCaptor.forClass(TelemetryEvent.class);
        verify(publisher).publishRaw(event.capture());
        assertTrue(!event.getValue().ts().isBefore(before));
    }

    @Test
    void batchValidatesEachElementAndRoutesIndependently() {
        when(lookup.findByImei("good")).thenReturn(Optional.of(new VehicleRef(5L, 1L, 3L)));

        // one valid+known, one invalid (lat null -> validation fails)
        BatchIngestResult result = service.ingestBatch(List.of(
                request("good", 41.0),
                request("bad", null)));

        assertEquals(1, result.accepted());
        assertEquals(1, result.deadLettered());
        assertEquals(2, result.total());
        verify(publisher).publishRaw(any());
        verify(publisher).publishDlq(any(), eq("VALIDATION_FAILED"));
    }
}
