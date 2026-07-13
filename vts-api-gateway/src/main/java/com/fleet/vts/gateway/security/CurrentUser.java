package com.fleet.vts.gateway.security;

import org.springframework.security.oauth2.jwt.Jwt;

/** Reads the tenant and user id from the authenticated JWT. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static long tenantId(Jwt jwt) {
        return ((Number) jwt.getClaim("tenantId")).longValue();
    }

    public static long userId(Jwt jwt) {
        return ((Number) jwt.getClaim("uid")).longValue();
    }
}
