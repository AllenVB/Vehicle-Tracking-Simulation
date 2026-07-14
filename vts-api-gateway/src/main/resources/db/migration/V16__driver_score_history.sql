-- Historical driver scores, so the scoreboard has depth the moment the system starts.
-- Going forward the scheduler computes real scores from trips + violations; this only
-- backfills the days before the system existed (up to yesterday, never today).
--
-- Deterministic pseudo-random: md5(driver, day) gives per-day noise, and a per-driver
-- "bias" makes some drivers consistently better than others — otherwise every driver
-- averages the same and a ranking is meaningless.
INSERT INTO driver_score_daily
    (tenant_id, driver_id, score_date, distance_km, harsh_braking_count,
     speeding_count, idling_seconds, violation_count, score)
SELECT r.tenant_id,
       r.driver_id,
       (current_date - r.day)::date,
       80 + (r.noise % 320),                                  -- 80..399 km sürülmüş
       (r.noise % 4),                                          -- sert fren
       (r.noise % 6),                                          -- hız ihlali
       (r.noise % 15) * 60,                                    -- rölanti (sn)
       (r.noise % 4) + (r.noise % 6),                          -- toplam ihlal
       greatest(45, least(99, 98 - r.bias - (r.noise % 10)))   -- 45..99 arası skor
FROM (
    SELECT d.tenant_id,
           d.id AS driver_id,
           g   AS day,
           -- 28 bit: her zaman pozitif, abs() taşma riski yok
           ('x' || substr(md5(d.id::text || ':' || g::text), 1, 7))::bit(28)::int AS noise,
           ('x' || substr(md5('bias:' || d.id::text), 1, 7))::bit(28)::int % 25    AS bias
    FROM driver d
    CROSS JOIN generate_series(1, 30) AS g
) AS r
ON CONFLICT (tenant_id, driver_id, score_date) DO NOTHING;
