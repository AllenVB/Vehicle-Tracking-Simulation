-- Deterministic demo seed: 1 tenant, roles + admin, 2 groups, 200 drivers,
-- 1000 vehicles, 1000 devices + SIMs, open driver assignments, 8 rules with
-- scoped overrides, 5 geofences, POIs and notification templates.
-- Natural keys (slug/license/plate/imei) are used for linking so the seed does
-- not depend on identity id values starting at 1.

-- ── Tenant ────────────────────────────────────────────────────────────────
INSERT INTO tenant (name, slug, timezone)
VALUES ('Demo Filo A.Ş.', 'demo', 'Europe/Istanbul');

-- ── Roles ─────────────────────────────────────────────────────────────────
INSERT INTO role (code, name) VALUES
    ('ADMIN', 'Administrator'),
    ('FLEET_MANAGER', 'Fleet Manager'),
    ('DRIVER', 'Driver'),
    ('VIEWER', 'Viewer');

-- ── Admin user (dev password: "password") ─────────────────────────────────
INSERT INTO app_user (tenant_id, username, email, password_hash, full_name)
SELECT t.id, 'admin', 'admin@demo.local',
       '$2b$10$leGL2nKyCjHUSNWvkgmecuNs/CgY3cqbAEl/ggXS/.HLYVymTrM7S', 'Demo Admin'
FROM tenant t WHERE t.slug = 'demo';

INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id
FROM app_user u, role r
WHERE u.username = 'admin' AND r.code = 'ADMIN';

-- ── Vehicle groups (used to demonstrate rule scope overrides) ─────────────
INSERT INTO vehicle_group (tenant_id, name)
SELECT t.id, g.name
FROM tenant t
CROSS JOIN (VALUES ('Kamyonlar'), ('Otomobiller')) AS g(name)
WHERE t.slug = 'demo';

-- ── 200 drivers ───────────────────────────────────────────────────────────
INSERT INTO driver (tenant_id, first_name, last_name, license_no, phone, email, status, hire_date)
SELECT t.id,
       'Sürücü',
       'No' || n,
       'DRV-' || lpad(n::text, 4, '0'),
       '+9055' || lpad(n::text, 8, '0'),
       'driver' || n || '@demo.local',
       'ACTIVE',
       DATE '2022-01-01'
FROM tenant t, generate_series(1, 200) AS n
WHERE t.slug = 'demo';

-- ── 1000 vehicles (every 3rd is a TRUCK, others CAR) ──────────────────────
INSERT INTO vehicle (tenant_id, group_id, plate, vin, make, model, year, type, fuel_type, status, odometer_km)
SELECT t.id,
       g.id,
       'VTS-' || lpad(n::text, 4, '0'),
       'VIN' || lpad(n::text, 8, '0'),
       CASE WHEN v.type = 'TRUCK' THEN 'Ford' ELSE 'Renault' END,
       CASE WHEN v.type = 'TRUCK' THEN 'Cargo' ELSE 'Megane' END,
       2018 + (n % 7),
       v.type,
       CASE WHEN v.type = 'TRUCK' THEN 'DIESEL' ELSE 'GASOLINE' END,
       'ACTIVE',
       (n * 137) % 300000
FROM tenant t
CROSS JOIN generate_series(1, 1000) AS n
CROSS JOIN LATERAL (SELECT CASE WHEN n % 3 = 0 THEN 'TRUCK' ELSE 'CAR' END AS type) AS v
JOIN vehicle_group g
     ON g.tenant_id = t.id
    AND g.name = CASE WHEN v.type = 'TRUCK' THEN 'Kamyonlar' ELSE 'Otomobiller' END
WHERE t.slug = 'demo';

-- Round-robin assign each vehicle N to driver ((N-1) mod 200) + 1.
UPDATE vehicle v
SET current_driver_id = d.id
FROM driver d
WHERE d.tenant_id = v.tenant_id
  AND d.license_no = 'DRV-' || lpad(((( (right(v.plate, 4))::int - 1) % 200) + 1)::text, 4, '0');

