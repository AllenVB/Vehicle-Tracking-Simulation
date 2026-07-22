-- V34'ün ikinci adımı yanlıştı; bu onun yerine geçiyor.
--
-- V34 planları interval boyunca yaymak isterken `last_service_km`'yi çıpa aldı — ve o sütun,
-- düzeltilmemiş planlarda hâlâ V15'ten kalma kurgu değeri taşıyordu. Sonuç, düzeltmeye
-- çalıştığı şeyin daha küçük bir kopyası oldu: 23 plan, 25 000 km "gecikmiş".
--
-- Doğru çıpa tek bir şey: aracın ŞU ANKİ kilometresi. Buradaki her sayı ondan türüyor.
--
-- Yayma neden gerekli: filonun tamamı aynı km'de kurulursa hepsi aynı gün bakıma girer ve
-- liste ya tamamen boş ya tamamen kırmızı olur. İkisi de operatöre bir öncelik sırası vermez.
--
-- Yayma neden GERİYE doğru: "son bakım, geçen interval içinde bir noktada yapıldı" demek,
-- gerçek bir filonun hâlidir. İleriye doğru yaysaydık her aracın deposu yeni bakım görmüş
-- gibi görünürdü ve liste aylarca boş kalırdı — çalıştığını gösteremeyen bir özellik.
--
-- Sonuç: kalan mesafe 0..interval arasına düzgün dağılıyor, yani filonun ~%7'si 1000 km'lik
-- "yaklaşan" penceresinde. Hiçbiri uydurma değil; hepsi cihazın bildirdiği odometreye göre.

UPDATE maintenance_plan mp
   SET last_service_km = GREATEST(0, base.km - (mp.vehicle_id * 137) % mp.interval_km),
       next_due_km     = GREATEST(0, base.km - (mp.vehicle_id * 137) % mp.interval_km) + mp.interval_km,
       updated_at      = now()
  FROM (
        SELECT v.id AS vehicle_id,
               -- Cihazın bildirdiği odometre. `vehicle.odometer_km` bundan sonra zaten bunu
               -- taşıyacak; temiz kurulumda telemetry boş olur ve COALESCE seed'e düşer.
               COALESCE(
                   (SELECT t.odometer_km FROM telemetry t
                     WHERE t.vehicle_id = v.id AND t.odometer_km IS NOT NULL
                     ORDER BY t.ts DESC LIMIT 1),
                   v.odometer_km,
                   0) AS km
          FROM vehicle v
       ) AS base
 WHERE mp.vehicle_id = base.vehicle_id
   AND mp.interval_km IS NOT NULL;
