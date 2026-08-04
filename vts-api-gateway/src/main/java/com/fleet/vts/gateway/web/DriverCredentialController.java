package com.fleet.vts.gateway.web;

import com.fleet.vts.gateway.security.CurrentUser;
import com.fleet.vts.gateway.track.DriverSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin management of driver sign-in for a vehicle: set/reset the password the driver app uses
 * (plate + model + password), and force-free a vehicle's active session. Writes require ADMIN or
 * FLEET_MANAGER and are tenant-scoped from the JWT, so one tenant cannot touch another's vehicles.
 */
@RestController
@RequestMapping("/api/v1/vehicles/{id}")
public class DriverCredentialController {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final DriverSessionService sessions;

    public DriverCredentialController(JdbcTemplate jdbc, PasswordEncoder encoder, DriverSessionService sessions) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.sessions = sessions;
    }

    public record CredentialRequest(@NotBlank @Size(min = 4, max = 72) String password) {
    }

    @PostMapping("/driver-credential")
    @PreAuthorize("hasAnyRole('ADMIN','FLEET_MANAGER')")
    public ResponseEntity<?> setPassword(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                         @Valid @RequestBody CredentialRequest req) {
        Long tenantId = CurrentUser.tenantId(jwt);
        if (!owns(id, tenantId)) {
            return ResponseEntity.notFound().build();
        }
        String hash = encoder.encode(req.password());
        jdbc.update("""
                        INSERT INTO driver_login (tenant_id, vehicle_id, password_hash)
                        VALUES (?, ?, ?)
                        ON CONFLICT (vehicle_id) DO UPDATE
                            SET password_hash = EXCLUDED.password_hash, updated_at = now()
                        """, tenantId, id, hash);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/driver-session")
    @PreAuthorize("hasAnyRole('ADMIN','FLEET_MANAGER')")
    public ResponseEntity<?> forceLogout(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Long tenantId = CurrentUser.tenantId(jwt);
        if (!owns(id, tenantId)) {
            return ResponseEntity.notFound().build();
        }
        sessions.evict(id);
        return ResponseEntity.noContent().build();
    }

    private boolean owns(Long vehicleId, Long tenantId) {
        List<Long> rows = jdbc.query("SELECT id FROM vehicle WHERE id = ? AND tenant_id = ?",
                (rs, n) -> rs.getLong(1), vehicleId, tenantId);
        return !rows.isEmpty();
    }
}
