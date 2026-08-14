package com.fleet.vts.gateway.web;

import com.fleet.vts.testsupport.VtsContainers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bakım "Yapıldı" işaretlenince plan bir sonraki döngüye kaydırılır — gerçek DB'ye karşı.
 * Kritik kural: yeni vade, önceki vadeden değil <em>servis anındaki odometreden</em> hesaplanır;
 * aksi halde geç yapılan her servis bir sonraki aralığı tam o kadar kısaltır ve program sürekli
 * sıkışırdı. Ayrıca bir {@code maintenance_record} yazılır.
 */
class MaintenanceRollForwardIT {

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void connect() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                VtsContainers.postgresJdbcUrl(), VtsContainers.postgresUsername(), VtsContainers.postgresPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.update("DELETE FROM maintenance_record WHERE performed_by = 'IT-ROLLFWD'");
        jdbc.update("DELETE FROM maintenance_plan WHERE name = 'IT-ROLLFWD'");
        jdbc.update("DELETE FROM vehicle WHERE plate LIKE 'IT-MTN-%'");
    }

    @Test
    void doneRecomputesNextDueFromServiceOdometerAndRecordsIt() {
        long vehicleId = jdbc.queryForObject("INSERT INTO vehicle (tenant_id, plate, fuel_type, status, odometer_km) "
                + "VALUES (1, 'IT-MTN-1', 'DIESEL', 'ACTIVE', 50000) RETURNING id", Long.class);

        long planId = jdbc.queryForObject("""
                INSERT INTO maintenance_plan
                    (tenant_id, vehicle_id, name, interval_km, last_service_km, last_service_at,
                     next_due_km, enabled, status)
                VALUES (1, ?, 'IT-ROLLFWD', 10000, 45000, now(), 55000, true, 'PENDING')
                RETURNING id
                """, Long.class, vehicleId);

        new MaintenanceController(jdbc).applyStatus(vehicleId, planId, "DONE", "IT-ROLLFWD");

        var plan = jdbc.queryForMap(
                "SELECT status, next_due_km, last_service_km FROM maintenance_plan WHERE id = ?", planId);
        // Servis odometresi 50000, aralık 10000 → yeni vade 60000 (önceki 55000'den DEĞİL).
        assertThat(((Number) plan.get("next_due_km")).longValue()).isEqualTo(60000L);
        assertThat(((Number) plan.get("last_service_km")).longValue()).isEqualTo(50000L);
        assertThat(plan.get("status")).isEqualTo("DONE");

        Integer records = jdbc.queryForObject(
                "SELECT count(*) FROM maintenance_record WHERE plan_id = ? AND performed_by = 'IT-ROLLFWD'",
                Integer.class, planId);
        assertThat(records).isEqualTo(1);
    }
}
