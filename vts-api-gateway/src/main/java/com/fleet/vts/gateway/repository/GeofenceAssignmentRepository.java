package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.GeofenceAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GeofenceAssignmentRepository extends JpaRepository<GeofenceAssignment, Long> {
}
