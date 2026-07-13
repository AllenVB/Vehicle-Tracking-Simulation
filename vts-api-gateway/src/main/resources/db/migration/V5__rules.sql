-- Rule engine configuration and the resulting violations.

-- A rule definition. Thresholds live here (never hard-coded) and are cached in
-- the services with a short TTL, invalidated over Kafka on change.
CREATE TABLE rule (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT       NOT NULL REFERENCES tenant (id),
    code             VARCHAR(40)  NOT NULL,
    name             VARCHAR(120) NOT NULL,
    type             VARCHAR(40)  NOT NULL
                         CHECK (type IN ('SPEED_LIMIT', 'LOW_BATTERY', 'LOW_FUEL',
                                         'HARSH_BRAKING', 'SUSTAINED_SPEEDING', 'IDLING',
                                         'GEOFENCE_ENTER', 'GEOFENCE_EXIT')),
    severity         VARCHAR(20)  NOT NULL DEFAULT 'MEDIUM'
                         CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    threshold_value  NUMERIC(10, 2),          -- e.g. 80 km/h, 20 %, -40 km/h delta
    window_seconds   INTEGER,                 -- for windowed/stateful rules
    cooldown_seconds INTEGER      NOT NULL DEFAULT 300,  -- notification de-dup window
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    description      VARCHAR(255),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_rule_code UNIQUE (tenant_id, code)
);

-- Scoped overrides: a rule can apply to the whole tenant, a group or a single
-- vehicle, with an optional threshold override (e.g. trucks 80, cars 110).
CREATE TABLE rule_assignment (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT      NOT NULL REFERENCES tenant (id),
    rule_id            BIGINT      NOT NULL REFERENCES rule (id) ON DELETE CASCADE,
    scope_type         VARCHAR(10) NOT NULL
                           CHECK (scope_type IN ('TENANT', 'GROUP', 'VEHICLE')),
    -- References vehicle_group.id or vehicle.id depending on scope_type;
    -- NULL when scope_type = TENANT. Kept generic on purpose (no FK).
    scope_id           BIGINT,
    threshold_override NUMERIC(10, 2),
    enabled            BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_scope_id CHECK (
        (scope_type = 'TENANT' AND scope_id IS NULL) OR
        (scope_type IN ('GROUP', 'VEHICLE') AND scope_id IS NOT NULL)
    )
);

-- Violations. Hypertable because they are produced at telemetry-adjacent rates
-- (~10% of vehicles speeding). No FKs for the same throughput reason as
-- telemetry; identifiers are validated by the producing service.
CREATE TABLE violation (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY,
    tenant_id   BIGINT      NOT NULL,
    vehicle_id  BIGINT      NOT NULL,
    driver_id   BIGINT,
    device_id   BIGINT,
    rule_id     BIGINT      NOT NULL,
    rule_code   VARCHAR(40) NOT NULL,
    type        VARCHAR(40) NOT NULL,
    severity    VARCHAR(20) NOT NULL
                    CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    occurred_at TIMESTAMPTZ NOT NULL,
    value       NUMERIC(10, 2),        -- measured value (e.g. actual speed)
    threshold   NUMERIC(10, 2),        -- threshold that was breached
    location    GEOGRAPHY(POINT, 4326),
    trip_id     BIGINT,
    detail      JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- PK must include the time partitioning column; id is globally unique via
    -- the identity sequence, so (id, occurred_at) is a safe primary key.
    CONSTRAINT pk_violation PRIMARY KEY (id, occurred_at)
);

SELECT create_hypertable(
    'violation',
    by_range('occurred_at', INTERVAL '${telemetry_chunk_interval}')
);

-- Acknowledgement of a violation by a user. Deliberately no FK to violation:
-- TimescaleDB does not support a foreign key that references a hypertable.
-- violation_occurred_at is stored so the row can be located with chunk pruning.
CREATE TABLE violation_ack (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id             BIGINT      NOT NULL REFERENCES tenant (id),
    violation_id          BIGINT      NOT NULL,
    violation_occurred_at TIMESTAMPTZ NOT NULL,
    acked_by              BIGINT      NOT NULL REFERENCES app_user (id),
    acked_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    note                  VARCHAR(255),
    CONSTRAINT uq_violation_ack UNIQUE (violation_id)
);
