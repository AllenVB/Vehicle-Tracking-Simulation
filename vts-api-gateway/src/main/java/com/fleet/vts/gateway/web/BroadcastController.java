package com.fleet.vts.gateway.web;

import com.fleet.vts.gateway.repository.BroadcastMessageRepository;
import com.fleet.vts.gateway.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin broadcast (pub/sub) channel — a tenant-wide announcement, separate from the per-vehicle
 * support chat in {@link VehicleMessageController}. A POST persists the announcement and fans it
 * out to every connected operator panel over {@code /topic/broadcast}; each panel drops it as a
 * 10s banner and then files it under the top-right notification bell. The GET rehydrates that
 * bell with recent history when a panel loads or reconnects.
 */
@RestController
@RequestMapping("/api/v1/broadcasts")
public class BroadcastController {

    /** STOMP destination every operator panel subscribes to for announcements. */
    static final String TOPIC = "/topic/broadcast";
    private static final int MAX_TITLE = 120;
    private static final int MAX_BODY = 1000;

    private final BroadcastMessageRepository broadcasts;
    private final SimpMessagingTemplate messaging;

    public BroadcastController(BroadcastMessageRepository broadcasts, SimpMessagingTemplate messaging) {
        this.broadcasts = broadcasts;
        this.messaging = messaging;
    }

    public record BroadcastRequest(String title, String body, String severity) {
    }

    /** Only INFO (default) or URGENT are accepted; anything else falls back to INFO. */
    private static String normalizeSeverity(String value) {
        return "URGENT".equalsIgnoreCase(value == null ? null : value.trim()) ? "URGENT" : "INFO";
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> send(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestBody BroadcastRequest request) {
        long tenant = CurrentUser.tenantId(jwt);
        String sender = jwt.getSubject() == null ? "admin" : jwt.getSubject();
        String title = trimToLimit(request == null ? null : request.title(), MAX_TITLE);
        String body = trimToLimit(request == null ? null : request.body(), MAX_BODY);
        String severity = normalizeSeverity(request == null ? null : request.severity());
        if (body == null || body.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        long id = broadcasts.insert(tenant, sender, severity, title, body);

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", id);
        msg.put("tenantId", tenant);
        msg.put("sender", sender);
        msg.put("severity", severity);
        msg.put("title", title);
        msg.put("body", body);
        msg.put("at", Instant.now().toString());
        messaging.convertAndSend(TOPIC, msg);   // fan out to every connected panel
        return ResponseEntity.ok(msg);
    }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal Jwt jwt) {
        return broadcasts.recent(CurrentUser.tenantId(jwt));
    }

    /** Null-safe trim + hard cap so an over-long field is clipped rather than rejected. */
    private static String trimToLimit(String value, int limit) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > limit ? trimmed.substring(0, limit) : trimmed;
    }
}
