package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.Geofence;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GeofenceRepository extends JpaRepository<Geofence, Long> {
}
