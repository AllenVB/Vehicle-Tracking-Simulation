-- Five helicopters (numbers 101..105) join the fleet, bringing capacity to 105.
-- They fly, so the road-based rules (speed limit, sustained speeding, harsh braking,
-- geofence) must NOT apply to them — that exemption is enforced by vehicle type in the
-- processing and stream-analytics code. Here we only register them like any vehicle:
-- plate VTS-101-Helikopter.., VIN..0000010N, IMEI 0..0010N (so the simulator's index
-- 101..105 map to them just like every other vehicle).

-- 1) Allow the HELICOPTER type. The original CHECK is an inline (auto-named) constraint,
--    so drop it by lookup rather than by a guessed name.
DO $$
DECLARE cname text;
BEGIN
    SELECT conname INTO cname
    FROM pg_constraint
    WHERE conrelid = 'vehicle'::regclass AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%type%CAR%';
    IF cname IS NOT NULL THEN
        EXECUTE 'ALTER TABLE vehicle DROP CONSTRAINT ' || quote_ident(cname);
    END IF;
END $$;

ALTER TABLE vehicle ADD CONSTRAINT vehicle_type_check
    CHECK (type IN ('CAR', 'VAN', 'TRUCK', 'BUS', 'MOTORCYCLE', 'HELICOPTER'));

-- 2) A group for them.
INSERT INTO vehicle_group (tenant_id, name)
SELECT t.id, 'Helikopterler' FROM tenant t WHERE t.slug = 'demo';

-- 3) The 5 helicopters.
INSERT INTO vehicle (tenant_id, group_id, plate, vin, make, model, year, type, fuel_type, status, odometer_km)
SELECT t.id, g.id,
       'VTS-' || lpad(n::text, 3, '0') || '-Helikopter',
       'VIN' || lpad(n::text, 8, '0'),
       'Airbus', 'H125', 2020 + (n % 5), 'HELICOPTER', 'GASOLINE', 'ACTIVE', (n * 211) % 50000
FROM tenant t
JOIN vehicle_group g ON g.tenant_id = t.id AND g.name = 'Helikopterler'
CROSS JOIN generate_series(101, 105) AS n
WHERE t.slug = 'demo';

-- 4) Driver, device, SIM and open assignment — same derivations as the base seed,
--    keyed off the VIN's numeric part (plate format is irrelevant).
UPDATE vehicle v
SET current_driver_id = d.id
FROM driver d
WHERE v.type = 'HELICOPTER' AND d.tenant_id = v.tenant_id
  AND d.license_no = 'DRV-' || lpad((((substring(v.vin FROM 4)::int - 1) % 200) + 1)::text, 4, '0');

INSERT INTO device (tenant_id, vehicle_id, imei, model, firmware, status, last_seen_at)
SELECT v.tenant_id, v.id, lpad(substring(v.vin FROM 4)::int::text, 15, '0'),
       'Teltonika FMB920', '1.2.3', 'ACTIVE', now()
FROM vehicle v
WHERE v.type = 'HELICOPTER';

INSERT INTO sim_card (tenant_id, device_id, iccid, msisdn, carrier, status)
SELECT d.tenant_id, d.id,
       '8990' || lpad(d.imei::text, 16, '0'),
       '+9053' || lpad(d.imei::text, 8, '0'),
       'Turkcell', 'ACTIVE'
FROM device d
JOIN vehicle v ON v.id = d.vehicle_id
WHERE v.type = 'HELICOPTER';

INSERT INTO vehicle_driver_assignment (tenant_id, vehicle_id, driver_id, assigned_at, released_at)
SELECT v.tenant_id, v.id, v.current_driver_id, now() - INTERVAL '30 days', NULL
FROM vehicle v
WHERE v.type = 'HELICOPTER' AND v.current_driver_id IS NOT NULL;
