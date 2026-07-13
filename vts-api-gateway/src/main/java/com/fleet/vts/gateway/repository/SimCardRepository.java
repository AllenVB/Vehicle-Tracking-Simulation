package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.SimCard;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SimCardRepository extends JpaRepository<SimCard, Long> {
}
