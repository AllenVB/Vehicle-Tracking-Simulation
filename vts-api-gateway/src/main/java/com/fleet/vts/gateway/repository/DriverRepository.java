package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.Driver;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriverRepository extends JpaRepository<Driver, Long> {
}
