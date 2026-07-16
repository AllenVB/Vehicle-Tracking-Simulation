package com.fleet.vts.gateway.web.dto;

/** A fuel station pin for the map and the nearest-station distance shown on select. */
public record FuelStationDto(
        String name,
        String brand,
        double lat,
        double lon) {
}
