package com.fleet.vts.gateway.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Create/update payload for a vehicle. */
public record VehicleRequest(
        @NotBlank String plate,
        String vin,
        String make,
        String model,
        Integer year,
        String type,
        String fuelType,
        String status,
        Long groupId,
        Long currentDriverId,
        Long odometerKm) {
}
