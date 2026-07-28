-- Eko-sürüş puanı: yolculuğun yakıt verimini (km başına harcanan yakıt) ve sürüş
-- yumuşaklığını (sert fren / aşırı hız cezası) tek bir 1-100 puanda toplar. İhlal-temelli
-- sürücü puanından (trip.score) AYRI bir eksen: biri "kurallara uydu mu", bu "verimli mi
-- sürdü". NULL, henüz hesaplanmamış (eski) yolculuk demek.
ALTER TABLE trip ADD COLUMN eco_score smallint;

COMMENT ON COLUMN trip.eco_score IS
    'Eko-sürüş puanı 1-100: yakıt verimi (km/%) + sürüş yumuşaklığı. NULL = hesaplanmadı.';
