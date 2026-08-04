package com.fleet.vts.gateway.track;

import com.fleet.vts.gateway.web.Plates;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

/**
 * Plate + password authentication for the driver app. The vehicle is created by an admin (with a
 * real plate and an admin-set password); the driver signs in TO that vehicle — drivers never
 * create vehicles themselves. Both failure modes (unknown plate, wrong password) return the same
 * empty result, so the endpoint cannot be used to probe which plates exist.
 */
@Service
public class DriverAuthService {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public DriverAuthService(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    public record DriverIdentity(long vehicleId, long tenantId, String imei,
                                 String plate, String make, String model) {
    }

    @Transactional
    public Optional<DriverIdentity> login(String plate, String password, String deviceId) {
        List<Vehicle> found = jdbc.query(
                "SELECT id, tenant_id, plate, make, model FROM vehicle WHERE replace(upper(plate), ' ', '') = ?",
                (rs, n) -> new Vehicle(rs.getLong("id"), rs.getLong("tenant_id"),
                        rs.getString("plate"), rs.getString("make"), rs.getString("model")),
                Plates.normalize(plate));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Vehicle v = found.get(0);

        List<String> hash = jdbc.query(
                "SELECT password_hash FROM driver_login WHERE vehicle_id = ?",
                (rs, n) -> rs.getString(1), v.id);
        if (hash.isEmpty() || !encoder.matches(password, hash.get(0))) {
            return Optional.empty();
        }

        String imei = ensureDevice(v, deviceId);
        return Optional.of(new DriverIdentity(v.id, v.tenantId, imei, v.plate, v.make, v.model));
    }

    /**
     * The IMEI this vehicle's telemetry streams under: reuse its existing device row if any (so
     * different phones over time all resolve to the same vehicle), otherwise create one derived
     * from this phone's deviceId. An admin-created vehicle has no device until its first driver
     * login — this is where that device row is minted.
     */
    private String ensureDevice(Vehicle v, String deviceId) {
        List<String> existing = jdbc.query(
                "SELECT imei FROM device WHERE vehicle_id = ? ORDER BY id LIMIT 1",
                (rs, n) -> rs.getString(1), v.id);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String imei = imeiFor(deviceId);
        jdbc.update("""
                        INSERT INTO device (tenant_id, vehicle_id, imei, model, firmware, status)
                        VALUES (?, ?, ?, 'Phone Browser GPS', 'geolocation', 'ACTIVE')
                        ON CONFLICT (imei) DO UPDATE SET vehicle_id = EXCLUDED.vehicle_id, status = 'ACTIVE'
                        """, v.tenantId, v.id, imei);
        return imei;
    }

    /** Stable 15-digit IMEI for a phone's deviceId (prefix 9 + 14 digits of SHA-256). */
    private static String imeiFor(String deviceId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(deviceId.getBytes(StandardCharsets.UTF_8));
            BigInteger n = new BigInteger(1, digest).mod(BigInteger.TEN.pow(14));
            return "9" + String.format("%014d", n);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record Vehicle(long id, long tenantId, String plate, String make, String model) {
    }
}
