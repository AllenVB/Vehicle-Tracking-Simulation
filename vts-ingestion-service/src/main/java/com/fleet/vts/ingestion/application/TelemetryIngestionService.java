package com.fleet.vts.ingestion.application;

import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.ingestion.adapter.in.web.TelemetryRequest;
import com.fleet.vts.ingestion.domain.VehicleRef;
import com.fleet.vts.ingestion.port.in.BatchIngestResult;
import com.fleet.vts.ingestion.port.in.IngestResult;
import com.fleet.vts.ingestion.port.in.TelemetryInboundPort;
import com.fleet.vts.ingestion.port.out.TelemetryPublisherPort;
import com.fleet.vts.ingestion.port.out.VehicleLookupPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Application core: resolve the device, stamp missing timestamps, and publish
 * to the raw topic keyed by vehicleId (preserving per-vehicle ordering).
 * Unknown devices and invalid batch elements are routed to the dead-letter
 * topic instead of being dropped.
 */
@Service
public class TelemetryIngestionService implements TelemetryInboundPort {

    private final VehicleLookupPort lookup;
    private final TelemetryPublisherPort publisher;
    private final Validator validator;
    private final Counter ingested;
    private final Counter deadLettered;

    public TelemetryIngestionService(VehicleLookupPort lookup,
                                     TelemetryPublisherPort publisher,
                                     Validator validator,
                                     MeterRegistry registry) {
        this.lookup = lookup;
        this.publisher = publisher;
        this.validator = validator;
        this.ingested = Counter.builder("telemetry.ingested")
                .description("Telemetry readings published to the raw topic")
                .register(registry);
        this.deadLettered = Counter.builder("telemetry.dead_lettered")
                .description("Telemetry readings routed to the DLQ")
                .register(registry);
    }

    @Override
    public IngestResult ingestOne(TelemetryRequest request) {
        Optional<VehicleRef> ref = lookup.findByImei(request.imei());
        if (ref.isEmpty()) {
            publisher.publishDlq(request, "UNKNOWN_IMEI");
            deadLettered.increment();
            return IngestResult.deadLettered("UNKNOWN_IMEI");
        }
        publisher.publishRaw(toEvent(request, ref.get()));
        ingested.increment();
        return IngestResult.ok();
    }

    @Override
    public BatchIngestResult ingestBatch(List<TelemetryRequest> requests) {
        int accepted = 0;
        int dead = 0;
        for (TelemetryRequest request : requests) {
            Set<ConstraintViolation<TelemetryRequest>> violations = validator.validate(request);
            if (!violations.isEmpty()) {
                publisher.publishDlq(request, "VALIDATION_FAILED");
                deadLettered.increment();
                dead++;
                continue;
            }
            if (ingestOne(request).accepted()) {
                accepted++;
            } else {
                dead++;
            }
        }
        return new BatchIngestResult(accepted, dead);
    }

    private TelemetryEvent toEvent(TelemetryRequest r, VehicleRef v) {
        Instant ts = r.ts() != null ? r.ts() : Instant.now();
        String correlationId = r.correlationId() != null ? r.correlationId()
                : UUID.randomUUID().toString();
        return TelemetryEvent.builder()
                .tenantId(v.tenantId())
                .vehicleId(v.vehicleId())
                .deviceId(v.deviceId())
                .imei(r.imei())
                .ts(ts)
                .lat(r.lat())
                .lon(r.lon())
                .speedKmh(r.speedKmh())
                .heading(r.heading())
                .battery(r.battery())
                .fuelPct(r.fuelPct())
                .engineOn(r.engineOn())
                .ignition(r.ignition())
                .odometerKm(r.odometerKm())
                .correlationId(correlationId)
                .build();
    }
}
