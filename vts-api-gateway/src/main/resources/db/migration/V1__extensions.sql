-- Required Postgres extensions.
-- The timescale/timescaledb-ha:pg16 image ships PostGIS and TimescaleDB and
-- preloads timescaledb + pg_stat_statements via shared_preload_libraries.

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS timescaledb;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
