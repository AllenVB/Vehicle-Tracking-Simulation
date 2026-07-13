-- Continuous aggregates. Dashboard/report queries hit THESE, never the raw
-- telemetry hypertable. Creating a continuous aggregate cannot run inside a
-- transaction, so this migration is paired with V12__continuous_aggs.sql.conf
-- (executeInTransaction=false).

-- Per-vehicle 1-minute rollup.
CREATE MATERIALIZED VIEW telemetry_1min
    WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT time_bucket(INTERVAL '1 minute', ts) AS bucket,
       tenant_id,
       vehicle_id,
       avg(speed_kmh)::NUMERIC(6, 2) AS avg_speed_kmh,
       max(speed_kmh)                AS max_speed_kmh,
       min(battery)                  AS min_battery,
       min(fuel_pct)                 AS min_fuel_pct,
       count(*)                      AS sample_count
FROM telemetry
GROUP BY bucket, tenant_id, vehicle_id
WITH NO DATA;

SELECT add_continuous_aggregate_policy('telemetry_1min',
    start_offset      => INTERVAL '3 hours',
    end_offset        => INTERVAL '1 minute',
    schedule_interval => INTERVAL '1 minute');

-- Per-vehicle hourly rollup (built from raw telemetry).
CREATE MATERIALIZED VIEW telemetry_hourly
    WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT time_bucket(INTERVAL '1 hour', ts) AS bucket,
       tenant_id,
       vehicle_id,
       avg(speed_kmh)::NUMERIC(6, 2) AS avg_speed_kmh,
       max(speed_kmh)                AS max_speed_kmh,
       min(battery)                  AS min_battery,
       min(fuel_pct)                 AS min_fuel_pct,
       count(*)                      AS sample_count
FROM telemetry
GROUP BY bucket, tenant_id, vehicle_id
WITH NO DATA;

SELECT add_continuous_aggregate_policy('telemetry_hourly',
    start_offset      => INTERVAL '3 days',
    end_offset        => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour');

-- Daily violation counts by rule and severity for summary dashboards.
CREATE MATERIALIZED VIEW violation_daily_summary
    WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT time_bucket(INTERVAL '1 day', occurred_at) AS bucket,
       tenant_id,
       rule_code,
       severity,
       count(*) AS violation_count
FROM violation
GROUP BY bucket, tenant_id, rule_code, severity
WITH NO DATA;

SELECT add_continuous_aggregate_policy('violation_daily_summary',
    start_offset      => INTERVAL '30 days',
    end_offset        => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour');
