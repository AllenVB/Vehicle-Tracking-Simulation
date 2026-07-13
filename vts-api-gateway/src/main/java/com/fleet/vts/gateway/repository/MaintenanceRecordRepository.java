package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.MaintenanceRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceRecordRepository extends JpaRepository<MaintenanceRecord, Long> {
}
