package com.fleet.vts.gateway.web;

import com.fleet.vts.gateway.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Araç/sürücü karnesi: son 30 günün ihlallerinden her araca bir güvenlik puanı (0-100) ve harf
 * notu üretir, en iyiden en kötüye sıralar. Her araç bir sürücüye (driver_login) karşılık geldiği
 * için bu pratikte sürücü sıralamasıdır. Ayrı bir tablo yok — mevcut ihlal verisinden hesaplanır.
 *
 * <p>Puan 100'den başlar; her ihlal ağırlığınca düşürür (ağır ihlal daha çok). İhlali olmayan araç
 * 100 (A) alır.
 */
@RestController
@RequestMapping("/api/v1/scorecard")
public class ScorecardController {

    private static final int WINDOW_DAYS = 30;

    private final JdbcTemplate jdbc;

    public ScorecardController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal Jwt jwt) {
        long tenant = CurrentUser.tenantId(jwt);
        List<Map<String, Object>> rows = jdbc.query("""
                        SELECT v.id, v.plate,
                               count(vi.id)                                                          AS total,
                               count(vi.id) FILTER (WHERE upper(vi.severity) IN ('HIGH','CRITICAL')) AS high,
                               count(vi.id) FILTER (WHERE upper(vi.severity) IN ('MEDIUM','WARNING','WARN')) AS med
                        FROM vehicle v
                        LEFT JOIN violation vi
                               ON vi.vehicle_id = v.id AND vi.tenant_id = v.tenant_id
                              AND vi.occurred_at > now() - make_interval(days => ?)
                        WHERE v.tenant_id = ?
                        GROUP BY v.id, v.plate
                        """,
                (rs, i) -> {
                    long total = rs.getLong("total"), high = rs.getLong("high"), med = rs.getLong("med");
                    long low = total - high - med;
                    int penalty = (int) (high * 10 + med * 5 + low * 2);
                    int score = Math.max(0, 100 - penalty);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("vehicleId", rs.getLong("id"));
                    m.put("plate", rs.getString("plate"));
                    m.put("score", score);
                    m.put("grade", grade(score));
                    m.put("violations", total);
                    m.put("severe", high);
                    return m;
                }, WINDOW_DAYS, tenant);

        // En yüksek puan önce; eşitlikte az ihlal, sonra plaka.
        List<Map<String, Object>> out = new ArrayList<>(rows);
        out.sort(Comparator
                .comparingInt((Map<String, Object> m) -> (int) m.get("score")).reversed()
                .thenComparingLong(m -> (long) m.get("violations"))
                .thenComparing(m -> String.valueOf(m.get("plate"))));
        for (int i = 0; i < out.size(); i++) {
            out.get(i).put("rank", i + 1);
        }
        return out;
    }

    private static String grade(int score) {
        if (score >= 90) return "A";
        if (score >= 75) return "B";
        if (score >= 60) return "C";
        return "D";
    }
}
