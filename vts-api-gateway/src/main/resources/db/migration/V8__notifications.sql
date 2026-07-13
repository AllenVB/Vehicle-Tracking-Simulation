-- Notification templates, per-user channel preferences, the notifications
-- themselves and their per-channel delivery attempts.

CREATE TABLE notification_template (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  BIGINT       NOT NULL REFERENCES tenant (id),
    code       VARCHAR(60)  NOT NULL,
    channel    VARCHAR(20)  NOT NULL
                   CHECK (channel IN ('WEBSOCKET', 'EMAIL', 'SMS', 'PUSH')),
    rule_code  VARCHAR(40),
    locale     VARCHAR(10)  NOT NULL DEFAULT 'tr',
    subject    VARCHAR(160),
    body       TEXT         NOT NULL,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_template UNIQUE (tenant_id, code, channel, locale)
);

CREATE TABLE notification_preference (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT      NOT NULL REFERENCES tenant (id),
    user_id           BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    channel           VARCHAR(20) NOT NULL
                          CHECK (channel IN ('WEBSOCKET', 'EMAIL', 'SMS', 'PUSH')),
    rule_code         VARCHAR(40),          -- NULL = applies to all rules
    enabled           BOOLEAN     NOT NULL DEFAULT TRUE,
    quiet_hours_start TIME,
    quiet_hours_end   TIME,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_pref UNIQUE (user_id, channel, rule_code)
);

CREATE TABLE notification (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL REFERENCES tenant (id),
    user_id             BIGINT       REFERENCES app_user (id),
    driver_id           BIGINT       REFERENCES driver (id),
    vehicle_id          BIGINT       REFERENCES vehicle (id),
    rule_code           VARCHAR(40),
    severity            VARCHAR(20)
                            CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    channel             VARCHAR(20)  NOT NULL
                            CHECK (channel IN ('WEBSOCKET', 'EMAIL', 'SMS', 'PUSH')),
    title               VARCHAR(160),
    body                TEXT,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SUPPRESSED')),
    source_violation_id BIGINT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at             TIMESTAMPTZ
);

CREATE TABLE notification_delivery_attempt (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    notification_id BIGINT      NOT NULL REFERENCES notification (id) ON DELETE CASCADE,
    channel         VARCHAR(20) NOT NULL,
    attempt_no      INTEGER     NOT NULL DEFAULT 1,
    status          VARCHAR(20) NOT NULL
                        CHECK (status IN ('SUCCESS', 'FAILED')),
    error           VARCHAR(255),
    attempted_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
