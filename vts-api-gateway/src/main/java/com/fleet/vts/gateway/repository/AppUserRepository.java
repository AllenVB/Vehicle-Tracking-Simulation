package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByTenantIdAndUsername(Long tenantId, String username);
}
