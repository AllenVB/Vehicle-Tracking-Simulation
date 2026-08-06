package com.fleet.vts.gateway.web;

import com.fleet.vts.gateway.repository.VehicleMessageRepository;
import com.fleet.vts.gateway.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The admin notification inbox: recent driver→operator messages across the fleet, so the operator
 * panel can surface them in the top-right bell even when no vehicle chat was open at arrival. The
 * per-vehicle conversation still lives under {@link VehicleMessageController}; this is only the
 * tenant-wide "what came in" list. A separate path ({@code /api/v1/inbox}) avoids colliding with
 * the {@code /api/v1/vehicles/{id}} routes.
 */
@RestController
@RequestMapping("/api/v1/inbox")
public class MessageInboxController {

    private static final int LIMIT = 50;

    private final VehicleMessageRepository messages;

    public MessageInboxController(VehicleMessageRepository messages) {
        this.messages = messages;
    }

    @GetMapping
    public List<Map<String, Object>> incoming(@AuthenticationPrincipal Jwt jwt) {
        return messages.recentIncoming(CurrentUser.tenantId(jwt), LIMIT);
    }
}