-- ── 1000 devices + SIM cards (one per vehicle) ────────────────────────────
INSERT INTO device (tenant_id, vehicle_id, imei, model, firmware, status, last_seen_at)
SELECT v.tenant_id, v.id,
       lpad((right(v.plate, 4))::text, 15, '0'),
       'Teltonika FMB920', '1.2.3', 'ACTIVE', now()
FROM vehicle v
JOIN tenant t ON t.id = v.tenant_id AND t.slug = 'demo';

INSERT INTO sim_card (tenant_id, device_id, iccid, msisdn, carrier, status)
SELECT d.tenant_id, d.id,
       '8990' || lpad(d.imei::text, 16, '0'),
       '+9053' || lpad(d.imei::text, 8, '0'),
       'Turkcell', 'ACTIVE'
FROM device d;

-- ── One open driver assignment per vehicle ────────────────────────────────
INSERT INTO vehicle_driver_assignment (tenant_id, vehicle_id, driver_id, assigned_at, released_at)
SELECT v.tenant_id, v.id, v.current_driver_id, now() - INTERVAL '30 days', NULL
FROM vehicle v
WHERE v.current_driver_id IS NOT NULL;

-- ── 8 rules ───────────────────────────────────────────────────────────────
INSERT INTO rule (tenant_id, code, name, type, severity, threshold_value, window_seconds, cooldown_seconds, description)
SELECT t.id, r.code, r.name, r.type, r.severity, r.threshold_value, r.window_seconds, r.cooldown_seconds, r.description
FROM tenant t
CROSS JOIN (VALUES
    ('SPEED_LIMIT',        'Hız Limiti',            'SPEED_LIMIT',        'HIGH',     80::numeric,  NULL::int, 300, 'Anlık hız 80 km/s üzeri'),
    ('LOW_BATTERY',        'Düşük Batarya',         'LOW_BATTERY',        'MEDIUM',   20::numeric,  NULL::int, 600, 'Batarya %20 altında'),
    ('LOW_FUEL',           'Düşük Yakıt',           'LOW_FUEL',           'MEDIUM',   15::numeric,  NULL::int, 600, 'Yakıt %15 altında'),
    ('HARSH_BRAKING',      'Sert Fren',             'HARSH_BRAKING',      'HIGH',    -40::numeric,  NULL::int, 120, 'Ardışık ölçümde hız düşüşü 40 km/s üzeri'),
    ('SUSTAINED_SPEEDING', 'Sürekli Hız Aşımı',     'SUSTAINED_SPEEDING', 'HIGH',     80::numeric,  300,       300, '5 dk pencerede olayların %80i 80+ km/s'),
    ('IDLING',             'Rölanti',               'IDLING',             'LOW',      NULL::numeric,600,       900, 'Motor açık, hız 0, 10 dk'),
    ('GEOFENCE_ENTER',     'Yasak Bölge Girişi',    'GEOFENCE_ENTER',     'CRITICAL', NULL::numeric,NULL::int, 300, 'Yasaklı geofence içine giriş'),
    ('GEOFENCE_EXIT',      'Bölge Çıkışı',          'GEOFENCE_EXIT',      'MEDIUM',   NULL::numeric,NULL::int, 300, 'İzinli bölgeden çıkış')
) AS r(code, name, type, severity, threshold_value, window_seconds, cooldown_seconds, description)
WHERE t.slug = 'demo';

