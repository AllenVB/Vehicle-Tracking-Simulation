-- Admin'in araca atadığı sürücü şifresini panelde görüntüleyebilmek için düz metni de sakla.
-- Bunlar kullanıcı sırrı değil, adminin araca verdiği ve sürücüye ilettiği ERİŞİM KODUDUR;
-- admin görebilmeli. Giriş doğrulaması yine password_hash (bcrypt) ile yapılır — düz metin
-- yalnızca panelde göstermek/paylaşmak için.
ALTER TABLE driver_login ADD COLUMN password_plain VARCHAR(72);
