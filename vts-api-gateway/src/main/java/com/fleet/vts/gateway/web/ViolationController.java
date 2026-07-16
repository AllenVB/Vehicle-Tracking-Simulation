package com.fleet.vts.gateway.web;

import com.fleet.vts.gateway.repository.ViolationQueryRepository;
import com.fleet.vts.gateway.security.CurrentUser;
import com.fleet.vts.gateway.web.dto.CursorPage;
import com.fleet.vts.gateway.web.dto.ViolationDto;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Violation queries with keyset (cursor) pagination over the hypertable, ordered
 * by (occurred_at, id) descending. Filters: date range, vehicle, rule, severity.
 *
 * <p>The queries live in {@link ViolationQueryRepository}. What stays here is the transport
 * concern the repository has no business knowing about: the opaque cursor, which is just
 * the last row's {@code (occurred_at, id)} encoded so callers cannot meaningfully tamper
 * with a position they should treat as a token.
 */
@RestController
@RequestMapping("/api/v1/violations")
public class ViolationController {

    private static final int MAX_PAGE_SIZE = 200;

    private final ViolationQueryRepository violations;

    public ViolationController(ViolationQueryRepository violations) {
        this.violations = violations;
    }

    @GetMapping
    public CursorPage<ViolationDto> list(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam(required = false) Instant from,
                                         @RequestParam(required = false) Instant to,
                                         @RequestParam(required = false) Long vehicleId,
                                         @RequestParam(required = false) String ruleCode,
                                         @RequestParam(required = false) String severity,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(defaultValue = "50") int limit) {
        int capped = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        Cursor decoded = Cursor.decode(cursor);

        // One extra row: its presence is how we know a further page exists.
        var query = new ViolationQueryRepository.PageQuery(
                CurrentUser.tenantId(jwt), from, to, vehicleId, ruleCode, severity,
                decoded.occurredAt(), decoded.id(), capped + 1);
        List<ViolationDto> rows = violations.findPage(query);

        if (rows.size() <= capped) {
            return new CursorPage<>(rows, null);
        }
        ViolationDto last = rows.get(capped - 1);
        return new CursorPage<>(rows.subList(0, capped), Cursor.encode(last));
    }

    @PostMapping("/{id}/ack")
    public ResponseEntity<Map<String, Object>> ack(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        long tenant = CurrentUser.tenantId(jwt);
        Optional<OffsetDateTime> occurredAt = violations.findOccurredAt(id, tenant);
        if (occurredAt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        violations.insertAck(tenant, id, occurredAt.get(), CurrentUser.userId(jwt));
        return ResponseEntity.ok(Map.of("violationId", id, "acked", true));
    }

    /** The keyset position a cursor carries; both fields null means "first page". */
    private record Cursor(Instant occurredAt, Long id) {

        private static final Cursor FIRST_PAGE = new Cursor(null, null);

        static Cursor decode(String encoded) {
            if (encoded == null) {
                return FIRST_PAGE;
            }
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded)).split(":");
            return new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), Long.parseLong(parts[1]));
        }

        static String encode(ViolationDto last) {
            String raw = last.occurredAt().toEpochMilli() + ":" + last.id();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes());
        }
    }
}
