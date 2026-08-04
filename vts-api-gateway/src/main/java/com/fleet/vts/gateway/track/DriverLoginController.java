package com.fleet.vts.gateway.track;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Driver sign-in for the in-browser driver app. Public, like the rest of {@code /api/v1/track} — a
 * phone cannot carry an operator JWT. Credentials are plate + model + password; a successful login
 * leases the vehicle to this device (one active driver at a time) and returns the IMEI to stream
 * under plus a session token the phone echoes on every telemetry post (header {@code X-Device-Session}).
 */
@RestController
@RequestMapping("/api/v1/track")
public class DriverLoginController {

    private final DriverAuthService auth;
    private final DriverSessionService sessions;

    public DriverLoginController(DriverAuthService auth, DriverSessionService sessions) {
        this.auth = auth;
        this.sessions = sessions;
    }

    public record LoginRequest(String plate, String password, String deviceId) {
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        if (isBlank(req.plate()) || isBlank(req.password()) || isBlank(req.deviceId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "MISSING_FIELDS"));
        }
        var identity = auth.login(req.plate().trim(), req.password(), req.deviceId().trim());
        if (identity.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "BAD_CREDENTIALS"));
        }
        var id = identity.get();
        var token = sessions.tryLogin(id.vehicleId(), req.deviceId().trim());
        if (token.isEmpty()) {
            return ResponseEntity.status(409).body(Map.of("error", "VEHICLE_IN_USE"));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("imei", id.imei());
        body.put("vehicleId", id.vehicleId());
        body.put("plate", id.plate());
        body.put("make", id.make() == null ? "" : id.make());
        body.put("model", id.model());
        body.put("sessionToken", token.get());
        body.put("sessionTtlSec", DriverSessionService.HOLD.toSeconds());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody(required = false) Map<String, String> body) {
        sessions.logout(body == null ? null : body.get("sessionToken"));
        return ResponseEntity.noContent().build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
