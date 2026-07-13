package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.UserRole;
import com.fleet.vts.gateway.domain.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {
}
