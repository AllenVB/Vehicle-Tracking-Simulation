package com.fleet.vts.gateway.web;

import com.fleet.vts.testsupport.VtsContainers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Araç/sürücü karnesi, gerçek DB'ye karşı: puan (100 − ceza), harf notu ve sıralama, ham
 * {@code violation} verisinden okuma anında hesaplanır. İhlali olmayan araç 100/A alır; her
 * ağır ihlal 10 puan düşürür. Sıralama: yüksek puan önce.
 *
 * <p>Sabit id'ler ve temiz-önce-ekle ile idempotenttir (reused container'da da tekrarlanabilir).
 */
class ScorecardIT {

    private static JdbcTemplate jdbc;

    private static final Jwt JWT = Jwt.withTokenValue("t").header("alg", "none")
            .subject("admin").claim("tenantId", 1L).build();

    @BeforeAll
    static void seed() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                VtsContainers.postgresJdbcUrl(), VtsContainers.postgresUsername(), VtsContainers.postgresPassword());
        jdbc = new JdbcTemplate(ds);

        // Idempotent: id GENERATED ALWAYS olduğundan plakaya göre temizle, sonra ekle.
        jdbc.update("DELETE FROM violation WHERE vehicle_id IN (SELECT id FROM vehicle WHERE plate LIKE 'IT-SCORE-%')");
        jdbc.update("DELETE FROM vehicle WHERE plate LIKE 'IT-SCORE-%'");

        insertVehicle("IT-SCORE-A");                                     // temiz → 100
        long b = insertVehicle("IT-SCORE-B");
        long c = insertVehicle("IT-SCORE-C");

        insertHighViolation(b);                                          // 1 ağır → −10 → 90
        for (int i = 0; i < 5; i++) insertHighViolation(c);              // 5 ağır → −50 → 50
    }

    private static long insertVehicle(String plate) {
        // id DB tarafından üretilir; type 'CAR' (default, geçerli FK); fuel_type/status geçerli.
        return jdbc.queryForObject("INSERT INTO vehicle (tenant_id, plate, fuel_type, status, odometer_km) "
                + "VALUES (1, ?, 'GASOLINE', 'ACTIVE', 0) RETURNING id", Long.class, plate);
    }

    private static void insertHighViolation(long vehicleId) {
        jdbc.update("INSERT INTO violation (tenant_id, vehicle_id, rule_id, rule_code, type, severity, occurred_at) "
                + "VALUES (1, ?, 10, 'SPEED_LIMIT', 'SPEED_LIMIT', 'HIGH', now())", vehicleId);
    }

    private static Map<String, Object> byPlate(List<Map<String, Object>> rows, String plate) {
        return rows.stream().filter(r -> plate.equals(r.get("plate"))).findFirst().orElseThrow();
    }

    @Test
    void scoresGradesAndRanksFromRealViolationData() {
        List<Map<String, Object>> rows = new ScorecardController(jdbc).list(JWT);

        Map<String, Object> clean = byPlate(rows, "IT-SCORE-A");
        Map<String, Object> oneHigh = byPlate(rows, "IT-SCORE-B");
        Map<String, Object> fiveHigh = byPlate(rows, "IT-SCORE-C");

        assertThat(clean).containsEntry("score", 100).containsEntry("grade", "A").containsEntry("violations", 0L);
        assertThat(oneHigh).containsEntry("score", 90).containsEntry("grade", "A").containsEntry("violations", 1L);
        assertThat(fiveHigh).containsEntry("score", 50).containsEntry("grade", "D").containsEntry("violations", 5L);

        // En yüksek puan önce: temiz < 1-ağır < 5-ağır (rank artan).
        assertThat((int) clean.get("rank"))
                .isLessThan((int) oneHigh.get("rank"))
                .isLessThan((int) fiveHigh.get("rank"));
        assertThat((int) oneHigh.get("rank")).isLessThan((int) fiveHigh.get("rank"));
    }
}
