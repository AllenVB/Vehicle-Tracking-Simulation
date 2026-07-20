-- Close the remaining gaps on long corridors.
--
-- V24 put three stations along every corridor regardless of its length. That works where
-- provinces are close together, but in the sparse east a 150 km corridor split three ways
-- still leaves ~37 km between pumps. Measured after V24: median distance fell 23 km -> 9.6 km,
-- yet the worst case was still 66 km and a third of the fleet remained outside the ~18 km that
-- a 25% tank can cover.
--
-- So this places points by SPACING rather than by count: roughly one every 15 km, however long
-- the corridor. Short corridors gain nothing (V24 already covered them and the 4 km proximity
-- guard rejects the rest), long ones gain the stations they were missing.

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
        ) b
        CROSS JOIN LATERAL (
            SELECT o.lat, o.lon,
                   -- One intermediate point per ~15 km of corridor.
                   greatest(1, floor(ST_Distance(o.location, b.location) / 15000.0)::int) AS steps
            FROM (
                SELECT id, location,
                       ST_Y(location::geometry) AS lat,
                       ST_X(location::geometry) AS lon
                FROM fuel_station
            ) o
            WHERE o.id <> b.id
              AND ST_Distance(o.location, b.location) BETWEEN 20000 AND 200000
            ORDER BY o.location <-> b.location
            LIMIT 4
        ) n
        CROSS JOIN generate_series(1, 13) AS f(i)
        WHERE f.i <= n.steps
    ) c
) s
WHERE NOT EXISTS (
    SELECT 1
    FROM fuel_station f
    WHERE ST_DWithin(f.location, ST_SetSRID(ST_MakePoint(s.plon, s.plat), 4326)::geography, 4000)
);
