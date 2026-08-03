-- Demo/sentetik verinin tamamen kaldırılması.
--
-- Sistem tek gerçek aracı (telefon-GPS, V41) ve QR ile enroll edilen gerçek
-- telefon araçlarını izliyor. Simülatör + İETT feed kaldırıldı; onların ürettiği
-- demo filo (100 araç + 5 helikopter + 200 sürücü) ve demo politika verisi
-- (kurallar, geofence'ler, yakıt istasyonları, POI'ler) artık gereksiz.
--
-- Ayırt edici anahtarlar (asla gerçek/enrolled aracı silmez):
--   * Demo araç : vehicle.vin LIKE 'VIN%'   (VIN00000001 … VIN00000105)
--   * Demo sürücü: driver.license_no LIKE 'DRV-%'  (DRV-0001 … DRV-0200)
--   * Gerçek araç (V41): vin = 'PHONEGPS00000001'        → korunur
--   * Enrolled araç    : vin = 'PHONE-' || imei          → korunur
--
-- FK-güvenli sıra: önce çocuk/olay tabloları, sonra araç/sürücü, en son politika.
-- (violation ve telemetry TimescaleDB hypertable'ı — FK yok, ama temizlik için siliyoruz.)

-- ── Demo araca/sürücüye bağlı olay & telemetri verisi ─────────────────────
DELETE FROM notification
 WHERE vehicle_id IN (SELECT id FROM vehicle WHERE vin LIKE 'VIN%')
    OR driver_id  IN (SELECT id FROM driver  WHERE license_no LIKE 'DRV-%');

-- Tüm geofence'ler kaldırılacağı için tüm geofence olaylarını temizle
-- (araç/sürücü/geofence'e FK'li — silmeden araç/sürücü/geofence silinemez).
DELETE FROM geofence_event;

DELETE FROM stop_event        WHERE vehicle_id IN (SELECT id FROM vehicle WHERE vin LIKE 'VIN%');
DELETE FROM trip              WHERE vehicle_id IN (SELECT id FROM vehicle WHERE vin LIKE 'VIN%');
DELETE FROM fuel_event        WHERE vehicle_id IN (SELECT id FROM vehicle WHERE vin LIKE 'VIN%');
DELETE FROM maintenance_record WHERE vehicle_id IN (SELECT id FROM vehicle WHERE vin LIKE 'VIN%');
DELETE FROM maintenance_plan  WHERE vehicle_id IN (SELECT id FROM vehicle WHERE vin LIKE 'VIN%');
DELETE FROM vehicle_message   WHERE vehicle_id IN (SELECT id FROM vehicle WHERE vin LIKE 'VIN%');
DELETE FROM device_command    WHERE vehicle_id IN (SELECT id FROM vehicle WHERE vin LIKE 'VIN%');
DELETE FROM vehicle_last_position WHERE vehicle_id IN (SELECT id FROM vehicle WHERE vin LIKE 'VIN%');

-- telemetry ve violation TimescaleDB SIKIŞTIRILMIŞ hypertable'larıdır. Onlardan satır-bazlı
-- DELETE, sıkıştırılmış chunk'ları açmaya çalışıp "tuple decompression limit exceeded" verir.
--   * telemetry: demo araçların satırları BIRAKILIR — silinen araca FK'siz bağlıdır, hiçbir
--     uç noktadan görünmez (sorgular vehicle JOIN'liyor) ve compression/retention ile yaşlanır.
--   * violation: tüm kurallar kaldırıldığından yeni ihlal üretilmeyecek; mevcut ihlallerin
--     tamamı TRUNCATE ile temizlenir — hypertable chunk-drop, decompression YOK, anında.
TRUNCATE TABLE violation, violation_ack;

-- Sürücü atamaları
DELETE FROM vehicle_driver_assignment WHERE vehicle_id IN (SELECT id FROM vehicle WHERE vin LIKE 'VIN%');

-- ── Cihazlar (demo araçlara ait) ──────────────────────────────────────────
DELETE FROM sim_card
 WHERE device_id IN (SELECT id FROM device WHERE vehicle_id IN (SELECT id FROM vehicle WHERE vin LIKE 'VIN%'));
DELETE FROM device_heartbeat
 WHERE device_id IN (SELECT id FROM device WHERE vehicle_id IN (SELECT id FROM vehicle WHERE vin LIKE 'VIN%'));
DELETE FROM device
 WHERE vehicle_id IN (SELECT id FROM vehicle WHERE vin LIKE 'VIN%');

-- ── Demo araçlar (100 kara aracı + 5 helikopter) ──────────────────────────
DELETE FROM vehicle WHERE vin LIKE 'VIN%';

-- ── Demo sürücüler (200) ───────────────────────────────────────────────────
-- Not: driver_score_daily/period tabloları V30'da düşürüldü (skor artık trip.score/
-- eco_score sütunlarında), o yüzden ayrıca skor tablosu temizliğine gerek yok.
DELETE FROM driver WHERE license_no LIKE 'DRV-%';

-- ── Demo araç grupları ────────────────────────────────────────────────────
DELETE FROM vehicle_group WHERE name IN ('Kamyonlar', 'Otomobiller', 'Motosikletler', 'Helikopterler');

-- ── Politika/harita demo verisi (tamamen temizlik) ────────────────────────
-- rule_assignment ve geofence_assignment ana kayda ON DELETE CASCADE'lidir.
DELETE FROM rule;          -- tüm kurallar (SPEED_LIMIT, HARSH_BRAKING, geofence kuralları, bakım kuralı…)
DELETE FROM geofence;      -- tüm geofence'ler
DELETE FROM fuel_station;  -- tüm yakıt istasyonları
DELETE FROM poi;           -- ilgi noktaları
