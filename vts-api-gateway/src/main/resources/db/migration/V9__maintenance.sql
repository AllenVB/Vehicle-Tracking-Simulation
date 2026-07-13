-- Maintenance plans/records and fuel events (refuel / drop / suspected theft).

CREATE TABLE maintenance_plan (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL REFERENCES tenant (id),
    vehicle_id     BIGINT       REFERENCES vehicle (id),
    name           VARCHAR(120) NOT NULL,
    interval_km    INTEGER,          -- distance-based reminder
    interval_days  INTEGER,          -- time-based reminder
    last_service_km BIGINT,
    last_service_at TIMESTAMPTZ,
    next_due_km    BIGINT,
    next_due_at    TIMESTAMPTZ,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE maintenance_record (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL REFERENCES tenant (id),
    vehicle_id   BIGINT       NOT NULL REFERENCES vehicle (id),
    plan_id      BIGINT       REFERENCES maintenance_plan (id),
    service_at   TIMESTAMPTZ  NOT NULL,
    odometer_km  BIGINT,
    cost         NUMERIC(12, 2),
    currency     VARCHAR(3)   NOT NULL DEFAULT 'TRY',
    notes        VARCHAR(255),
    performed_by VARCHAR(120),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE fuel_event (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      BIGINT      NOT NULL REFERENCES tenant (id),
    vehicle_id     BIGINT      NOT NULL REFERENCES vehicle (id),
    ts             TIMESTAMPTZ NOT NULL,
    fuel_level_pct SMALLINT,
    delta_pct      SMALLINT,         -- signed change vs previous reading
    kind           VARCHAR(15) NOT NULL DEFAULT 'REFUEL'
                       CHECK (kind IN ('REFUEL', 'DROP', 'THEFT_SUSPECT')),
    location       GEOGRAPHY(POINT, 4326),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
