package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.VehicleGroup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleGroupRepository extends JpaRepository<VehicleGroup, Long> {
}
