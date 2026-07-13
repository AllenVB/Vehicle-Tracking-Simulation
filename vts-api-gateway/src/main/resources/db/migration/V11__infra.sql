-- Cross-cutting infrastructure tables.

-- Transactional outbox: services write a business row and an outbox row in the
-- SAME transaction; a publisher (Debezium, or the scheduler fallback) relays
-- rows to Kafka. Guarantees DB-write / Kafka-publish atomicity.
CREATE TABLE outbox_event (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL,
    aggregate_type VARCHAR(60)  NOT NULL,   -- e.g. 'violation', 'trip'
    aggregate_id   VARCHAR(60)  NOT NULL,
    event_type     VARCHAR(60)  NOT NULL,
    topic          VARCHAR(120) NOT NULL,
    partition_key  VARCHAR(120) NOT NULL,   -- usually vehicleId, preserves ordering
    payload        JSONB        NOT NULL,
    headers        JSONB,
    status         VARCHAR(15)  NOT NULL DEFAULT 'PENDING'
                       CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    attempts       INTEGER      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

-- One row per scheduled-job run (audit + observability of the scheduler).
CREATE TABLE job_execution (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_name      VARCHAR(80) NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    status        VARCHAR(15) NOT NULL DEFAULT 'RUNNING'
                      CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
    node          VARCHAR(80),
    rows_affected BIGINT,
    detail        JSONB
);

-- ShedLock backing table (distributed lock for the scheduler). Not one of the
-- 38 business tables; it is required plumbing for JdbcTemplateLockProvider.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
