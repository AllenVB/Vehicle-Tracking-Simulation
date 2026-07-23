-- Bakım gecikmesini bir İHLAL haline getir, ve periyodik aralığı 10.000 km'ye çek.
--
-- Şimdiye kadar bakım yalnızca bir panel satırıydı: operatör görür, görmezse hiçbir şey
-- olmazdı. Oysa bakımı geçmiş bir araç, yakıtı biten ya da hız sınırını aşan bir araçla aynı
-- kategoride — takip edilmesi gereken bir kural ihlali. Artık öyle modelleniyor:
-- scheduler eşiği geçen aracı saptayıp `vehicle.violation`'a bir olay basıyor, o da mevcut
-- ihlal hattından geçip canlı haritaya, listeye ve bildirime düşüyor.
--
-- Bunun için üç şey gerekiyor: kuralın tanımı (rule satırı), tipin CHECK'e eklenmesi, ve
-- planların yeni aralığa göre yeniden tabana oturtulması.

-- ── rule.type CHECK'ini genişlet ────────────────────────────────────────────
-- V5 bu CHECK'i sütun içinde tanımladı, yani Postgres ona <tablo>_<sütun>_check adını verdi.
-- Yeni tipi eklemeden önce eskisini düşürmezsek, iki CHECK birden dayatılır ve eski olan
-- MAINTENANCE_OVERDUE'yu reddeder.
ALTER TABLE rule DROP CONSTRAINT IF EXISTS rule_type_check;
ALTER TABLE rule ADD CONSTRAINT ck_rule_type CHECK (type IN (
    'SPEED_LIMIT', 'LOW_BATTERY', 'LOW_FUEL', 'HARSH_BRAKING', 'SUSTAINED_SPEEDING',
    'IDLING', 'GEOFENCE_ENTER', 'GEOFENCE_EXIT', 'MAINTENANCE_OVERDUE'));

-- ── Kuralın kendisi ─────────────────────────────────────────────────────────
-- violation.rule_id NOT NULL, ve processing ihlali kalıcılaştırırken rule_id'yi rule_code'dan
-- çözüyor. Yani bu satır olmadan scheduler'ın bastığı ihlal yazılamaz.
-- cooldown 24 saat: aynı araç için günde bir kez uyarılır (scheduler de bu pencereyi tarıyor).
INSERT INTO rule (tenant_id, code, name, type, severity, cooldown_seconds, enabled, description)
SELECT t.id, 'MAINTENANCE_OVERDUE', 'Bakım Gecikmesi', 'MAINTENANCE_OVERDUE',
       'MEDIUM', 86400, true, 'Araç periyodik servis kilometresini geçti'
FROM tenant t
WHERE NOT EXISTS (
    SELECT 1 FROM rule r WHERE r.tenant_id = t.id AND r.code = 'MAINTENANCE_OVERDUE');

-- ── Aralık 10.000 km ────────────────────────────────────────────────────────
-- Kullanıcı "her 10 bin km"de istedi. Aralığı değiştiriyor, sonra tabanı GERÇEK odometreye
-- göre yeniden kuruyoruz — V33'ün tuzağı buydu: taban, o an henüz kurgu olan bir sayıya
-- oturtulunca filonun %76'sı bir anda "gecikmiş" göründü. Kaynak yine telemetry'deki cihaz
-- odometresi; temiz kurulumda telemetry boş olur ve COALESCE seed'e düşer.
UPDATE maintenance_plan SET interval_km = 10000 WHERE interval_km IS NOT NULL;

UPDATE maintenance_plan mp
   SET last_service_km = GREATEST(0, base.km - (mp.vehicle_id * 137) % mp.interval_km),
       next_due_km     = GREATEST(0, base.km - (mp.vehicle_id * 137) % mp.interval_km) + mp.interval_km,
       updated_at      = now()
  FROM (
        SELECT v.id AS vehicle_id,
               COALESCE(
                   (SELECT t.odometer_km FROM telemetry t
                     WHERE t.vehicle_id = v.id AND t.odometer_km IS NOT NULL
                     ORDER BY t.ts DESC LIMIT 1),
                   v.odometer_km, 0) AS km
          FROM vehicle v
       ) AS base
 WHERE mp.vehicle_id = base.vehicle_id
   AND mp.interval_km IS NOT NULL;
