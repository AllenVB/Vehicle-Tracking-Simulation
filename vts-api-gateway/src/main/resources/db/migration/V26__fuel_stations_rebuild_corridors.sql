-- Rebuild the generated stations so they fill the intercity corridors, which is where the
-- vehicles actually drive and where the coverage holes actually were.
--
-- V24 and V25 both chained off "every station's nearest neighbours". That was right the first
-- time, when the only stations were the 162 at province centres — and wrong the second, because
-- by then V24 had added 1,140 more. Every station's nearest neighbours were now a few kilometres
-- away, so V25 packed more pumps into places that already had them and never spanned the long
-- gaps it was written to close. The count tripled to 4,405 while the worst-covered vehicle
-- barely moved: 78 km -> 67 km.
--
-- So: drop the generated ones and derive corridors from the province centres alone, the fixed
-- anchor set, spacing points every ~15 km along each. The 162 seeded centres are kept.

DELETE FROM fuel_station WHERE name LIKE '%Yol Servis%';

INSERT INTO fuel_station (tenant_id, name, brand, location)
SELECT s.tenant_id,
       s.brand || ' Yol Servis',
       s.brand,
       ST_SetSRID(ST_MakePoint(s.plon, s.plat), 4326)::geography
FROM (
    SELECT DISTINCT
           c.tenant_id,
           c.plat,
           c.plon,
           (ARRAY['Shell', 'BP', 'Opet', 'Petrol Ofisi', 'Total', 'Aytemiz'])[
               1 + (abs((c.plat * 1000)::int + (c.plon * 1000)::int) % 6)] AS brand
    FROM (
        SELECT b.tenant_id,
               round((b.lat + (n.lat - b.lat) * f.i / (n.steps + 1.0))::numeric, 2)::double precision AS plat,
               round((b.lon + (n.lon - b.lon) * f.i / (n.steps + 1.0))::numeric, 2)::double precision AS plon
        FROM (
            -- The anchors: only the province-centre stations seeded by V19. Excluding the
            -- generated ones is the whole point — corridors must be measured between cities,
            -- not between pumps that were themselves placed on a corridor.
            SELECT id, tenant_id, location,
                   ST_Y(location::geometry) AS lat,
                   ST_X(location::geometry) AS lon
            FROM fuel_station
            WHERE name NOT LIKE '%Yol Servis%'
        ) b
        CROSS JOIN LATERAL (
            SELECT o.lat, o.lon,
                   -- One point per ~15 km, so a long corridor gets the pumps a short one
                   -- does not need. Capped by the generate_series bound below.
                   greatest(1, floor(ST_Distance(o.location, b.location) / 15000.0)::int) AS steps
            FROM (
                SELECT id, location,
                       ST_Y(location::geometry) AS lat,
                       ST_X(location::geometry) AS lon
                FROM fuel_station
                WHERE name NOT LIKE '%Yol Servis%'
            ) o
            WHERE o.id <> b.id
              AND ST_Distance(o.location, b.location) <= 220000
            ORDER BY o.location <-> b.location
            LIMIT 5
        ) n
        CROSS JOIN generate_series(1, 15) AS f(i)
        WHERE f.i <= n.steps
    ) c
) s
WHERE NOT EXISTS (
    SELECT 1
    FROM fuel_station f
    WHERE ST_DWithin(f.location, ST_SetSRID(ST_MakePoint(s.plon, s.plat), 4326)::geography, 4000)
);
