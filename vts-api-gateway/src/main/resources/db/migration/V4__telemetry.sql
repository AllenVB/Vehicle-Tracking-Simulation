-- High-volume telemetry. This is a TimescaleDB hypertable and is written ONLY
-- with JdbcTemplate.batchUpdate() + ON CONFLICT DO NOTHING. There is no JPA
-- entity and there are deliberately NO foreign keys here: FK checks would add a
-- per-row lookup that batch inserts at 1000 msg/s cannot afford. vehicle_id and
-- tenant_id are plain columns validated upstream at ingestion.
CREATE TABLE telemetry (
    tenant_id   BIGINT      NOT NULL,
    vehicle_id  BIGINT      NOT NULL,
    device_id   BIGINT,
    ts          TIMESTAMPTZ NOT NULL,
    location    GEOGRAPHY(POINT, 4326),
    speed_kmh   SMALLINT,          -- 0..~300, smallint saves space at scale
    heading     SMALLINT,          -- 0..359
    battery     SMALLINT,          -- percent 0..100
    fuel_pct    SMALLINT,          -- percent 0..100
    engine_on   BOOLEAN,
    ignition    BOOLEAN,
    odometer_km BIGINT,
    -- (vehicle_id, ts) is the natural key; it includes both the time and the
    -- space partitioning column, satisfying TimescaleDB's unique-index rule and
    -- giving ingestion a clean ON CONFLICT (vehicle_id, ts) target.
    CONSTRAINT pk_telemetry PRIMARY KEY (vehicle_id, ts)
);

-- Time partitioning by ts; space (hash) partitioning by vehicle_id into 8
-- partitions. chunk interval is profile-driven via a Flyway placeholder
-- (dev: 1 day, load: 1 hour). Making a bloated plain table into a hypertable
-- later means downtime, so it is a hypertable from day one.
SELECT create_hypertable(
    'telemetry',
    by_range('ts', INTERVAL '${telemetry_chunk_interval}')
);
SELECT add_dimension('telemetry', by_hash('vehicle_id', 8));

-- Exactly one row per vehicle, continuously UPSERTed. Serves as the cold-start
-- fallback for the Redis last-position cache. Low volume (=fleet size), so a
-- real FK to vehicle is fine here.
CREATE TABLE vehicle_last_position (
    vehicle_id BIGINT      PRIMARY KEY REFERENCES vehicle (id),
    tenant_id  BIGINT      NOT NULL REFERENCES tenant (id),
    ts         TIMESTAMPTZ NOT NULL,
    location   GEOGRAPHY(POINT, 4326),
    speed_kmh  SMALLINT,
    heading    SMALLINT,
    engine_on  BOOLEAN,
    ignition   BOOLEAN,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
