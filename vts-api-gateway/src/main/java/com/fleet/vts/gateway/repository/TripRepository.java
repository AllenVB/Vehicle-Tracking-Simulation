package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.Trip;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripRepository extends JpaRepository<Trip, Long> {
}
