package com.fleet.vts.gateway.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.vts.gateway.security.CurrentUser;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Müşteri takip linki: admin bir araç için 24 saatlik, JWT'siz paylaşım tokeni üretir.
 * Müşteri bu linki açınca yalnızca o aracın konumunu görür — başka araç, başka veri yok.
 *
 * <p>Konum Redux sırasıyla: önce Redis (gerçek zamanlı, 5 dk TTL), sonra
 * {@code vehicle_last_position} DB tablosu (soğuk başlangıç fallback'i).
 */
@RestController
public class ShareController {

    private static final int TOKEN_BYTES = 18;          // 24 char base64url, tahmin edilemez
    private static final String POS_PREFIX = "vts:pos:";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final SecureRandom rng = new SecureRandom();

    public ShareController(JdbcTemplate jdbc, StringRedisTemplate redis, ObjectMapper json) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.json = json;
    }

    /**
     * Araç için 24 saatlik token oluşturur. Birden fazla çağrı birden fazla
     * bağımsız token üretir — hangisi dağıtıldıysa onu iptal etmek için ayrıca
     * bir endpoint gerekmez; süre dolunca kendiliğinden geçersiz olur.
     */
    @PostMapping("/api/v1/vehicles/{id}/share")
    @PreAuthorize("hasAnyRole('ADMIN', 'FLEET_MANAGER')")
    public ResponseEntity<Map<String, Object>> create(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        long tenant = CurrentUser.tenantId(jwt);
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vehicle WHERE id = ? AND tenant_id = ?",
                Integer.class, id, tenant);
        if (exists == null || exists == 0) {
            return ResponseEntity.notFound().build();
        }

        byte[] bytes = new byte[TOKEN_BYTES];
        rng.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        OffsetDateTime expiresAt = OffsetDateTime.now().plusHours(24);

        jdbc.update("""
                INSERT INTO vehicle_share_token (vehicle_id, tenant_id, token, expires_at)
                VALUES (?, ?, ?, ?)
                """, id, tenant, token, expiresAt);

        return ResponseEntity.status(201).body(Map.of(
                "token", token,
                "expiresAt", expiresAt.toInstant().toString()));
    }

    /**
     * Public — JWT gerekmez. Token geçerliyse en güncel konumu döner:
     * Redis'te varsa oradan (gerçek zamanlı), yoksa DB fallback'ten.
     */
    @GetMapping("/api/v1/share/{token}")
    public ResponseEntity<Map<String, Object>> position(@PathVariable String token) {
        Map<String, Object> meta;
        try {
            meta = jdbc.queryForMap("""
                    SELECT st.vehicle_id, v.plate
                    FROM vehicle_share_token st
                    JOIN vehicle v ON v.id = st.vehicle_id
                    WHERE st.token = ? AND st.expires_at > now()
                    """, token);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }

        long vehicleId = ((Number) meta.get("vehicle_id")).longValue();
        String plate = (String) meta.get("plate");

        Map<String, Object> resp = new LinkedHashMap<>();
        // Redis: vts:pos:{vehicleId} → JSON ile lat/lon/speedKmh/heading/ts
        String cached = redis.opsForValue().get(POS_PREFIX + vehicleId);
        boolean havePos = false;
        if (cached != null) {
            try {
                resp.putAll(json.readValue(cached, MAP_TYPE));
                havePos = true;
            } catch (Exception ignored) {}
        }
        if (!havePos) {
            // DB fallback: vehicle_last_position
            try {
                resp.putAll(jdbc.queryForMap("""
                        SELECT ST_Y(location::geometry) AS lat,
                               ST_X(location::geometry) AS lon,
                               speed_kmh              AS "speedKmh",
                               heading, ts
                        FROM vehicle_last_position
                        WHERE vehicle_id = ?
                        """, vehicleId));
            } catch (Exception e) {
                // Araç var ama hiç konum yok (yeni kayıt).
            }
        }
        resp.put("plate", plate);
        resp.put("vehicleId", vehicleId);
        appendActiveJob(resp, vehicleId);   // aktif görev varsa hedef + ETA ekle
        return ResponseEntity.ok(resp);
    }

    /**
     * Araca atanmış aktif görev varsa müşteri linkine hedefi ve varış tahminini ekler:
     * müşteri "araç nereye gidiyor, yaklaşık ne zaman varır" bilgisini görür.
     */
    private void appendActiveJob(Map<String, Object> resp, long vehicleId) {
        Map<String, Object> job;
        try {
            job = jdbc.queryForMap("""
                    SELECT title, dest_label, dest_lat, dest_lon, status
                    FROM dispatch_job
                    WHERE vehicle_id = ? AND status IN ('ASSIGNED', 'EN_ROUTE', 'ARRIVED')
                    ORDER BY created_at DESC
                    LIMIT 1
                    """, vehicleId);
        } catch (Exception e) {
            return;   // aktif görev yok
        }
        double dlat = ((Number) job.get("dest_lat")).doubleValue();
        double dlon = ((Number) job.get("dest_lon")).doubleValue();
        Map<String, Object> dest = new LinkedHashMap<>();
        dest.put("lat", dlat);
        dest.put("lon", dlon);
        dest.put("label", job.get("dest_label"));
        dest.put("title", job.get("title"));
        resp.put("dest", dest);
        resp.put("jobStatus", job.get("status"));

        Double lat = num(resp.get("lat")), lon = num(resp.get("lon")), spd = num(resp.get("speedKmh"));
        if (lat != null && lon != null) {
            double km = GeoEta.haversineKm(lat, lon, dlat, dlon);
            resp.put("remainingKm", Math.round(km * 10) / 10.0);
            resp.put("etaMin", GeoEta.etaMinutes(km, spd));
        }
    }

    private static Double num(Object o) {
        return (o instanceof Number n) ? n.doubleValue() : null;
    }
}
