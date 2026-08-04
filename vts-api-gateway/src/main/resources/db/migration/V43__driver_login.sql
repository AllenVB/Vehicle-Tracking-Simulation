-- Katman 1a — Sürücü uygulaması girişi (plaka + model + şifre).
--
-- Eşzamanlı tek-oturum kontrolü Redis'te TTL ile yaşar (bkz DriverSessionService);
-- burada yalnızca KALICI kimlik bilgisi (araç başına şifre) tutulur. Üretilen hash
-- Spring'in BCryptPasswordEncoder'ı ile birebir uyumlu ($2a$) — pgcrypto'nun bf
-- (blowfish/bcrypt) salt'ı bunu verir.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE driver_login (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL REFERENCES tenant(id),
    vehicle_id    BIGINT NOT NULL REFERENCES vehicle(id) ON DELETE CASCADE,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_driver_login_vehicle UNIQUE (vehicle_id)
);

-- Mevcut gerçek takip aracı için başlangıç şifresi, sürücü sayfası kutudan
-- çıktığı gibi denenebilsin diye: plaka '34 GPS 001', model 'Canlı GPS',
-- şifre '1234'. Admin UI'dan değiştirilebilir (POST .../driver-credential).
INSERT INTO driver_login (tenant_id, vehicle_id, password_hash)
SELECT v.tenant_id, v.id, crypt('1234', gen_salt('bf'))
FROM vehicle v
WHERE v.plate = '34 GPS 001'
ON CONFLICT (vehicle_id) DO NOTHING;
