-- Plakaları boşluksuz + büyük harfe normalize et. Bundan sonra create/update de plakayı
-- normalize eder (Plates.normalize) ve giriş boşluk-duyarsız eşleşir; böylece '06 ANK 06',
-- '06ANK06', '06 ANK06', '06ANK 06' aynı araç sayılır ve uq_vehicle_plate uniqueliği
-- boşluk varyantı kopya oluşturulmasını engeller. (Çakışma kontrol edildi: yok.)
UPDATE vehicle SET plate = upper(replace(plate, ' ', '')) WHERE plate <> upper(replace(plate, ' ', ''));
