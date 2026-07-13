package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.ViolationAck;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ViolationAckRepository extends JpaRepository<ViolationAck, Long> {
}
