-- Give the fleet variety: not every car is a Renault Megane. Brand is chosen
-- deterministically from the vehicle's number so it is stable across restarts.
WITH brands(type, idx, make, model) AS (
    VALUES
        ('CAR', 0, 'Renault', 'Clio'),      ('CAR', 1, 'Fiat', 'Egea'),
        ('CAR', 2, 'Toyota', 'Corolla'),    ('CAR', 3, 'Volkswagen', 'Golf'),
        ('CAR', 4, 'Ford', 'Focus'),        ('CAR', 5, 'Hyundai', 'i20'),
        ('CAR', 6, 'Honda', 'Civic'),       ('CAR', 7, 'Peugeot', '301'),
        ('CAR', 8, 'Opel', 'Corsa'),        ('CAR', 9, 'Dacia', 'Sandero'),
        ('TRUCK', 0, 'Mercedes', 'Actros'), ('TRUCK', 1, 'MAN', 'TGX'),
        ('TRUCK', 2, 'Scania', 'R450'),     ('TRUCK', 3, 'Volvo', 'FH'),
        ('TRUCK', 4, 'Ford', 'F-MAX'),      ('TRUCK', 5, 'DAF', 'XF'),
        ('TRUCK', 6, 'Iveco', 'S-Way'),     ('TRUCK', 7, 'Renault', 'T'),
        ('MOTORCYCLE', 0, 'Yamaha', 'MT-07'),   ('MOTORCYCLE', 1, 'Honda', 'CB500'),
        ('MOTORCYCLE', 2, 'Kawasaki', 'Z900'),  ('MOTORCYCLE', 3, 'Suzuki', 'GSX-S'),
        ('MOTORCYCLE', 4, 'BMW', 'R1250 GS'),   ('MOTORCYCLE', 5, 'KTM', 'Duke 390'),
        ('MOTORCYCLE', 6, 'Ducati', 'Monster'),
        ('HELICOPTER', 0, 'Airbus', 'H125'),    ('HELICOPTER', 1, 'Bell', '429'),
        ('HELICOPTER', 2, 'Sikorsky', 'S-76'),  ('HELICOPTER', 3, 'Robinson', 'R44'),
        ('HELICOPTER', 4, 'Leonardo', 'AW139')
),
counts(type, n) AS (
    SELECT type, count(*) FROM brands GROUP BY type
)
UPDATE vehicle v
SET make = b.make, model = b.model
FROM brands b
JOIN counts c ON c.type = b.type
WHERE b.type = v.type
  AND b.idx = (substring(v.vin FROM 4)::int % c.n);

-- Fewer violations: a chronically speeding vehicle now fires at most once per 10
-- minutes (was 5). Combined with the simulator's lower cruise speeds this cuts the
-- speed-violation stream substantially.
UPDATE rule SET cooldown_seconds = 600 WHERE code = 'SPEED_LIMIT';
