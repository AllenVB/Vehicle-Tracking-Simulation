-- Trips detected by the stream analytics topology, their route points and stops.

CREATE TABLE trip (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      BIGINT      NOT NULL REFERENCES tenant (id),
    vehicle_id     BIGINT      NOT NULL REFERENCES vehicle (id),
    driver_id      BIGINT      REFERENCES driver (id),
    started_at     TIMESTAMPTZ NOT NULL,
    ended_at       TIMESTAMPTZ,
    start_location GEOGRAPHY(POINT, 4326),
    end_location   GEOGRAPHY(POINT, 4326),
    distance_km    NUMERIC(10, 2),
    avg_speed_kmh  NUMERIC(6, 2),
    max_speed_kmh  SMALLINT,
    violation_count INTEGER    NOT NULL DEFAULT 0,
    status         VARCHAR(10) NOT NULL DEFAULT 'ONGOING'
                       CHECK (status IN ('ONGOING', 'CLOSED')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Ordered breadcrumb of a trip. Rebuilt into a polyline for the route endpoint.
CREATE TABLE trip_point (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    trip_id    BIGINT      NOT NULL REFERENCES trip (id) ON DELETE CASCADE,
    seq        INTEGER     NOT NULL,
    ts         TIMESTAMPTZ NOT NULL,
    location   GEOGRAPHY(POINT, 4326) NOT NULL,
    speed_kmh  SMALLINT,
    heading    SMALLINT,
    CONSTRAINT uq_trip_point_seq UNIQUE (trip_id, seq)
);

CREATE TABLE stop_event (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT      NOT NULL REFERENCES tenant (id),
    vehicle_id       BIGINT      NOT NULL REFERENCES vehicle (id),
    trip_id          BIGINT      REFERENCES trip (id),
    kind             VARCHAR(10) NOT NULL DEFAULT 'IDLE'
                         CHECK (kind IN ('IDLE', 'PARK')),
    started_at       TIMESTAMPTZ NOT NULL,
    ended_at         TIMESTAMPTZ,
    duration_seconds INTEGER,
    location         GEOGRAPHY(POINT, 4326),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
