package com.fleet.vts.gateway.track;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

/**
 * Turns a phone into its OWN vehicle. Each phone holds a persistent random {@code deviceKey}
 * (localStorage); this derives a stable, unique IMEI from it, so two phones are two distinct
 * devices bound to two distinct vehicles. That is the fix for the "which one's data?" confusion:
 * before this, every phone fell back to one shared IMEI and their positions overwrote each other.
 *
 * <p>Enrollment upserts by that IMEI — first time it creates a {@code vehicle} + {@code device};
 * afterwards the same phone updates its own plate/make/model. The IMEI derivation is deterministic
 * (SHA-256 of the deviceKey), so the same phone always lands on the same vehicle across sessions.
 */
@Service
public class EnrollmentService {

    private final JdbcTemplate jdbc;

    public EnrollmentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record EnrolledVehicle(String imei, long vehicleId, String plate, String make, String model) {
    }

    /** Stable 15-digit IMEI for a phone's deviceKey (prefix 9 + 14 digits of SHA-256). */
    static String imeiFor(String deviceKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(deviceKey.getBytes(StandardCharsets.UTF_8));
            BigInteger n = new BigInteger(1, digest).mod(BigInteger.TEN.pow(14));
            return "9" + String.format("%014d", n);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Already-enrolled vehicle for this phone, so the tracker can prefill and reuse it. */
    public Optional<EnrolledVehicle> findByDeviceKey(String deviceKey) {
        String imei = imeiFor(deviceKey);
        return jdbc.query("""
                        SELECT v.id, v.plate, v.make, v.model
                        FROM device d JOIN vehicle v ON v.id = d.vehicle_id
                        WHERE d.imei = ?
                        """,
                (rs, n) -> new EnrolledVehicle(imei, rs.getLong("id"),
                        rs.getString("plate"), rs.getString("make"), rs.getString("model")),
                imei).stream().findFirst();
    }

    @Transactional
    public EnrolledVehicle enroll(String deviceKey, String plate, String make, String model) {
        String imei = imeiFor(deviceKey);
        Long tenantId = jdbc.queryForObject("SELECT id FROM tenant ORDER BY id LIMIT 1", Long.class);

        List<Long> existing = jdbc.query(
                "SELECT v.id FROM device d JOIN vehicle v ON v.id = d.vehicle_id WHERE d.imei = ?",
                (rs, n) -> rs.getLong(1), imei);

        long vehicleId;
        if (!existing.isEmpty()) {
            vehicleId = existing.get(0);
            jdbc.update("UPDATE vehicle SET plate = ?, make = ?, model = ?, updated_at = now() WHERE id = ?",
                    plate, make, model, vehicleId);
        } else {
            vehicleId = jdbc.queryForObject("""
                            INSERT INTO vehicle (tenant_id, plate, vin, make, model, type, fuel_type, status, odometer_km)
                            VALUES (?, ?, ?, ?, ?, 'CAR', 'GASOLINE', 'ACTIVE', 0)
                            RETURNING id
                            """, Long.class, tenantId, plate, "PHONE-" + imei, make, model);
            jdbc.update("""
                            INSERT INTO device (tenant_id, vehicle_id, imei, model, firmware, status)
                            VALUES (?, ?, ?, 'Phone Browser GPS', 'geolocation', 'ACTIVE')
                            """, tenantId, vehicleId, imei);
        }
        return new EnrolledVehicle(imei, vehicleId, plate, make, model);
    }
}
