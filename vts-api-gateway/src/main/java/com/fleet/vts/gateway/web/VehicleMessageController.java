package com.fleet.vts.gateway.web;

import com.fleet.vts.gateway.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Operator -> vehicle text warnings (e.g. "fragile / flammable cargo, mind your speed").
 * Each message is stored per vehicle and broadcast to every connected operator over
 * {@code /topic/vehicle-messages}, so it both persists (shown when the vehicle is clicked)
 * and arrives as a live notification.
 */
@RestController
@RequestMapping("/api/v1/vehicles")
public class VehicleMessageController {

    private final JdbcTemplate jdbc;
    private final SimpMessagingTemplate messaging;

    public VehicleMessageController(JdbcTemplate jdbc, SimpMessagingTemplate messaging) {
        this.jdbc = jdbc;
        this.messaging = messaging;
    }

    public record MessageRequest(String category, String body) {
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<Map<String, Object>> send(@AuthenticationPrincipal Jwt jwt,
                                                    @PathVariable Long id,
                                                    @RequestBody MessageRequest request) {
        long tenant = CurrentUser.tenantId(jwt);
        String category = request.category() == null || request.category().isBlank()
                ? "GENEL" : request.category().trim();
        String body = request.body() == null ? "" : request.body().trim();
        if (body.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        if (body.length() > 500) {
            body = body.substring(0, 500);
        }

        String plate = jdbc.query("SELECT plate FROM vehicle WHERE id = ? AND tenant_id = ?",
                (ResultSetExtractor<String>) rs -> rs.next() ? rs.getString(1) : null, id, tenant);
        if (plate == null) {
            return ResponseEntity.notFound().build();
        }

        jdbc.update("INSERT INTO vehicle_message (tenant_id, vehicle_id, category, body) VALUES (?, ?, ?, ?)",
                tenant, id, category, body);

        Map<String, Object> msg = new HashMap<>();
        msg.put("vehicleId", id);
        msg.put("plate", plate);
        msg.put("category", category);
        msg.put("body", body);
        msg.put("at", Instant.now().toString());
        messaging.convertAndSend("/topic/vehicle-messages", msg);   // live notification
        return ResponseEntity.ok(msg);
    }

    @GetMapping("/{id}/messages")
    public List<Map<String, Object>> list(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return jdbc.query("""
                SELECT category, body, created_at
                FROM vehicle_message
                WHERE tenant_id = ? AND vehicle_id = ?
                ORDER BY created_at DESC LIMIT 20
                """,
                (rs, n) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("category", rs.getString("category"));
                    m.put("body", rs.getString("body"));
                    m.put("at", rs.getObject("created_at", OffsetDateTime.class).toInstant().toString());
                    return m;
                },
                CurrentUser.tenantId(jwt), id);
    }
}
