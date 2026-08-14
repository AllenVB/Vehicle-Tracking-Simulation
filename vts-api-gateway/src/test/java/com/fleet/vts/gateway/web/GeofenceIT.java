package com.fleet.vts.gateway.web;

import com.fleet.vts.testsupport.VtsContainers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Geofence oluşturma, gerçek TimescaleDB + PostGIS'e karşı (mock'lanamaz: WKT üretimi ve
 * geography dönüşümü tam olarak burada yaşar). Kritik iki hata alanı test ediliyor:
 * <ul>
 *   <li>Koordinat sırası: harita [lat, lon] verir, WKT longitude-first ister — ters çevrilirse
 *       zon denize düşer. İlk köşenin X'i (lon) = 29, Y'si (lat) = 41 olmalı.</li>
 *   <li>Halka kapanışı: {@code ST_MakePolygon} kapalı ring ister; açık gelen ring kapatılmalı
 *       (4 köşe → 5 nokta).</li>
 * </ul>
 * Ayrıca zon silinmez, {@code active=false} yapılır (ihlal geçmişi ona referans verir).
 */
class GeofenceIT {

    private static JdbcTemplate jdbc;

    private static final Jwt JWT = Jwt.withTokenValue("t").header("alg", "none")
            .subject("admin").claim("tenantId", 1L).build();   // tenant 1 = seed'li "Demo Filo A.Ş."

    @BeforeAll
    static void connectToMigratedDatabase() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                VtsContainers.postgresJdbcUrl(), VtsContainers.postgresUsername(), VtsContainers.postgresPassword());
        jdbc = new JdbcTemplate(ds);
    }

    private GeofenceController controller() {
        return new GeofenceController(jdbc);
    }

    /** [lat, lon] köşeleri (haritanın verdiği sıra); ring bilinçli AÇIK bırakılır. */
    private static List<List<Double>> square() {
        List<List<Double>> pts = new ArrayList<>();
        pts.add(List.of(41.0, 29.0));
        pts.add(List.of(41.0, 29.1));
        pts.add(List.of(41.1, 29.1));
        pts.add(List.of(41.1, 29.0));
        return pts;
    }

    @Test
    void storesPolygonLonFirstAndClosesTheRing() {
        ResponseEntity<Map<String, Object>> res = controller().create(JWT,
                new GeofenceController.GeofenceRequest("Test Bölge", "EXCLUSION", square()));

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        long id = ((Number) res.getBody().get("id")).longValue();

        // 4 köşe → kapalı ring 5 nokta.
        Integer nPoints = jdbc.queryForObject(
                "SELECT ST_NPoints(area::geometry) FROM geofence WHERE id = ?", Integer.class, id);
        assertThat(nPoints).isEqualTo(5);

        // İlk köşe longitude-first saklanmış: X = 29 (lon), Y = 41 (lat).
        Double firstLon = jdbc.queryForObject(
                "SELECT ST_X(ST_PointN(ST_ExteriorRing(area::geometry), 1)) FROM geofence WHERE id = ?",
                Double.class, id);
        Double firstLat = jdbc.queryForObject(
                "SELECT ST_Y(ST_PointN(ST_ExteriorRing(area::geometry), 1)) FROM geofence WHERE id = ?",
                Double.class, id);
        assertThat(firstLon).isEqualTo(29.0);
        assertThat(firstLat).isEqualTo(41.0);
    }

    @Test
    void rejectsTooFewVertices() {
        List<List<Double>> line = new ArrayList<>();
        line.add(List.of(41.0, 29.0));
        line.add(List.of(41.0, 29.1));

        assertThat(controller().create(JWT, new GeofenceController.GeofenceRequest("Çizgi", "EXCLUSION", line))
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void rejectsUnknownKind() {
        assertThat(controller().create(JWT, new GeofenceController.GeofenceRequest("X", "FORBIDDEN", square()))
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void rejectsOutOfRangePoint() {
        List<List<Double>> bad = square();
        bad.set(0, List.of(200.0, 29.0));   // geçersiz enlem

        assertThat(controller().create(JWT, new GeofenceController.GeofenceRequest("X", "EXCLUSION", bad))
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void deactivateHidesButKeepsTheRow() {
        long id = ((Number) controller().create(JWT,
                new GeofenceController.GeofenceRequest("Silinecek", "INCLUSION", square())).getBody().get("id")).longValue();

        assertThat(controller().deactivate(JWT, id).getStatusCode().value()).isEqualTo(204);

        Boolean active = jdbc.queryForObject("SELECT active FROM geofence WHERE id = ?", Boolean.class, id);
        assertThat(active).isFalse();   // satır duruyor, yalnızca pasif
    }

    @Test
    void deactivateUnknownReturns404() {
        assertThat(controller().deactivate(JWT, 999_999_999L).getStatusCode().value()).isEqualTo(404);
    }
}
