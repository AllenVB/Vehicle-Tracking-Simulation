-- Rules become type-aware: which rules a vehicle is subject to, and at what threshold,
-- is now data rather than code.
--
-- Before this, "helicopters are exempt from road rules" was written twice and disagreed
-- with itself: the processing service exempted only SPEED_LIMIT (so helicopters still
-- collected LOW_FUEL, LOW_BATTERY and IDLING violations), while the stream topology
-- exempted harsh braking, sustained speeding and geofence but ran idling over everything.
-- Neither list was reachable without a redeploy.
--
-- A new VEHICLE_TYPE scope on rule_assignment carries both facts in one mechanism:
--   enabled = false        -> the rule does not apply to that type at all
--   threshold_override     -> the rule applies, at a type-specific threshold
-- A type with no row keeps the rule's own default, so the table only records difference.

ALTER TABLE rule_assignment ADD COLUMN scope_code VARCHAR(20);

COMMENT ON COLUMN rule_assignment.scope_code IS
    'vehicle_type.code when scope_type = VEHICLE_TYPE; NULL otherwise. Generic (no FK), like scope_id.';

-- scope_type was VARCHAR(10), sized when the longest value was 'VEHICLE' (7).
-- 'VEHICLE_TYPE' is 12, so widen it before anything tries to store one.
ALTER TABLE rule_assignment ALTER COLUMN scope_type TYPE VARCHAR(20);

-- Both existing CHECKs mention scope_type, so they are dropped by name rather than by
-- searching their definitions: a LIKE '%scope_type%TENANT%' lookup matches ck_scope_id
-- too, and SELECT ... INTO would silently take whichever row came first.
--
-- V5 declared scope_type's CHECK inline, so Postgres auto-named it <table>_<column>_check.
ALTER TABLE rule_assignment DROP CONSTRAINT IF EXISTS rule_assignment_scope_type_check;
ALTER TABLE rule_assignment DROP CONSTRAINT IF EXISTS ck_scope_id;

ALTER TABLE rule_assignment ADD CONSTRAINT ck_scope_type
    CHECK (scope_type IN ('TENANT', 'GROUP', 'VEHICLE', 'VEHICLE_TYPE'));

-- Exactly one target column is populated, decided by scope_type.
ALTER TABLE rule_assignment ADD CONSTRAINT ck_scope_target CHECK (
    (scope_type = 'TENANT'       AND scope_id IS NULL     AND scope_code IS NULL) OR
    (scope_type IN ('GROUP', 'VEHICLE')
                                 AND scope_id IS NOT NULL AND scope_code IS NULL) OR
    (scope_type = 'VEHICLE_TYPE' AND scope_id IS NULL     AND scope_code IS NOT NULL)
);

CREATE INDEX idx_rule_assignment_type ON rule_assignment (rule_id, scope_code)
    WHERE scope_type = 'VEHICLE_TYPE';

-- ── Drop the group-scoped speed overrides ────────────────────────────────────
-- These were type overrides wearing a group's clothes (the groups are literally named
-- 'Otomobiller'/'Kamyonlar'/'Motosikletler'). They are re-expressed below against the
-- type itself, which frees vehicle_group to mean what its name says.
DELETE FROM rule_assignment ra
USING rule r, vehicle_group g
WHERE ra.rule_id = r.id
  AND ra.scope_type = 'GROUP'
  AND ra.scope_id = g.id
  AND r.code = 'SPEED_LIMIT'
  AND g.name IN ('Otomobiller', 'Kamyonlar', 'Motosikletler');

-- ── Road rules do not apply to aircraft ──────────────────────────────────────
-- Speed limits, harsh braking, sustained speeding, idling and geofences all describe a
-- vehicle's relationship to a road or a ground zone. A helicopter has none: it cruises
-- at 250 km/h, crosses a restricted zone at altitude, and "idles" only in the sense that
-- it is airborne and stationary. Disabling them here is what makes violations a land
-- concern, in one place, for both the stateless and the stateful engine.
INSERT INTO rule_assignment (tenant_id, rule_id, scope_type, scope_id, scope_code, threshold_override, enabled)
SELECT r.tenant_id, r.id, 'VEHICLE_TYPE', NULL, 'HELICOPTER', NULL, false
FROM rule r
WHERE r.code IN ('SPEED_LIMIT', 'HARSH_BRAKING', 'SUSTAINED_SPEEDING',
                 'IDLING', 'GEOFENCE_ENTER', 'GEOFENCE_EXIT');

-- LOW_FUEL and LOW_BATTERY are deliberately absent from that list: they describe the
-- machine, not the road, and a helicopter running low on fuel is the most urgent case
-- of all. It keeps them, at its own thresholds below.

-- ── Per-type thresholds ──────────────────────────────────────────────────────
-- Speed: unchanged values, now attached to the type rather than to a group.
INSERT INTO rule_assignment (tenant_id, rule_id, scope_type, scope_id, scope_code, threshold_override)
SELECT r.tenant_id, r.id, 'VEHICLE_TYPE', NULL, t.code, t.threshold
FROM rule r
CROSS JOIN (VALUES
    ('CAR', 110::numeric), ('MOTORCYCLE', 90::numeric), ('TRUCK', 80::numeric)
) AS t(code, threshold)
WHERE r.code IN ('SPEED_LIMIT', 'SUSTAINED_SPEEDING');

-- Harsh braking is a deceleration delta (negative km/h between consecutive readings), so
-- a *smaller* magnitude is the stricter setting. Mass decides what counts as harsh: a
-- loaded truck shedding 30 km/h in one sample is violent, whereas a motorcycle does 50
-- routinely and safely. One shared -40 flagged trucks late and motorcycles constantly.
INSERT INTO rule_assignment (tenant_id, rule_id, scope_type, scope_id, scope_code, threshold_override)
SELECT r.tenant_id, r.id, 'VEHICLE_TYPE', NULL, t.code, t.threshold
FROM rule r
CROSS JOIN (VALUES
    ('TRUCK', -30::numeric), ('CAR', -40::numeric), ('MOTORCYCLE', -50::numeric)
) AS t(code, threshold)
WHERE r.code = 'HARSH_BRAKING';

-- Low fuel is a percentage, so the useful threshold tracks how much range a percent buys
-- and how bad running dry is. A truck's 20% is still hundreds of km; a motorcycle's tank
-- is small enough that 10% is a normal reserve; a helicopter cannot coast to a station.
INSERT INTO rule_assignment (tenant_id, rule_id, scope_type, scope_id, scope_code, threshold_override)
SELECT r.tenant_id, r.id, 'VEHICLE_TYPE', NULL, t.code, t.threshold
FROM rule r
CROSS JOIN (VALUES
    ('TRUCK', 20::numeric), ('CAR', 15::numeric),
    ('MOTORCYCLE', 10::numeric), ('HELICOPTER', 25::numeric)
) AS t(code, threshold)
WHERE r.code = 'LOW_FUEL';
