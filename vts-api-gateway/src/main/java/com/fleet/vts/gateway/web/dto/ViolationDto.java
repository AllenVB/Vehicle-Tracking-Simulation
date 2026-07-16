package com.fleet.vts.gateway.web.dto;

import java.time.Instant;

/** A violation as the API returns it (never the entity itself). */
public record ViolationDto(
        Long id,
        Long vehicleId,
        Long driverId,
        String ruleCode,
        String type,
        String severity,
        Instant occurredAt,
        Double value,
        Double threshold,
        Double lat,
        Double lon) {
}
