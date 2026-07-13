package com.fleet.vts.ingestion.domain;

/**
 * The identifiers a device IMEI resolves to. Cached (Caffeine -> Redis) and
 * backed by the database, so ingestion stays stateless and horizontally
 * scalable.
 */
public record VehicleRef(Long vehicleId, Long tenantId, Long deviceId) {
}
