package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.Poi;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoiRepository extends JpaRepository<Poi, Long> {
}