-- Tenant-wide assignment for every rule (uses the rule's own threshold).
INSERT INTO rule_assignment (tenant_id, rule_id, scope_type, scope_id, threshold_override)
SELECT r.tenant_id, r.id, 'TENANT', NULL, NULL
FROM rule r
JOIN tenant t ON t.id = r.tenant_id AND t.slug = 'demo';

-- Scoped speed overrides: cars may go up to 110, trucks stay at 80.
INSERT INTO rule_assignment (tenant_id, rule_id, scope_type, scope_id, threshold_override)
SELECT g.tenant_id, r.id, 'GROUP', g.id,
       CASE WHEN g.name = 'Otomobiller' THEN 110 ELSE 80 END
FROM rule r
JOIN tenant t ON t.id = r.tenant_id AND t.slug = 'demo'
JOIN vehicle_group g ON g.tenant_id = t.id AND g.name IN ('Otomobiller', 'Kamyonlar')
WHERE r.code = 'SPEED_LIMIT';

-- ── 5 geofences (Istanbul area, WGS84 lon lat) ────────────────────────────
INSERT INTO geofence (tenant_id, name, kind, area)
SELECT t.id, gf.name, gf.kind,
       ST_GeogFromText('SRID=4326;' || gf.wkt)
FROM tenant t
CROSS JOIN (VALUES
    ('Tarihi Yarımada - Yasak Bölge', 'EXCLUSION',
     'POLYGON((28.955 41.000, 28.985 41.000, 28.985 41.020, 28.955 41.020, 28.955 41.000))'),
    ('Havalimanı Çevresi',            'EXCLUSION',
     'POLYGON((28.800 40.970, 28.850 40.970, 28.850 41.010, 28.800 41.010, 28.800 40.970))'),
    ('Ana Depo Bölgesi',              'INCLUSION',
     'POLYGON((29.080 41.020, 29.120 41.020, 29.120 41.050, 29.080 41.050, 29.080 41.020))'),
    ('Anadolu Yakası Servis Alanı',   'INCLUSION',
     'POLYGON((29.020 40.980, 29.070 40.980, 29.070 41.010, 29.020 41.010, 29.020 40.980))'),
    ('Boğaz Köprüsü Kısıtlı Şerit',   'EXCLUSION',
     'POLYGON((29.030 41.040, 29.045 41.040, 29.045 41.055, 29.030 41.055, 29.030 41.040))')
) AS gf(name, kind, wkt)
WHERE t.slug = 'demo';

INSERT INTO geofence_assignment (tenant_id, geofence_id, scope_type, scope_id)
SELECT gf.tenant_id, gf.id, 'TENANT', NULL
FROM geofence gf
JOIN tenant t ON t.id = gf.tenant_id AND t.slug = 'demo';

-- ── Points of interest ────────────────────────────────────────────────────
INSERT INTO poi (tenant_id, name, category, location)
SELECT t.id, p.name, p.category, ST_GeogFromText('SRID=4326;POINT(' || p.lon || ' ' || p.lat || ')')
FROM tenant t
CROSS JOIN (VALUES
    ('Ana Depo',        'DEPOT',    '29.10', '41.03'),
    ('Bakım Merkezi',   'SERVICE',  '29.05', '40.99'),
    ('Yakıt İstasyonu', 'FUEL',     '28.97', '41.01')
) AS p(name, category, lon, lat)
WHERE t.slug = 'demo';

-- ── Notification templates ────────────────────────────────────────────────
INSERT INTO notification_template (tenant_id, code, channel, rule_code, locale, subject, body)
SELECT t.id, nt.code, nt.channel, nt.rule_code, 'tr', nt.subject, nt.body
FROM tenant t
CROSS JOIN (VALUES
    ('SPEED_WS',   'WEBSOCKET', 'SPEED_LIMIT',   'Hız İhlali',  '{{plate}} aracı {{value}} km/s ile hız limitini aştı.'),
    ('BRAKE_WS',   'WEBSOCKET', 'HARSH_BRAKING', 'Sert Fren',   '{{plate}} aracında sert fren tespit edildi.'),
    ('GEOFENCE_WS','WEBSOCKET', 'GEOFENCE_ENTER','Yasak Bölge', '{{plate}} aracı yasak bölgeye girdi: {{geofence}}.')
) AS nt(code, channel, rule_code, subject, body)
WHERE t.slug = 'demo';

-- Admin opts into live WebSocket notifications for all rules.
INSERT INTO notification_preference (tenant_id, user_id, channel, rule_code, enabled)
SELECT u.tenant_id, u.id, 'WEBSOCKET', NULL, TRUE
FROM app_user u WHERE u.username = 'admin';
