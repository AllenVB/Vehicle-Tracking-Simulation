package com.fleet.vts.gateway.web;

import com.fleet.vts.common.event.DeviceCommandEvent;
import com.fleet.vts.common.topic.Topics;
import com.fleet.vts.gateway.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operator → device commands.
 *
 * <p>What makes this different from every other endpoint here: it is the first one that
 * changes something in the physical world. Everything else reads a fleet or nudges a
 * simulation; {@code setdigout 1} cuts a relay.
 *
 * <p>Two consequences, both deliberate:
 * <ul>
 *   <li><b>Not free text.</b> The command is chosen from a fixed catalogue. Devices accept a
 *       long list of configuration commands, and an endpoint that forwards whatever it is
 *       handed lets anyone with an operator login reconfigure hardware in the field.</li>
 *   <li><b>Fire and record, not fire and wait.</b> The response is 202 with a command id. The
 *       device may be in a tunnel; a synchronous API would either block on that or lie about
 *       it. The operator polls the row and sees which of the two happened.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class DeviceCommandController {

    /** What an operator may send, and what it means in the cab. */
    public record CommandOption(String command, String label, boolean destructive) {
    }

    private static final List<CommandOption> CATALOGUE = List.of(
            new CommandOption("getgps", "Konumu şimdi gönder", false),
            new CommandOption("getinfo", "Cihaz bilgisi", false),
            new CommandOption("getver", "Yazılım sürümü", false),
            new CommandOption("getstatus", "Modem durumu", false),
            new CommandOption("setdigout 1", "Röleyi kes (motor kilidi)", true),
            new CommandOption("setdigout 0", "Röleyi aç (kilidi kaldır)", true),
            new CommandOption("cpureset", "Cihazı yeniden başlat", true));

    private static final int MAX_HISTORY = 50;

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, Object> kafka;

    public DeviceCommandController(JdbcTemplate jdbc, KafkaTemplate<String, Object> kafka) {
        this.jdbc = jdbc;
        this.kafka = kafka;
    }

    public record CommandRequest(String command) {
    }

    /** The catalogue, so the UI never has to hard-code a command string. */
    @GetMapping("/device-commands/catalogue")
    public List<CommandOption> catalogue() {
        return CATALOGUE;
    }

    @PostMapping("/vehicles/{id}/commands")
    @PreAuthorize("hasAnyRole('ADMIN', 'FLEET_MANAGER')")
    public ResponseEntity<Map<String, Object>> send(@AuthenticationPrincipal Jwt jwt,
                                                    @PathVariable Long id,
                                                    @RequestBody CommandRequest request) {
        long tenant = CurrentUser.tenantId(jwt);

        String command = request.command() == null ? "" : request.command().trim();
        if (CATALOGUE.stream().noneMatch(o -> o.command().equals(command))) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "UNKNOWN_COMMAND", "command", command));
        }

        // The device row is the reason a vehicle id can address hardware at all; a vehicle
        // without one has nothing to send to, and that is a 404 rather than a silent no-op.
        Map<String, Object> device = jdbc.query("""
                        SELECT d.id AS device_id, d.imei AS imei
                        FROM device d
                        WHERE d.vehicle_id = ? AND d.tenant_id = ?
                        ORDER BY d.id LIMIT 1
                        """,
                (ResultSetExtractor<Map<String, Object>>) rs -> rs.next()
                        ? Map.of("device_id", rs.getLong("device_id"), "imei", rs.getString("imei"))
                        : null,
                id, tenant);
        if (device == null) {
            return ResponseEntity.notFound().build();
        }

        String issuedBy = jwt == null ? "system" : jwt.getSubject();
        Long commandId = jdbc.queryForObject("""
                        INSERT INTO device_command
                            (tenant_id, vehicle_id, device_id, imei, command, status, issued_by)
                        VALUES (?, ?, ?, ?, ?, 'PENDING', ?)
                        RETURNING id
                        """,
                Long.class, tenant, id, device.get("device_id"), device.get("imei"),
                command, issuedBy);

        DeviceCommandEvent event = DeviceCommandEvent.builder()
                .commandId(commandId)
                .tenantId(tenant)
                .vehicleId(id)
                .imei((String) device.get("imei"))
                .command(command)
                .issuedAt(Instant.now())
                .issuedBy(issuedBy)
                .correlationId(UUID.randomUUID().toString())
                .build();
        // Keyed by IMEI so one device's commands keep their order across partitions — the
        // response matching downstream is positional and depends on it.
        kafka.send(Topics.DEVICE_COMMAND, event.imei(), event);

        return ResponseEntity.accepted().body(Map.of(
                "id", commandId, "status", "PENDING", "command", command));
    }

    /** Recent commands for a vehicle, newest first — this is what the operator panel polls. */
    @GetMapping("/vehicles/{id}/commands")
    public List<Map<String, Object>> history(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable Long id,
                                             @RequestParam(defaultValue = "10") int limit) {
        long tenant = CurrentUser.tenantId(jwt);
        int capped = Math.max(1, Math.min(limit, MAX_HISTORY));

        return jdbc.query("""
                        SELECT id, command, status, response, issued_by, issued_at, sent_at, executed_at
                        FROM device_command
                        WHERE vehicle_id = ? AND tenant_id = ?
                        ORDER BY issued_at DESC
                        LIMIT ?
                        """,
                rs -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getLong("id"));
                        row.put("command", rs.getString("command"));
                        row.put("status", rs.getString("status"));
                        row.put("response", rs.getString("response"));
                        row.put("issuedBy", rs.getString("issued_by"));
                        row.put("createdAt", instant(rs.getObject("issued_at", OffsetDateTime.class)));
                        row.put("answeredAt", instant(rs.getObject("executed_at", OffsetDateTime.class)));
                        out.add(row);
                    }
                    return out;
                },
                id, tenant, capped);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
