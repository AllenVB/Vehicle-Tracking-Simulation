package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {
}
