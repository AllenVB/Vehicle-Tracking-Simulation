-- Sefer öncesi araç kontrolü (DVIR): sürücü mobilde bir kontrol listesi (lastik, fren,
-- far, sıvı, kaporta, korna/ayna) doldurur; herhangi bir madde "kusurlu" ise genel sonuç
-- DEFECT olur ve admin panelinde işaretlenir. items = madde→durum JSON'u.
CREATE TABLE vehicle_inspection (
    id           BIGSERIAL    PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL,
    vehicle_id   BIGINT       NOT NULL REFERENCES vehicle(id) ON DELETE CASCADE,
    inspected_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    overall      VARCHAR(8)   NOT NULL,          -- OK | DEFECT
    items        JSONB        NOT NULL,          -- {"tires":"ok","brakes":"defect",...}
    note         TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_inspection_vehicle ON vehicle_inspection(vehicle_id, inspected_at DESC);
CREATE INDEX ix_inspection_tenant ON vehicle_inspection(tenant_id, inspected_at DESC);
