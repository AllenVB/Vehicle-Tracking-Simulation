package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
}
