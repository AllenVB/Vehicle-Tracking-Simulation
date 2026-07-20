-- Build the corridors from one anchor per province, connected to the eight provinces a
-- vehicle can actually be sent to.
--
-- V26 still left a 69 km hole between Sivas and Kayseri, and the reason was subtle: V19 seeds
-- TWO stations per province a few hundred metres apart, so "the five nearest anchors" was
-- really only two or three distinct provinces — Sivas reached Tokat, Amasya and Ordu, all
-- northward, and never Kayseri to the south-west. The corridor that needed filling was the one
-- the query could not see.
--
-- Two changes: anchor on one station per province (V19's second station is the one whose name
-- ends in ' 2'), and connect each to its eight nearest neighbours — eight because that is
-- exactly how many candidate destinations the simulator draws from
-- (DESTINATION_CANDIDATES = 8), so the corridors built here are the routes vehicles actually
-- take rather than a guess at them.

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
            SELECT id, tenant_id, location,
                   ST_Y(location::geometry) AS lat,
                   ST_X(location::geometry) AS lon
            FROM fuel_station
            WHERE name NOT LIKE '%Yol Servis%' AND name NOT LIKE '%% 2'
        ) b
        CROSS JOIN LATERAL (
            SELECT o.lat, o.lon,
                   greatest(1, floor(ST_Distance(o.location, b.location) / 15000.0)::int) AS steps
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
            LIMIT 8
        ) n
        CROSS JOIN generate_series(1, 20) AS f(i)
        WHERE f.i <= n.steps
    ) c
) s
WHERE NOT EXISTS (
    SELECT 1
    FROM fuel_station f
    WHERE ST_DWithin(f.location, ST_SetSRID(ST_MakePoint(s.plon, s.plat), 4326)::geography, 4000)
);
