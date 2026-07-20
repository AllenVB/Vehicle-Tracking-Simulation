package com.fleet.vts.ingestion.domain;

/** A fuel station the fleet can refuel at. */
public record FuelStation(Long id, String name, String brand, double lat, double lon) {
}
