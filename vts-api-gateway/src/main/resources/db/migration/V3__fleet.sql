-- Fleet master data: groups, drivers, vehicles, the temporal driver assignment,
-- devices, SIM cards, heartbeats, remote commands and points of interest.

CREATE TABLE vehicle_group (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  BIGINT       NOT NULL REFERENCES tenant (id),
    parent_id  BIGINT       REFERENCES vehicle_group (id),
    name       VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_group_name UNIQUE (tenant_id, name)
);

CREATE TABLE driver (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES tenant (id),
    first_name  VARCHAR(80)  NOT NULL,
    last_name   VARCHAR(80)  NOT NULL,
    license_no  VARCHAR(40),
    phone       VARCHAR(30),
    email       VARCHAR(160),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED')),
    hire_date   DATE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_driver_license UNIQUE (tenant_id, license_no)
);

-- Now that driver exists, wire app_user.driver_id.
ALTER TABLE app_user
    ADD CONSTRAINT fk_user_driver FOREIGN KEY (driver_id) REFERENCES driver (id);

CREATE TABLE vehicle (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenant (id),
    group_id      BIGINT       REFERENCES vehicle_group (id),
    -- Convenience pointer to the currently assigned driver. Authoritative
    -- attribution is via vehicle_driver_assignment (a vehicle changes hands).
    current_driver_id BIGINT   REFERENCES driver (id),
    plate         VARCHAR(20)  NOT NULL,
    vin           VARCHAR(40),
    make          VARCHAR(60),
    model         VARCHAR(60),
    year          SMALLINT,
    type          VARCHAR(20)  NOT NULL DEFAULT 'CAR'
                      CHECK (type IN ('CAR', 'VAN', 'TRUCK', 'BUS', 'MOTORCYCLE')),
    fuel_type     VARCHAR(20)  NOT NULL DEFAULT 'DIESEL'
                      CHECK (fuel_type IN ('DIESEL', 'GASOLINE', 'LPG', 'ELECTRIC', 'HYBRID')),
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                      CHECK (status IN ('ACTIVE', 'INACTIVE', 'MAINTENANCE')),
    odometer_km   BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_vehicle_plate UNIQUE (tenant_id, plate)
);

-- Temporal record of which driver operated which vehicle and when. Required to
-- attribute a violation to the correct driver (vehicle.current_driver_id alone
-- is not enough because it only reflects the present).
CREATE TABLE vehicle_driver_assignment (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL REFERENCES tenant (id),
    vehicle_id  BIGINT      NOT NULL REFERENCES vehicle (id),
    driver_id   BIGINT      NOT NULL REFERENCES driver (id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE device (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL REFERENCES tenant (id),
    vehicle_id   BIGINT       REFERENCES vehicle (id),
    imei         VARCHAR(20)  NOT NULL,
    model        VARCHAR(60),
    firmware     VARCHAR(40),
    status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                     CHECK (status IN ('ACTIVE', 'INACTIVE', 'OFFLINE')),
    last_seen_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_device_imei UNIQUE (imei)
);

CREATE TABLE sim_card (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  BIGINT       NOT NULL REFERENCES tenant (id),
    device_id  BIGINT       REFERENCES device (id),
    iccid      VARCHAR(25)  NOT NULL,
    msisdn     VARCHAR(20),
    carrier    VARCHAR(60),
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED')),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_sim_iccid UNIQUE (iccid)
);

CREATE TABLE device_heartbeat (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL REFERENCES tenant (id),
    device_id   BIGINT      NOT NULL REFERENCES device (id),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    rssi        SMALLINT,
    battery     SMALLINT
);

CREATE TABLE device_command (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    BIGINT      NOT NULL REFERENCES tenant (id),
    device_id    BIGINT      NOT NULL REFERENCES device (id),
    command_type VARCHAR(40) NOT NULL,
    payload      JSONB,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'SENT', 'ACK', 'FAILED')),
    issued_by    BIGINT      REFERENCES app_user (id),
    issued_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    executed_at  TIMESTAMPTZ
);

-- Points of interest (depots, customer sites). Spatial point in WGS84.
CREATE TABLE poi (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  BIGINT       NOT NULL REFERENCES tenant (id),
    name       VARCHAR(120) NOT NULL,
    category   VARCHAR(60),
    location   GEOGRAPHY(POINT, 4326) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
