-- Araçlara gerçek Türk plakası: "06 AFK 1928" formatı.
--
-- Eski plaka "VTS-001-Otomobil" hem tipi taşıyordu hem de araç numarasını (001). Numara
-- arayüzde araç seçmek için kullanılıyordu. Ama numara zaten VIN'de de var (VIN00000001), o
-- yüzden plakayı serbestçe gerçek formata çevirebiliriz — arayüz numarayı VIN'den okuyacak.
--
-- Biçim: "PP LLL NNNN" — il kodu (01-81), 3 harf, 4 rakam.
--
-- Üretim DETERMİNİSTİK: random() yok. Aynı veritabanı her temiz kurulumda aynı plakaları verir
-- ve migration testi tekrarlanabilir olur. Benzersizlik uq_vehicle_plate ile zorunlu; 3 harf
-- araç numarasını 23 tabanında injektif kodladığı için çakışma olmaz (105 < 23^3).
--
-- Harf alfabesi Türk plakasınınkiyle aynı: Q, W, X yok (kullanıcının "W-X olmasın" isteği de
-- bu kümede zaten karşılanıyor). 23 harf: A-Z eksi Q, W, X.

WITH plate_parts AS (
    SELECT v.id,
           -- Araç numarası (VIN'in 4. karakterinden itibaren): VIN00000001 -> 1.
           substring(v.vin FROM 4)::int AS n
    FROM vehicle v
),
built AS (
    SELECT p.id,
           -- İl kodu 01-81, numaradan türetilmiş.
           lpad((((p.n - 1) % 81) + 1)::text, 2, '0') AS province,
           -- 3 harf = n'nin 23 tabanında yazılışı (injektif, yani benzersiz).
           substr(alpha, ((p.n / 529) % 23) + 1, 1) ||
           substr(alpha, ((p.n / 23)  % 23) + 1, 1) ||
           substr(alpha, ( p.n        % 23) + 1, 1) AS letters,
           -- 4 rakam: numaradan türetilmiş, 1000-9999 aralığında.
           lpad((1000 + (p.n * 137) % 9000)::text, 4, '0') AS digits
    FROM plate_parts p
    CROSS JOIN (SELECT 'ABCDEFGHIJKLMNOPRSTUVYZ' AS alpha) a
)
UPDATE vehicle v
   SET plate = b.province || ' ' || b.letters || ' ' || b.digits,
       updated_at = now()
  FROM built b
 WHERE v.id = b.id;
