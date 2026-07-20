package com.fleet.vts.ingestion.adapter.out.persistence;

import com.fleet.vts.ingestion.domain.FuelStation;
import com.fleet.vts.ingestion.port.out.FuelStationLookupPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fuel stations, straight from the table the gateway's map reads.
 *
 * <p>The PostGIS point is unwrapped into plain lat/lon here so callers need no spatial types.
 * There are ~160 rows and they never change at runtime, so this is read whole rather than
 * queried per lookup — the simulator caches the list at startup.
 */
@Component
public class FuelStationJdbcAdapter implements FuelStationLookupPort {

    private static final String SQL = """
            SELECT id, name, brand,
                   ST_Y(location::geometry) AS lat,
                   ST_X(location::geometry) AS lon
            FROM fuel_station
            ORDER BY id
            """;

    private final JdbcTemplate jdbc;

    public FuelStationJdbcAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<FuelStation> findAll() {
        return jdbc.query(SQL, (rs, n) -> new FuelStation(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("brand"),
                rs.getDouble("lat"),
                rs.getDouble("lon")));
    }
}
