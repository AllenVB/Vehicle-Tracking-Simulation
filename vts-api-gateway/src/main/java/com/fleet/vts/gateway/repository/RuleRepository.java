package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.Rule;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleRepository extends JpaRepository<Rule, Long> {

    List<Rule> findByTenantIdAndEnabledTrue(Long tenantId);
}
