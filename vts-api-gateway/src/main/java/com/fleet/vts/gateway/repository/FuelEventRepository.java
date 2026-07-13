package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.FuelEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FuelEventRepository extends JpaRepository<FuelEvent, Long> {
}
