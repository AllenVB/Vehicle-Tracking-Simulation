package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.Vehicle;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    Optional<Vehicle> findByTenantIdAndPlate(Long tenantId, String plate);
}
