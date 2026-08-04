-- Katman 1c — sürücü uygulaması için iki yönlü mesaj kanalı.
--
-- vehicle_message şimdiye dek yalnızca operatör -> araç yönündeydi ve cihaz onu hiç
-- okumuyordu. İki sütun ekleniyor:
--   direction    'TO_DRIVER' (operatörden sürücüye) | 'FROM_DRIVER' (sürücü hazır yanıtı)
--   delivered_at sürücü cihazının mesajı çektiği an (NULL = henüz teslim edilmedi)
-- Sürücü cihazı yalnızca TO_DRIVER + delivered_at IS NULL satırları çeker, TTS ile okur
-- ve çekerken teslim edilmiş işaretler. Sürücünün hazır yanıtları FROM_DRIVER olarak
-- yazılır ve operatörlere yayınlanır.

ALTER TABLE vehicle_message
    ADD COLUMN direction    VARCHAR(12) NOT NULL DEFAULT 'TO_DRIVER',
    ADD COLUMN delivered_at TIMESTAMPTZ;

-- Bu migration'dan ÖNCE yazılmış operatör mesajları cihaz kanalından önce vardı;
-- ilk girişte hepsinin birden sesli okunmaması için teslim edilmiş sayılıyorlar.
-- Yalnızca bundan sonra gönderilen mesajlar sürücü cihazına düşer.
UPDATE vehicle_message SET delivered_at = now() WHERE delivered_at IS NULL;

-- Cihazın "bu araca gelen, henüz teslim edilmemiş mesajlar" sorgusu için.
CREATE INDEX idx_vehicle_message_undelivered
    ON vehicle_message (vehicle_id, direction, delivered_at);
