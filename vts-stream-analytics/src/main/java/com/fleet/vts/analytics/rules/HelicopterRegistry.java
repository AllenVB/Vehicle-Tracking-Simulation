package com.fleet.vts.analytics.rules;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * The set of vehicle IDs that are helicopters. They fly, so the road-based stateful
 * rules — harsh braking, sustained speeding, geofence enter/exit — must not apply:
 * a helicopter cruising at 250 km/h over the restricted zone is not a violation.
 * Trip detection and idling still apply (a flight is a trip; a landed helicopter can idle).
 */
@Component
public class HelicopterRegistry {

    private static final Logger log = LoggerFactory.getLogger(HelicopterRegistry.class);

    private final JdbcTemplate jdbc;
    private volatile Set<Long> ids = Set.of();

    @Autowired
    public HelicopterRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Test constructor: preloaded ids, no database. */
    public HelicopterRegistry(Set<Long> ids) {
        this.jdbc = null;
        this.ids = Set.copyOf(ids);
    }

    @PostConstruct
    @Scheduled(fixedDelay = 300_000)   // the fleet is static, but re-resolve occasionally
    public void load() {
        if (jdbc == null) {
            return;
        }
        Set<Long> loaded = new HashSet<>();
        jdbc.query("SELECT id FROM vehicle WHERE type = 'HELICOPTER'",
                rs -> { loaded.add(rs.getLong("id")); });
        this.ids = Set.copyOf(loaded);
        log.info("Helicopters loaded: {} ({})", loaded.size(), loaded);
    }

    /** The stream is keyed by vehicleId as a string. */
    public boolean isHelicopter(String vehicleId) {
        try {
            return ids.contains(Long.valueOf(vehicleId));
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
