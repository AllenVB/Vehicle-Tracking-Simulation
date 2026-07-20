-- Cap the fleet's fuel stations at five per province.
--
-- Chasing coverage by adding stations had run to 3,521 of them — 3,359 generated every ~15 km
-- along every corridor. That is not a fleet map any more, it is a carpet: it removes the
-- problem by removing the distances, and a demo where fuel is never more than a few kilometres
-- away shows nothing about how the system behaves when it is.
--
-- So the count is now bounded by design rather than by whatever a spacing rule produced:
-- V19's two stations at each province centre, plus at most three on the corridors leaving it,
-- placed on the THREE LONGEST of the eight routes a vehicle can actually be dispatched along.
-- Longest first because that is where the holes are; the short corridors were already covered
-- by the neighbouring centres.
--
-- Fewer stations means longer worst-case runs to reach one, so the tank has to last longer:
-- the drain rate is lowered to match (see SimulatorProperties.fuelDrainPctPerMinute). The two
-- numbers are a pair — station density and drain rate together decide whether a vehicle that
-- lights up at 25% can still reach a pump, and neither is meaningful alone.

DELETE FROM fuel_station WHERE name LIKE '%Yol Servis%';

INSERT INTO fuel_station (tenant_id, name, brand, location)
SELECT DISTINCT
       c.tenant_id,
       c.brand || ' Yol Servis',
       c.brand,
       ST_SetSRID(ST_MakePoint(c.plon, c.plat), 4326)::geography
FROM (
    SELECT b.tenant_id,
           -- Rounded so the A->B and B->A midpoints collapse to one row under DISTINCT;
           -- without this every corridor would be built twice, once from each end.
           round(((b.lat + n.lat) / 2)::numeric, 2)::double precision AS plat,
           round(((b.lon + n.lon) / 2)::numeric, 2)::double precision AS plon,
           (ARRAY['Shell', 'BP', 'Opet', 'Petrol Ofisi', 'Total', 'Aytemiz'])[
               1 + (abs((b.lat * 1000)::int + (n.lon * 1000)::int) % 6)] AS brand,
           row_number() OVER (PARTITION BY b.id ORDER BY n.dist DESC) AS rn
    FROM (
        -- One anchor per province: V19 seeds two a few hundred metres apart, and treating
        -- both as anchors makes "nearest neighbours" return the same province twice.
        SELECT id, tenant_id, location,
               ST_Y(location::geometry) AS lat,
               ST_X(location::geometry) AS lon
        FROM fuel_station
        WHERE name NOT LIKE '%Yol Servis%' AND name NOT LIKE '%% 2'
    ) b
    CROSS JOIN LATERAL (
        SELECT o.lat, o.lon, ST_Distance(o.location, b.location) AS dist
        FROM (
            SELECT id, location,
                   ST_Y(location::geometry) AS lat,
                   ST_X(location::geometry) AS lon
            FROM fuel_station
            WHERE name NOT LIKE '%Yol Servis%' AND name NOT LIKE '%% 2'
        ) o
        WHERE o.id <> b.id
          AND ST_Distance(o.location, b.location) <= 300000
        ORDER BY o.location <-> b.location
        LIMIT 8                       -- simülatörün hedef havuzu (DESTINATION_CANDIDATES)
    ) n
) c
WHERE c.rn <= 3                       -- her ilin KENDİ koridorlarından en fazla 3
  AND NOT EXISTS (
      SELECT 1
      FROM fuel_station f
      WHERE ST_DWithin(f.location,
                       ST_SetSRID(ST_MakePoint(c.plon, c.plat), 4326)::geography, 10000)
  );

-- Enforce the cap on the finished map, not just on what each province generated.
--
-- Limiting generation to three per province is not the same as five per province: a corridor
-- midpoint belongs to whichever centre it ends up nearest, and a province ringed by close
-- neighbours collects theirs as well as its own. Measured after the insert above, the worst
-- province held ten. So the surplus is trimmed here, where "how many does this province
-- actually have" is finally answerable.
--
-- Kept first: the two seeded centre stations, since a city with no pump in it would be the
-- strangest possible outcome. Then the corridor stations FARTHEST from the centre — those are
-- the ones doing the work, reaching out along the routes; the near ones only duplicate cover
-- the centre already provides.
DELETE FROM fuel_station f
USING (
    SELECT s.id,
           row_number() OVER (
               PARTITION BY s.anchor_id
               ORDER BY s.is_centre DESC, s.km_from_anchor DESC
           ) AS rn
    FROM (
        SELECT f2.id,
               (f2.name NOT LIKE '%Yol Servis%') AS is_centre,
               a.id AS anchor_id,
               ST_Distance(f2.location, a.location) / 1000.0 AS km_from_anchor
        FROM fuel_station f2
        CROSS JOIN LATERAL (
            SELECT a2.id, a2.location
            FROM fuel_station a2
            WHERE a2.name NOT LIKE '%Yol Servis%' AND a2.name NOT LIKE '%% 2'
            ORDER BY a2.location <-> f2.location
            LIMIT 1
        ) a
    ) s
) t
WHERE f.id = t.id AND t.rn > 5;
