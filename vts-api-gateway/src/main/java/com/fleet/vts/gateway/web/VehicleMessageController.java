package com.fleet.vts.gateway.web;

import com.fleet.vts.gateway.repository.VehicleMessageRepository;
import com.fleet.vts.gateway.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operator -> vehicle text warnings (e.g. "fragile / flammable cargo, mind your speed").
 * Each message is stored per vehicle and broadcast to every connected operator over
 * {@code /topic/vehicle-messages}, so it both persists (shown when the vehicle is clicked)
 * and arrives as a live notification.
 *
 * <p>The SQL lives in {@link VehicleMessageRepository}; this class only maps HTTP to it.
 */
@RestController
@RequestMapping("/api/v1/vehicles")
public class VehicleMessageController {

    private static final int MAX_BODY = 500;

    private final VehicleMessageRepository messages;
    private final SimpMessagingTemplate messaging;

    public VehicleMessageController(VehicleMessageRepository messages, SimpMessagingTemplate messaging) {
        this.messages = messages;
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
        if (body.length() > MAX_BODY) {
            body = body.substring(0, MAX_BODY);
        }

        String plate = messages.findPlate(id, tenant);
        if (plate == null) {
            return ResponseEntity.notFound().build();
        }
        messages.insert(tenant, id, category, body);

        Map<String, Object> msg = new LinkedHashMap<>();
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
        return messages.recent(CurrentUser.tenantId(jwt), id);
    }
}
