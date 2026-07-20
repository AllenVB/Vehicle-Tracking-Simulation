-- Fuel stations along the intercity corridors, not just at province centres.
--
-- V19 put two stations per province, both within ~3 km of the city centre. That leaves the
-- roads between provinces empty: measured across the running fleet, the median vehicle was
-- 23 km from its nearest station and the farthest was 78 km. A tank draining 2%/minute gives
-- about 18 km of range once the 25% warning fires, so 58 of 105 vehicles could not reach fuel
-- and simply ran dry mid-route.
--
-- Adding more stations at the same centres would not have helped: the gap is not around the
-- cities, it is on the line between them. So these are placed along those lines — for each
-- existing station, points interpolated towards its nearest few neighbours, which is where
-- the vehicles actually drive.

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
           -- Deterministic brand from the coordinates, so re-running yields the same fleet
           -- of stations rather than a different one each time.
           (ARRAY['Shell', 'BP', 'Opet', 'Petrol Ofisi', 'Total', 'Aytemiz'])[
               1 + (abs((c.plat * 1000)::int + (c.plon * 1000)::int) % 6)] AS brand
    FROM (
        SELECT b.tenant_id,
               -- Snapped to a ~5 km grid so overlapping corridors do not pile stations on
               -- top of each other; DISTINCT then collapses the duplicates.
               round((b.lat + (n.lat - b.lat) * f.i / 4.0)::numeric, 2)::double precision AS plat,
               round((b.lon + (n.lon - b.lon) * f.i / 4.0)::numeric, 2)::double precision AS plon
        FROM (
            SELECT id, tenant_id, location,
                   ST_Y(location::geometry) AS lat,
                   ST_X(location::geometry) AS lon
            FROM fuel_station
        ) b
        -- The nearest few neighbours in a band: closer than 25 km is the same town (no gap to
        -- fill), farther than 150 km is not a corridor this fleet drives in one hop.
        CROSS JOIN LATERAL (
            SELECT o.lat, o.lon
            FROM (
                SELECT id, location,
                       ST_Y(location::geometry) AS lat,
                       ST_X(location::geometry) AS lon
                FROM fuel_station
            ) o
            WHERE o.id <> b.id
              AND ST_Distance(o.location, b.location) BETWEEN 25000 AND 150000
            ORDER BY o.location <-> b.location
            LIMIT 4
        ) n
        CROSS JOIN generate_series(1, 3) AS f(i)
    ) c
) s
-- Never right on top of a station that already exists.
WHERE NOT EXISTS (
    SELECT 1
    FROM fuel_station f
    WHERE ST_DWithin(f.location, ST_SetSRID(ST_MakePoint(s.plon, s.plat), 4326)::geography, 4000)
);
