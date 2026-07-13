-- Tenancy and identity. Every business table below carries tenant_id so the
-- application can enforce isolation with a Hibernate @Filter.

CREATE TABLE tenant (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(120) NOT NULL,
    slug       VARCHAR(60)  NOT NULL,
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    timezone   VARCHAR(60)  NOT NULL DEFAULT 'Europe/Istanbul',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_slug UNIQUE (slug)
);

-- System-wide roles (ADMIN, FLEET_MANAGER, DRIVER, VIEWER). Not tenant scoped.
CREATE TABLE role (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code       VARCHAR(40)  NOT NULL,
    name       VARCHAR(80)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_role_code UNIQUE (code)
);

CREATE TABLE app_user (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenant (id),
    username      VARCHAR(80)  NOT NULL,
    email         VARCHAR(160) NOT NULL,
    password_hash VARCHAR(120) NOT NULL,
    full_name     VARCHAR(160),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Set when this login belongs to a driver (driver FK added in V3).
    driver_id     BIGINT,
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_username UNIQUE (tenant_id, username),
    CONSTRAINT uq_user_email    UNIQUE (tenant_id, email)
);

CREATE TABLE user_role (
    user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES role (id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE refresh_token (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash VARCHAR(120) NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash)
);

CREATE TABLE audit_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL REFERENCES tenant (id),
    user_id     BIGINT      REFERENCES app_user (id),
    action      VARCHAR(80) NOT NULL,
    entity_type VARCHAR(80),
    entity_id   VARCHAR(80),
    detail      JSONB,
    ip_address  INET,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
