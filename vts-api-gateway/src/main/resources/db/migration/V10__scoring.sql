-- Driver scoring: a daily roll-up plus longer aggregation periods.

CREATE TABLE driver_score_daily (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           BIGINT      NOT NULL REFERENCES tenant (id),
    driver_id           BIGINT      NOT NULL REFERENCES driver (id),
    score_date          DATE        NOT NULL,
    distance_km         NUMERIC(10, 2) NOT NULL DEFAULT 0,
    harsh_braking_count INTEGER     NOT NULL DEFAULT 0,
    speeding_count      INTEGER     NOT NULL DEFAULT 0,
    idling_seconds      INTEGER     NOT NULL DEFAULT 0,
    violation_count     INTEGER     NOT NULL DEFAULT 0,
    score               NUMERIC(5, 2),      -- 0..100
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_score_daily UNIQUE (tenant_id, driver_id, score_date)
);

CREATE TABLE driver_score_period (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       BIGINT      NOT NULL REFERENCES tenant (id),
    driver_id       BIGINT      NOT NULL REFERENCES driver (id),
    period_type     VARCHAR(10) NOT NULL CHECK (period_type IN ('WEEK', 'MONTH')),
    period_start    DATE        NOT NULL,
    period_end      DATE        NOT NULL,
    avg_score       NUMERIC(5, 2),
    distance_km     NUMERIC(12, 2) NOT NULL DEFAULT 0,
    violation_count INTEGER     NOT NULL DEFAULT 0,
    rank            INTEGER,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_score_period UNIQUE (tenant_id, driver_id, period_type, period_start)
);
