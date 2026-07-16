package com.fleet.vts.gateway.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** A trip in a vehicle's history list. {@code endedAt} is null while the trip is ONGOING. */
public record TripSummaryDto(
        Long id,
        Instant startedAt,
        Instant endedAt,
        BigDecimal distanceKm,
        String status) {
}
