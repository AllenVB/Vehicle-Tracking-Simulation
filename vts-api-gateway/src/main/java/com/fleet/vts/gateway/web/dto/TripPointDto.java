package com.fleet.vts.gateway.web.dto;

/**
 * One breadcrumb of a trip's route, in {@code seq} order. {@code speedKmh} is nullable —
 * the breadcrumb is rebuilt from telemetry, which does not always carry a speed.
 */
public record TripPointDto(
        int seq,
        double lat,
        double lon,
        Integer speedKmh) {
}
