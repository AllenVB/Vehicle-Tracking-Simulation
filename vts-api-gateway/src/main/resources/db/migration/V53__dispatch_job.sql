-- Görev atama: admin bir araca hedef (varış noktası) atar; sürücü mobilde görür,
-- durumu günceller; müşteri paylaşım linkinde canlı varış tahmini (ETA) görür.
--   ASSIGNED  → atandı, sürücü henüz yola çıkmadı
--   EN_ROUTE  → sürücü yola çıktı
--   ARRIVED   → varış noktasına ulaştı
--   DONE      → görev tamamlandı (arşiv)
--   CANCELLED → admin iptal etti
CREATE TABLE dispatch_job (
    id         BIGSERIAL    PRIMARY KEY,
    tenant_id  BIGINT       NOT NULL,
    vehicle_id BIGINT       NOT NULL REFERENCES vehicle(id) ON DELETE CASCADE,
    title      TEXT         NOT NULL,
    dest_label TEXT,
    dest_lat   DOUBLE PRECISION NOT NULL,
    dest_lon   DOUBLE PRECISION NOT NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'ASSIGNED',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Bir araç için aynı anda tek aktif görev pratikte yeterli; aktif görevleri hızlı bul.
CREATE INDEX ix_dispatch_active ON dispatch_job(vehicle_id)
    WHERE status IN ('ASSIGNED', 'EN_ROUTE', 'ARRIVED');
