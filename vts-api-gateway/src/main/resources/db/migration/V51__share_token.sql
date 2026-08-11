-- Müşteri takip linki: tek bir araç için 24 saatlik, JWT'siz paylaşım tokeni.
-- Birden fazla token üretilebilir (her paylaşımda yeni); süresi dolmuşlar
-- WHERE expires_at > now() filtresiyle otomatik devre dışı kalır.
CREATE TABLE vehicle_share_token (
    id         BIGSERIAL    PRIMARY KEY,
    vehicle_id BIGINT       NOT NULL REFERENCES vehicle(id) ON DELETE CASCADE,
    tenant_id  BIGINT       NOT NULL,
    token      TEXT         NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_share_token_expires ON vehicle_share_token(expires_at);
