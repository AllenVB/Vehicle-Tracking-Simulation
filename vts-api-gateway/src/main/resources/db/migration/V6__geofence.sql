-- Geofences (polygons) plus their scoped assignments and the enter/exit events.

CREATE TABLE geofence (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  BIGINT       NOT NULL REFERENCES tenant (id),
    name       VARCHAR(120) NOT NULL,
    -- INCLUSION: alert on leaving; EXCLUSION: alert on entering (restricted).
    kind       VARCHAR(20)  NOT NULL DEFAULT 'EXCLUSION'
                   CHECK (kind IN ('INCLUSION', 'EXCLUSION')),
    area       GEOGRAPHY(POLYGON, 4326) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE geofence_assignment (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL REFERENCES tenant (id),
    geofence_id BIGINT      NOT NULL REFERENCES geofence (id) ON DELETE CASCADE,
    scope_type  VARCHAR(10) NOT NULL
                    CHECK (scope_type IN ('TENANT', 'GROUP', 'VEHICLE')),
    scope_id    BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_geofence_scope_id CHECK (
        (scope_type = 'TENANT' AND scope_id IS NULL) OR
        (scope_type IN ('GROUP', 'VEHICLE') AND scope_id IS NOT NULL)
    )
);

CREATE TABLE geofence_event (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL REFERENCES tenant (id),
    geofence_id BIGINT      NOT NULL REFERENCES geofence (id),
    vehicle_id  BIGINT      NOT NULL REFERENCES vehicle (id),
    driver_id   BIGINT      REFERENCES driver (id),
    event_type  VARCHAR(10) NOT NULL CHECK (event_type IN ('ENTER', 'EXIT')),
    occurred_at TIMESTAMPTZ NOT NULL,
    location    GEOGRAPHY(POINT, 4326),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
