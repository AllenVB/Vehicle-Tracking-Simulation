package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.Device;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByImei(String imei);
}
