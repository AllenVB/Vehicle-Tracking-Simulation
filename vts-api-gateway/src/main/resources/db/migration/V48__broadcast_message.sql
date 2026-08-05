-- Admin toplu duyurusu (pub/sub). Araç sohbetinden (vehicle_message) tamamen ayrı bir kanal:
-- admin bir "genel mesaj" attığında tüm bağlı panellere WebSocket'te /topic/broadcast üzerinden
-- yayınlanır. Bu tablo, geç bağlanan/yenilenen istemcinin geçmişi görebilmesi için kalıcı kaydı tutar.
-- Belirli bir araca/sürücüye bağlı değildir; bu yüzden vehicle_id yok.
CREATE TABLE broadcast_message (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  BIGINT        NOT NULL REFERENCES tenant (id),
    sender     VARCHAR(80)   NOT NULL DEFAULT 'admin',
    title      VARCHAR(120),
    body       VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Bildirim panelinin "bu tenant'ın son duyuruları" sorgusu için (en yeni önce).
CREATE INDEX idx_broadcast_message_tenant ON broadcast_message (tenant_id, created_at DESC);
