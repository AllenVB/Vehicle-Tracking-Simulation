package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.DeviceHeartbeat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceHeartbeatRepository extends JpaRepository<DeviceHeartbeat, Long> {
}
