package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.GeofenceEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GeofenceEventRepository extends JpaRepository<GeofenceEvent, Long> {
}
