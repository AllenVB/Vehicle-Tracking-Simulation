package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.MaintenancePlan;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenancePlanRepository extends JpaRepository<MaintenancePlan, Long> {
}
