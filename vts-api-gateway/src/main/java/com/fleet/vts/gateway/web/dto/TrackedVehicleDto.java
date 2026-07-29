package com.fleet.vts.gateway.web.dto;

/**
 * A phone-enrolled ("tracked") vehicle for the left bar. {@code trackId} is a 1..N sequence
 * assigned by enrollment order, so the operator tracks by a small number rather than the raw
 * database id — the auto-seeded demo fleet is not included here at all.
 */
public record TrackedVehicleDto(long id, long trackId, String plate, String make, String model, String type) {
}
