package com.fleet.vts.gateway.web;

import com.fleet.vts.gateway.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Araç kontrol (DVIR) kayıtlarının admin görünümü: en yeni önce, plakayla. Sürücü doldurur
 * (bkz. DriverInspectionController); admin yalnızca görüntüler ve kusurları takip eder.
 */
@RestController
@RequestMapping("/api/v1/inspections")
public class InspectionController {

    private static final int MAX_ROWS = 50;

    private final JdbcTemplate jdbc;

    public InspectionController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Son kontroller; {@code defectsOnly=true} ile yalnızca kusurlu olanlar. */
    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal Jwt jwt,
                                          @RequestParam(defaultValue = "false") boolean defectsOnly) {
        long tenant = CurrentUser.tenantId(jwt);
        String where = defectsOnly ? " AND vi.overall = 'DEFECT'" : "";
        List<Map<String, Object>> rows = jdbc.query("""
                        SELECT vi.id, vi.vehicle_id, v.plate, vi.inspected_at, vi.overall,
                               vi.items::text AS items, vi.note
                        FROM vehicle_inspection vi
                        JOIN vehicle v ON v.id = vi.vehicle_id
                        WHERE vi.tenant_id = ?""" + where
                        + " ORDER BY vi.inspected_at DESC LIMIT " + MAX_ROWS,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("vehicleId", rs.getLong("vehicle_id"));
                    m.put("plate", rs.getString("plate"));
                    OffsetDateTime at = rs.getObject("inspected_at", OffsetDateTime.class);
                    m.put("inspectedAt", at == null ? null : at.toInstant());
                    m.put("overall", rs.getString("overall"));
                    m.put("items", rs.getString("items"));   // ham JSON; istemci parse eder
                    m.put("note", rs.getString("note"));
                    return m;
                }, tenant);
        return new ArrayList<>(rows);
    }
}
