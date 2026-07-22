-- Continuous aggregates have to look back as far as data can arrive late.
--
-- The aggregates were already event-time correct in the sense that matters: they bucket by
-- telemetry.ts and violation.occurred_at, which are the device's instants, not arrival time.
-- What was sized for a source that never arrived late is the REFRESH window.
--
-- A refresh policy only re-materialises buckets inside [now - start_offset, now - end_offset].
-- A reading recorded at 09:00 and delivered at 12:30 lands in the 09:00 bucket, and the
-- policy notices — but only while that bucket is still inside the window. Past it, the
-- invalidation is never acted on and the bucket keeps the value it had before the late rows
-- existed. Nothing errors; the number is just quietly low forever.
--
-- 'timescaledb.materialized_only = false' does not save this. Real-time aggregation unions
-- raw rows NEWER than the materialisation watermark; a hole behind the watermark stays a hole.
--
-- Widening is cheap. TimescaleDB tracks invalidated ranges, so a refresh re-materialises only
-- the buckets that actually changed — a wider window costs nothing on the ordinary runs where
-- nothing arrived late, and is the entire difference on the runs where something did.

-- Ihmal edilmemesi gereken sınır: cihaz kanalı 7 güne kadar geriye tarihli kayıt kabul eder
-- (TeltonikaSessionHandler.MAX_BACKDATING). Buradaki pencereler o sınırla hizalı.

-- Dakikalık toplam: bir gün. Bir cihazın tamponu bir saati tutar; gün, kapsama boşluğunu
-- gecelik bir kesintiye kadar genişletme payı bırakır.
SELECT remove_continuous_aggregate_policy('telemetry_1min');
SELECT add_continuous_aggregate_policy('telemetry_1min',
    start_offset      => INTERVAL '1 day',
    end_offset        => INTERVAL '1 minute',
    schedule_interval => INTERVAL '1 minute');

-- Saatlik toplam: yedi gün, ingestion'ın kabul ettiği en eski kayıtla aynı.
SELECT remove_continuous_aggregate_policy('telemetry_hourly');
SELECT add_continuous_aggregate_policy('telemetry_hourly',
    start_offset      => INTERVAL '7 days',
    end_offset        => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour');

-- violation_daily_summary zaten 30 günü tarıyor; ihlaller ölçümün kendi zamanıyla
-- (occurred_at) damgalandığı için geç gelen bir ölçümün ihlali doğru güne yazılıyor.
-- Dokunulmadı.
