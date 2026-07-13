package com.fleet.vts.gateway.web.dto;

/** Vehicle read model returned by the API (never the entity itself). */
public record VehicleDto(
        Long id,
        Long groupId,
        Long currentDriverId,
        String plate,
        String vin,
        String make,
        String model,
        Integer year,
        String type,
        String fuelType,
        String status,
        Long odometerKm) {
}
