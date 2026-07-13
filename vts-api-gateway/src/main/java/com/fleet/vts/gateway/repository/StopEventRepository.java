package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.StopEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StopEventRepository extends JpaRepository<StopEvent, Long> {
}
