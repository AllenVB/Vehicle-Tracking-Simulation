package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.Violation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ViolationRepository extends JpaRepository<Violation, Long> {
}
