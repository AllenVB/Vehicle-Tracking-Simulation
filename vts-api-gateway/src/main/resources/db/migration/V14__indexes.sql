-- Indexes: GIST for spatial predicates, BRIN for append-only time columns,
-- partial indexes for hot filtered lookups, and tenant/foreign-key indexes.

-- ── GIST (geography) ─────────────────────────────────────────────────────
-- Point-in-polygon geofencing and nearest-POI queries. Not added on telemetry:
-- spatial queries there are rare and a GIST index would tax high-rate inserts.
CREATE INDEX ix_geofence_area_gist   ON geofence   USING GIST (area);
CREATE INDEX ix_poi_location_gist    ON poi        USING GIST (location);
CREATE INDEX ix_lastpos_location_gist ON vehicle_last_position USING GIST (location);

-- ── BRIN (time) ──────────────────────────────────────────────────────────
-- Cheap, tiny indexes ideal for naturally time-ordered append-only tables.
CREATE INDEX ix_heartbeat_received_brin ON device_heartbeat USING BRIN (received_at);
CREATE INDEX ix_audit_created_brin      ON audit_log        USING BRIN (created_at);
CREATE INDEX ix_geofence_event_ts_brin  ON geofence_event   USING BRIN (occurred_at);
CREATE INDEX ix_notification_created_brin ON notification    USING BRIN (created_at);
CREATE INDEX ix_fuel_event_ts_brin      ON fuel_event       USING BRIN (ts);
CREATE INDEX ix_trip_point_ts_brin      ON trip_point       USING BRIN (ts);

-- ── Partial indexes (hot paths) ──────────────────────────────────────────
-- Outbox publisher only ever scans PENDING rows, oldest first.
CREATE INDEX ix_outbox_pending ON outbox_event (created_at)
    WHERE status = 'PENDING';
-- The single currently-open driver assignment per vehicle.
CREATE INDEX ix_assignment_open ON vehicle_driver_assignment (vehicle_id)
    WHERE released_at IS NULL;
-- Active devices are the ones the offline-detector cares about.
CREATE INDEX ix_device_active ON device (last_seen_at)
    WHERE status = 'ACTIVE';

-- ── Foreign-key / lookup / temporal indexes ──────────────────────────────
CREATE INDEX ix_vehicle_tenant       ON vehicle (tenant_id);
CREATE INDEX ix_vehicle_group        ON vehicle (group_id);
CREATE INDEX ix_device_vehicle       ON device (vehicle_id);
CREATE INDEX ix_assignment_vehicle   ON vehicle_driver_assignment (vehicle_id, assigned_at DESC);
CREATE INDEX ix_assignment_driver    ON vehicle_driver_assignment (driver_id, assigned_at DESC);
CREATE INDEX ix_violation_vehicle    ON violation (vehicle_id, occurred_at DESC);
CREATE INDEX ix_violation_rule       ON violation (rule_code, occurred_at DESC);
CREATE INDEX ix_violation_tenant     ON violation (tenant_id, occurred_at DESC);
CREATE INDEX ix_geofence_event_vehicle ON geofence_event (vehicle_id, occurred_at DESC);
CREATE INDEX ix_trip_vehicle         ON trip (vehicle_id, started_at DESC);
CREATE INDEX ix_trip_point_trip      ON trip_point (trip_id, seq);
CREATE INDEX ix_notification_user    ON notification (user_id, created_at DESC);
CREATE INDEX ix_rule_assignment_rule ON rule_assignment (rule_id);
CREATE INDEX ix_score_daily_driver   ON driver_score_daily (driver_id, score_date DESC);
