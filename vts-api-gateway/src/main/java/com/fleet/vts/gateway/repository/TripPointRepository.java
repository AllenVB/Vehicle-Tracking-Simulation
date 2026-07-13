package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.TripPoint;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripPointRepository extends JpaRepository<TripPoint, Long> {
}
