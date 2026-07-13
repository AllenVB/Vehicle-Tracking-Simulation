package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.Vehicle;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    Optional<Vehicle> findByTenantIdAndPlate(Long tenantId, String plate);

    Optional<Vehicle> findByIdAndTenantId(Long id, Long tenantId);

    Page<Vehicle> findByTenantId(Long tenantId, Pageable pageable);

    List<Vehicle> findByTenantId(Long tenantId);
}
