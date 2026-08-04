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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
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
                        INSERT INTO driver_login (tenant_id, vehicle_id, password_hash, password_plain)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (vehicle_id) DO UPDATE
                            SET password_hash = EXCLUDED.password_hash,
                                password_plain = EXCLUDED.password_plain, updated_at = now()
                        """, tenantId, id, hash, req.password());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * Reveal the driver access code so the operator can share it with the driver. These are
     * admin-assigned codes (not user secrets), stored as plaintext for exactly this purpose; login
     * still verifies the bcrypt hash. ADMIN/FLEET_MANAGER only, tenant-scoped.
     */
    @GetMapping("/driver-credential")
    @PreAuthorize("hasAnyRole('ADMIN','FLEET_MANAGER')")
    public ResponseEntity<Map<String, Object>> getPassword(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Long tenantId = CurrentUser.tenantId(jwt);
        if (!owns(id, tenantId)) {
            return ResponseEntity.notFound().build();
        }
        List<String> pw = jdbc.query("SELECT password_plain FROM driver_login WHERE vehicle_id = ?",
                (rs, n) -> rs.getString(1), id);
        Map<String, Object> body = new HashMap<>();
        body.put("password", pw.isEmpty() ? null : pw.get(0));
        return ResponseEntity.ok(body);
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
