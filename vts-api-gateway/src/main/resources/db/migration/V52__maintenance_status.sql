-- Bakım planı durumu artık sürücü tarafından işaretlenir. Admin planı tanımlar,
-- aracı kullanan sürücü (mobil) durumunu günceller; admin paneli yalnızca görüntüler.
--   PENDING     → "Bekleniyor" (vadesi geldi, henüz yapılmadı)
--   IN_PROGRESS → "Bakımda"    (sürücü bakıma aldı)
--   DONE        → "Yapıldı"    (sürücü tamamladı; plan bir sonraki döngüye kaydırılır)
ALTER TABLE maintenance_plan
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PENDING';
