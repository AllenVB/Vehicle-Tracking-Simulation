-- V42 (demo filo temizligi) rule + rule_assignment satirlarini da silmisti. Kural olmadan
-- kural motoru (processing + stream-analytics) hicbir esigi degerlendiremiyor ve HIC ihlal
-- uretmiyordu (SPEED_LIMIT / HARSH_BRAKING / IDLING / GEOFENCE ...). Bu, "ihlaller gorunmuyor"
-- probleminin tam koku. Aktif tenant (demo) icin standart 8 kurali geri yukler. Idempotent:
-- var olan (tenant_id, code) cakisirsa dokunmaz.
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
WHERE t.slug = 'demo'
ON CONFLICT (tenant_id, code) DO NOTHING;

-- Tenant geneli atama (kuralin kendi esigini kullanir). Yalnizca eksikse ekle.
INSERT INTO rule_assignment (tenant_id, rule_id, scope_type, scope_id, threshold_override)
SELECT r.tenant_id, r.id, 'TENANT', NULL, NULL
FROM rule r
JOIN tenant t ON t.id = r.tenant_id AND t.slug = 'demo'
WHERE NOT EXISTS (
    SELECT 1 FROM rule_assignment a WHERE a.rule_id = r.id AND a.scope_type = 'TENANT'
);
