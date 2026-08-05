-- Duyuru önem derecesi: 'INFO' (bilgi) veya 'URGENT' (acil). Sürücü telefonunda ve admin
-- panelinde banner rengi + acilde farklı titreşim bununla belirlenir. Eski satırlar bilgi sayılır.
ALTER TABLE broadcast_message
    ADD COLUMN severity VARCHAR(10) NOT NULL DEFAULT 'INFO'
        CHECK (severity IN ('INFO', 'URGENT'));
