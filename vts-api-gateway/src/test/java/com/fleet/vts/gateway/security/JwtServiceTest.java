package com.fleet.vts.gateway.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issues a JWT and decodes it back, asserting the claims survive the round-trip. */
class JwtServiceTest {

    private static final String SECRET = "dev-only-secret-key-that-is-at-least-256-bits-long!!";
    private final SecretKeySpec key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    private final JwtService jwtService = new JwtService(new NimbusJwtEncoder(new ImmutableSecret<>(key)));
    private final JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();

    @Test
    void issuesTokenWithClaimsThatDecode() {
        String token = jwtService.issue(42L, 7L, "admin", List.of("ADMIN", "FLEET_MANAGER"));

        Jwt jwt = decoder.decode(token);

        assertEquals("admin", jwt.getSubject());
        assertEquals(7L, ((Number) jwt.getClaim("tenantId")).longValue());
        assertEquals(42L, ((Number) jwt.getClaim("uid")).longValue());
        assertEquals(List.of("ADMIN", "FLEET_MANAGER"), jwt.getClaimAsStringList("roles"));
        assertTrue(jwt.getExpiresAt().isAfter(jwt.getIssuedAt()));
    }
}
