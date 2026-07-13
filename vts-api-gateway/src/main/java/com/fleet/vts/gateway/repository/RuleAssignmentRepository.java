package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.RuleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleAssignmentRepository extends JpaRepository<RuleAssignment, Long> {
}
