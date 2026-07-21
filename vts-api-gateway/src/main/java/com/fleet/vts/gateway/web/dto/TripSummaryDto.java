package com.fleet.vts.gateway.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A trip in a vehicle's history list. {@code endedAt} is null while the trip is ONGOING.
 *
 * <p>{@code score} is the journey's quality out of ten, set when the trip closes. Null for an
 * ongoing trip — which has not been scored yet — and for trips that predate scoring.
 */
public record TripSummaryDto(
        Long id,
        Instant startedAt,
        Instant endedAt,
        BigDecimal distanceKm,
        Integer score,
        String status) {
}
