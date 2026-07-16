-- Operator -> vehicle text warnings ("fragile cargo, watch your speed", etc.). Stored
-- per vehicle and broadcast to the operators over WebSocket so a warning both persists
-- (shown when the vehicle is clicked) and pops up as a live notification.
CREATE TABLE vehicle_message (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  BIGINT       NOT NULL REFERENCES tenant (id),
    vehicle_id BIGINT       NOT NULL REFERENCES vehicle (id),
    category   VARCHAR(30)  NOT NULL DEFAULT 'GENEL',
    body       VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_vehicle_message_vehicle ON vehicle_message (vehicle_id, created_at DESC);
