-- Compression and retention policies for the hypertables. Retention window is
-- profile-driven via a Flyway placeholder (dev: 30 days, load: 7 days).

-- Telemetry: columnar compression on old chunks, segmented by vehicle so that
-- per-vehicle scans stay cheap after compression.
ALTER TABLE telemetry SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'vehicle_id',
    timescaledb.compress_orderby   = 'ts DESC'
);
SELECT add_compression_policy('telemetry', INTERVAL '7 days');
SELECT add_retention_policy('telemetry', INTERVAL '${retention_days} days');

-- Violations: compress old chunks; kept for a year regardless of profile since
-- they are far lower volume than telemetry and needed for reporting/audits.
ALTER TABLE violation SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'tenant_id',
    timescaledb.compress_orderby   = 'occurred_at DESC'
);
SELECT add_compression_policy('violation', INTERVAL '30 days');
SELECT add_retention_policy('violation', INTERVAL '365 days');
