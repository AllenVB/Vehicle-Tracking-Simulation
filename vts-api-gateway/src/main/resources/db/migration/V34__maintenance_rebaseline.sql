-- V33'ün taban çizgisi yanlış bir sayıya dayanıyordu. Bu onu düzeltiyor.
--
-- Ne oldu: V33, km tabanlı planları `vehicle.odometer_km` üzerine kurdu. O sütun V15'ten beri
-- vardı ve HİÇBİR ŞEY onu yazmıyordu — yani içindeki sayı kurulum anından kalma bir kurguydu
-- (685, 2603, 4110 gibi). Aynı deploy'da processing servisi odometreyi cihazın kendi
-- sayacından yazmaya başladı ve sütun bir anda gerçeğe atladı (94 570). Sonuç: planların
-- %76'sı "78 000 km gecikmiş" göründü. Veri tutarlıydı, anlamı yoktu.
--
-- Ders, migration'ın kendisinden daha genel: bir sütunu ilk kez doldurmaya başlayan deploy,
-- o sütunun eski değerine dayanan her şeyi aynı anda geçersiz kılar. V33 ile odometre yazımı
-- aynı sürümde gitti ve biri diğerinin altını oydu.
--
-- Düzeltme neden `telemetry`den okuyor: cihazın bildirdiği odometre zaten hypertable'da ve
-- `vehicle.odometer_km`'nin BUNDAN SONRA taşıyacağı değer o. Yani burada tahmin yok, aynı
-- kaynağa gidiliyor. Temiz bir kurulumda `telemetry` boş olur, COALESCE seed değerine düşer
-- ve bu migration hiçbir şeyi değiştirmez — yani hem bu kurulum hem sıfırdan kurulum için
-- doğru.

UPDATE maintenance_plan mp
   SET last_service_km = base.km,
       next_due_km     = base.km + mp.interval_km,
       updated_at      = now()
  FROM (
        SELECT v.id AS vehicle_id,
               COALESCE(
                   (SELECT t.odometer_km FROM telemetry t
                     WHERE t.vehicle_id = v.id AND t.odometer_km IS NOT NULL
                     ORDER BY t.ts DESC LIMIT 1),
                   v.odometer_km,
                   0) AS km
          FROM vehicle v
       ) AS base
 WHERE mp.vehicle_id = base.vehicle_id
   AND mp.interval_km IS NOT NULL
   -- Yalnızca taban çizgisi bozulmuş planlar. Gerçekten bakımı yaklaşan bir aracı da
   -- sıfırlamak, düzeltmenin asıl işi görmesini engellerdi: bir interval'dan FAZLA gecikme
   -- bakım gecikmesi değil, taban hatasıdır.
   AND base.km > mp.next_due_km + mp.interval_km;

-- Filo aynı anda bakıma girmesin diye planlar interval boyunca yayılıyor. Hepsi aynı km'de
-- kurulursa hepsi aynı gün dolar ve liste ya tamamen boş ya tamamen kırmızı olur; ikisi de
-- operatöre bir öncelik sırası vermez.
UPDATE maintenance_plan mp
   SET next_due_km = mp.last_service_km + 1 + (mp.vehicle_id * 137 % mp.interval_km),
       updated_at  = now()
 WHERE mp.interval_km IS NOT NULL
   AND mp.next_due_km = mp.last_service_km + mp.interval_km;
