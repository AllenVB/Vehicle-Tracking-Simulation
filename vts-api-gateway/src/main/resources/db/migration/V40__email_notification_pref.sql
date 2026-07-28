-- Admin, kritik ihlaller icin canli WebSocket bildirimlerine ek olarak e-posta
-- da alir. V15'teki WEBSOCKET tercihinin (satir 187) EMAIL esi: tenant-geneli
-- (rule_code = NULL, tum kurallar). Hangi ihlalin gercekten mail uretecegini
-- notification-service severity esigi (vts.notification.email.min-severity,
-- varsayilan CRITICAL) belirler; tercih yalnizca kanali acar.
-- V15 zaten uygulandigi icin yeni migration sart (Flyway V15'i tekrar kosmaz).
-- uq_pref (user_id, channel, rule_code) benzersizligine uyar.
INSERT INTO notification_preference (tenant_id, user_id, channel, rule_code, enabled)
SELECT u.tenant_id, u.id, 'EMAIL', NULL, TRUE
FROM app_user u
JOIN tenant t ON t.id = u.tenant_id AND t.slug = 'demo'
WHERE u.username = 'admin';
