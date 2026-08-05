-- Sesli mesaj: ses dosyası volume'da (/data/audio/{audio_ref}) tutulur, DB'de yalnızca
-- referans + içerik tipi. Ses mesaj tablosuna GÖMÜLMEZ (sorgu/aggregate şişmesin, ucuz kalsın).
-- 1 hafta retention (AudioRetentionJob) hem eski satırları hem dosyalarını temizler.
ALTER TABLE vehicle_message ADD COLUMN audio_ref  VARCHAR(64);
ALTER TABLE vehicle_message ADD COLUMN audio_type VARCHAR(40);
