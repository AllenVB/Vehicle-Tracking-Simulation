package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
}
