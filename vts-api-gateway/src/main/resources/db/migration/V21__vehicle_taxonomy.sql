-- Vehicle category and type become first-class, instead of being implied by a CHECK
-- constraint and impersonated by vehicle groups.
--
-- Until now "type" was a bare string and the per-type rule thresholds were hung off
-- vehicle_group rows literally named 'Kamyonlar'/'Otomobiller'/'Motosikletler'. That
-- conflated two different ideas: a group is how a fleet is *organised* (a depot, a
-- customer, a region), while a type is what the vehicle *is*. Anything that needed to
-- reason about type — "do road rules apply?" — had to either guess from a group name or
-- hard-code the type string. Hence a HELICOPTER exemption written twice, differently, in
-- two services.
--
-- Category is the axis that actually decides movement and rule applicability: land
-- vehicles are bound to roads, aircraft are not.

CREATE TABLE vehicle_category (
    code       VARCHAR(20) PRIMARY KEY,
    label      VARCHAR(60) NOT NULL,
    sort_order INTEGER     NOT NULL
);

-- SEA is registered deliberately with no types under it yet: the taxonomy is the place
-- that decides a maritime fleet is expressible, and an empty category is how it says so
-- without any vehicle existing.
INSERT INTO vehicle_category (code, label, sort_order) VALUES
    ('LAND', 'Kara Araçları',  1),
    ('AIR',  'Hava Araçları',  2),
    ('SEA',  'Deniz Araçları', 3);

CREATE TABLE vehicle_type (
    code       VARCHAR(20) PRIMARY KEY,
    category   VARCHAR(20) NOT NULL REFERENCES vehicle_category (code),
    label      VARCHAR(60) NOT NULL,
    sort_order INTEGER     NOT NULL
);

CREATE INDEX idx_vehicle_type_category ON vehicle_type (category);

INSERT INTO vehicle_type (code, category, label, sort_order) VALUES
    ('CAR',        'LAND', 'Otomobil',   1),
    ('TRUCK',      'LAND', 'Tır',        2),
    ('MOTORCYCLE', 'LAND', 'Motor',      3),
    ('HELICOPTER', 'AIR',  'Helikopter', 4);

-- VAN and BUS were allowed by the old CHECK but never seeded and never given rules or
-- icons — they were dead options. The fleet is exactly three land types plus one air
-- type. Fold any stray rows into the nearest surviving type so the FK below can hold.
UPDATE vehicle SET type = 'CAR'   WHERE type = 'VAN';
UPDATE vehicle SET type = 'TRUCK' WHERE type = 'BUS';

-- Replace the CHECK with a real foreign key: the type list now lives in a table that can
-- be read and joined (for labels, categories, applicability) rather than parsed out of a
-- constraint definition.
ALTER TABLE vehicle DROP CONSTRAINT IF EXISTS vehicle_type_check;

DO $$
DECLARE cname text;
BEGIN
    -- V3's original constraint was inline (auto-named); find it by definition, as V17 did.
    SELECT conname INTO cname
    FROM pg_constraint
    WHERE conrelid = 'vehicle'::regclass AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%type%CAR%';
    IF cname IS NOT NULL THEN
        EXECUTE 'ALTER TABLE vehicle DROP CONSTRAINT ' || quote_ident(cname);
    END IF;
END $$;

ALTER TABLE vehicle
    ALTER COLUMN type SET DEFAULT 'CAR',
    ADD CONSTRAINT fk_vehicle_type FOREIGN KEY (type) REFERENCES vehicle_type (code);

CREATE INDEX idx_vehicle_type ON vehicle (type);
