-- Muayene tarihleri de yayılıyor — km planlarında V35 ile yapılanın aynısı.
--
-- V33 bütün muayeneleri `now() + 1 ay`a kurdu, yani 100 aracın tamamı AYNI GÜN dolacaktı.
-- İki sonucu vardı ve ikisi de kötü: o güne kadar liste bomboş (özellik görünmüyor), o günden
-- sonra liste tamamen kırmızı (öncelik sırası yok). Üstelik 30 günlük "yaklaşan" penceresi
-- tam bir gün ıskalıyordu — özellik doğru çalışıyordu, gösterecek hiçbir şeyi yoktu.
--
-- Gerçek bir filoda muayeneler yıla yayılıdır, çünkü araçlar farklı zamanlarda trafiğe çıkar.
-- Araç kimliğinden türeyen sabit bir gün kaydırması bunu ifade eder: dağılım tekrarlanabilir
-- (aynı veritabanı her kurulumda aynı sonucu verir) ve rastgelelik gerektirmez.
--
-- 365 güne yayılınca herhangi bir 30 günlük pencerede filonun ~%8'i düşer — panelde her
-- zaman birkaç satır olur, ama hepsi olmaz.

-- ::int cast'i zorunlu: vehicle_id BIGINT, dolayısıyla çarpım da bigint oluyor ve
-- make_interval(days => ...) yalnızca integer alıyor. Cast olmadan migration, imzası
-- tutmayan bir fonksiyon çağrısıyla düşüyor.
UPDATE maintenance_plan
   SET next_due_at     = now() + make_interval(days => ((vehicle_id * 61) % interval_days)::int),
       last_service_at = now() + make_interval(days => ((vehicle_id * 61) % interval_days)::int)
                               - make_interval(days => interval_days),
       updated_at      = now()
 WHERE interval_days IS NOT NULL;
