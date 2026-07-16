package com.fleet.vts.gateway.web.dto;

/** Fleet-wide counters shown on the dashboard header. */
public record DashboardSummaryDto(
        long vehicles,
        long activeVehicles,
        long drivers,
        long violations24h,
        long ongoingTrips) {
}
