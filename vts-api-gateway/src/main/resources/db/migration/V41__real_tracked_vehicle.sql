-- Bir GERÇEK takip aracı: tarayıcı içi telefon GPS cihazı.
--
-- Sentetik kaynaklar (simulator + İETT feed) artık varsayılan çalıştırmada kapalı;
-- sistem tek bir gerçek aracı izliyor. Bu aracın konumu, HIZI ve GPS doğruluğu
-- (accuracy) telefonun Geolocation API'sinden gerçek zamanlı gelir — türetilmiş
-- değil, cihazın kendi ölçümü. Plaka bir yer tutucudur (34 GPS 001); kullanıcı
-- kendi aracının gerçek plakasını UI'dan (PUT /api/v1/vehicles/{id}) girebilir.
--
-- device.imei = '990000000000001' → tracker sayfasının varsayılan IMEI'si.
-- CachedVehicleLookupAdapter bu IMEI'yi bu araca çözer; başka türlü telemetri
-- UNKNOWN_IMEI olarak dead-letter'a düşerdi.

WITH t AS (
    SELECT id AS tenant_id FROM tenant ORDER BY id LIMIT 1
),
ins_v AS (
    INSERT INTO vehicle (tenant_id, plate, vin, make, model, year, type, fuel_type, status, odometer_km)
    SELECT t.tenant_id, '34 GPS 001', 'PHONEGPS00000001', 'Telefon', 'Canlı GPS',
           2024, 'CAR', 'GASOLINE', 'ACTIVE', 0
    FROM t
    RETURNING id, tenant_id
)
INSERT INTO device (tenant_id, vehicle_id, imei, model, firmware, status)
SELECT ins_v.tenant_id, ins_v.id, '990000000000001', 'Phone Browser GPS', 'geolocation', 'ACTIVE'
FROM ins_v;
