-- Bakım planları: tablo V9'dan beri vardı ve BOŞTU.
--
-- Boş olması, "bakımı yaklaşan araç sayısı" işini her gece 0 döndüren bir sayaç haline
-- getirmişti: iş koşuyor, log yazıyor, hiçbir şey ölçmüyor. Bir tabloyu doldurmadan onu
-- okuyan bir işi yazmak, çalıştığını sanmanın en sessiz yolu.
--
-- Artık gerçek kilometre sayacı var: cihaz kanalı Teltonika IO 16'yı (toplam odometre)
-- taşıyor ve `vehicle.odometer_km` onunla besleniyor. Yani km tabanlı bir plan, uydurma
-- bir alan üzerinde değil, aracın bildirdiği sayı üzerinde çalışır.

-- Her kara aracına iki plan: periyodik servis (km) ve muayene (zaman).
-- Helikopterler dışarıda: uçuş bakımı saat esaslıdır, km değil, ve onu km ile modellemek
-- doğru görünen yanlış bir sayı üretirdi.
INSERT INTO maintenance_plan
    (tenant_id, vehicle_id, name, interval_km, last_service_km, next_due_km, enabled)
SELECT v.tenant_id, v.id, 'Periyodik servis', 15000,
       -- Filo koşarken kuruluyor: son servis "şu anki km", sıradaki servis onun 15.000 km
       -- sonrası. Sıfırdan başlatsaydık 100 aracın tamamı ilk gece "bakımı geçmiş" olurdu.
       COALESCE(v.odometer_km, 0),
       COALESCE(v.odometer_km, 0) + 15000,
       true
FROM vehicle v
JOIN vehicle_type t ON t.code = v.type
WHERE t.category = 'LAND';

INSERT INTO maintenance_plan
    (tenant_id, vehicle_id, name, interval_days, last_service_at, next_due_at, enabled)
SELECT v.tenant_id, v.id, 'Muayene', 365,
       now() - INTERVAL '11 months',
       -- Bir ay içinde dolacak şekilde: hatırlatma işinin gerçekten bir şey saydığını
       -- görmek için filonun bir kısmının vadesinin yaklaşıyor olması gerekiyor.
       now() + INTERVAL '1 month',
       true
FROM vehicle v
JOIN vehicle_type t ON t.code = v.type
WHERE t.category = 'LAND';

-- Yaklaşan/geçmiş bakımı olan araçları listeleyen sorgu her ikisini de tarıyor.
CREATE INDEX idx_maintenance_plan_due ON maintenance_plan (vehicle_id)
    WHERE enabled;
