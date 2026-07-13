package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.VehicleDriverAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleDriverAssignmentRepository extends JpaRepository<VehicleDriverAssignment, Long> {
}
