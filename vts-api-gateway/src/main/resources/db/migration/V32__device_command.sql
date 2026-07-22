-- Operatörden cihaza giden komutlar (Teltonika Codec 12).
--
-- Tablo yeni değil: `device_command` V3'ten beri var. Hiç yazılmadı, hiç okunmadı ve hiçbir
-- kod ona dokunmadı — şema, henüz var olmayan bir yeteneği tarif ediyordu. Yanına ikinci bir
-- tablo koymak, aynı kavramın iki kaydı olurdu; bu migration mevcut olanı bugünkü protokole
-- göre evriltiyor.
--
-- Neyin değiştiği ve NEDEN:
--   command_type -> command   Codec 12 bir "tip" değil, ASCII bir komut taşır ("setdigout 1").
--                             40 karakter bir tip için yeterliydi, komut metni için değil.
--   payload                   Düşürüldü: komutun kendisi zaten metin, JSON sarmalayacak bir
--                             yapısı yok. Boş bir JSONB sütunu tutmak, doldurulacakmış gibi
--                             görünen bir yer bırakırdı.
--   issued_by                 app_user FK'sinden kullanıcı adına döndü. Komutu kim verdi
--                             sorusunun cevabı JWT'den geliyor ve o kullanıcı silinse bile
--                             kaydın "kim" bilgisi durmalı.
--   vehicle_id, imei          Eklendi. Cihaz kanalı IMEI ile adresleniyor, operatör araçla
--                             konuşuyor; ikisi de kayıtta olmadan komut geçmişi araca göre
--                             sorgulanamaz.
--   response, sent_at         Eklendi. Cevabın kendisi ve sokete yazılma anı, "cihaz aldı mı"
--                             ile "cihaz cevapladı mı" sorularını ayırmanın tek yolu.
--
-- Durumlar:
--   PENDING     yayınlandı, henüz hiçbir ingestion örneği üstlenmedi
--   SENT        sokete yazıldı, cihazın cevabı bekleniyor
--   ANSWERED    cihaz cevapladı (response dolu)
--   TIMEOUT     yazıldı ama cihaz sustu          -> cihaz aldı, cevap vermedi
--   NO_SESSION  hiçbir örnekte oturum yoktu      -> cihaz çevrimdışı
--   FAILED      soket yazımı başarısız / oturum kapandı
-- TIMEOUT ile NO_SESSION'ı ayırmak bilinçli: operatör için biri "cihaz cevap vermiyor",
-- diğeri "cihaz bağlı değil" demek ve ikisi bambaşka aksiyon gerektirir.

ALTER TABLE device_command RENAME COLUMN command_type TO command;
ALTER TABLE device_command ALTER COLUMN command TYPE VARCHAR(200);
ALTER TABLE device_command DROP COLUMN payload;

-- issued_by app_user'a FK'ydi; sütunu düşürüp adıyla yeniden ekliyoruz. Tablo boş olduğu
-- için veri kaybı yok, ve DROP + ADD tipi değiştirmenin FK'yi de temizleyen tek adımı.
ALTER TABLE device_command DROP COLUMN issued_by;
ALTER TABLE device_command ADD COLUMN issued_by VARCHAR(120);

ALTER TABLE device_command ADD COLUMN vehicle_id BIGINT REFERENCES vehicle (id);
ALTER TABLE device_command ADD COLUMN imei VARCHAR(20);
ALTER TABLE device_command ADD COLUMN response TEXT;
ALTER TABLE device_command ADD COLUMN sent_at TIMESTAMPTZ;

-- device_id, V3'te NOT NULL'dı. Bir cihazı olmayan araca komut gönderilemiyor zaten, ama
-- kaydı tutan yol IMEI; ikisi birlikte NOT NULL kalmalı ki "kime gitti" hep cevaplanabilsin.
ALTER TABLE device_command ALTER COLUMN vehicle_id SET NOT NULL;
ALTER TABLE device_command ALTER COLUMN imei SET NOT NULL;

-- V3 CHECK'i inline tanımlamıştı, yani Postgres <tablo>_<sütun>_check adını verdi.
ALTER TABLE device_command DROP CONSTRAINT IF EXISTS device_command_status_check;
ALTER TABLE device_command ADD CONSTRAINT ck_device_command_status
    CHECK (status IN ('PENDING', 'SENT', 'ANSWERED', 'TIMEOUT', 'NO_SESSION', 'FAILED'));

COMMENT ON COLUMN device_command.command IS
    'Cihaza gönderilen ASCII komut. Serbest metin değil: gateway sabit bir izin listesinden seçtirir.';

-- Operatör paneli araç başına son N komutu çekiyor; sıralama sütunu indeksin parçası olmalı,
-- yoksa her açılışta o aracın tüm komut geçmişi taranır.
CREATE INDEX idx_device_command_vehicle ON device_command (vehicle_id, issued_at DESC);

-- Zaman aşımı taraması yalnızca kapanmamış komutlarla ilgileniyor. Kısmi indeks, tablo
-- büyüdükçe bu taramanın maliyetini sabit tutar: biten komutlar indekste hiç yer almaz.
CREATE INDEX idx_device_command_open ON device_command (status, issued_at)
    WHERE status IN ('PENDING', 'SENT');
