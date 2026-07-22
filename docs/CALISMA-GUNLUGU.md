# Çalışma Günlüğü

Ne yapıldığının değil, **neden öyle yapıldığının** kaydı. Commit mesajları tek tek neyi
değiştirdiğini anlatır; burada bir günün kararları ve o kararların bedeli durur.

---

## 22 Temmuz 2026 (akşam) — dört işlevsel özellik

Kullanıcı "işlevsel bir şeyler ekleyelim" dedi ve dördünü birden istedi. Dördü de kodlandı,
**tek seferde** derlenip deploy edildi — her özellik için ayrı bir imaj derlemesi 35 dakika
sürüyor ve dördü iki buçuk saat ederdi; doğrulamanın çoğu Testcontainers testleriyle
build'den önce yapıldı.

### 1. Cihaza komut — Codec 12

Kanal tek yönlüydü: cihaz konuşuyor, biz dinliyorduk. Codec 12 aynı soketi ters yöne açıyor.
Çerçeve birebir aynı — sıfır önek, uzunluk, veri, CRC — yalnızca veri alanı farklı, bu yüzden
TCP çerçeveleme koduna **hiç dokunulmadı**; codec id'ye bakan üç satırlık bir dallanma yetti.

**Komut Kafka üzerinden yayınlanıyor.** Sebep, tembellik değil topoloji: bir cihazın soketi
tek bir ingestion örneğinde yaşıyor ve dışarıdan hiçbir şey hangisinde olduğunu bilmiyor. Her
örnek kendi grup kimliğiyle her komutu okuyor ve yalnızca tuttuğu cihazınkine davranıyor.
Alternatif, yeniden bağlanmalarda doğru kalması gereken bir servis kaydıydı — yani sürekli
yanlış olabilecek bir şey.

**ACK'ten sonraki en önemli karar, iki başarısızlığı ayırmak oldu.** `TIMEOUT` "cihaz aldı,
cevap vermedi"; `NO_SESSION` "hiçbir örnekte oturum yoktu". Tek bir "başarısız"da toplamak
operatöre yanlış aksiyon aldırırdı: biri cihazla, diğeri kapsamayla ilgili.

**Komutun görünür bir etkisi olması şart koşuldu.** `setdigout 1` yalnızca `DOUT1:1` dönseydi
protokolü gösterirdi, başka bir şeyi değil. Emülatör röleyi kesiyor ve araç bir tick içinde
duruyor. **Ölçülen:** komut `PENDING → SENT → ANSWERED`, araç 7 `0 0 0 0 0 0` km/s'ye düştü,
aynı anda araç 8 `52 52 48 53 54 52` ile sürmeye devam etti; `setdigout 0` sonrası araç 7
`87 88 90 83 89`.

**Tablo yeni değildi.** `device_command` V3'ten beri duruyordu — `command_type`, JSONB
`payload`, `app_user` FK'si — ve hiç kullanılmamıştı. Şema, henüz var olmayan bir yeteneği
tarif ediyordu. Yanına ikinci bir tablo koymak aynı kavramın iki kaydı olurdu; V32 mevcut
olanı Codec 12'nin gerçekten taşıdığı şeye göre evriltti. Bunu ilk yakalayan `FlywayMigrationIT`
oldu (*relation "device_command" already exists*), ikincisini `GatewayContextIT`: JPA entity'si
hâlâ `command_type` bekliyordu ve `ddl-auto=validate` derhal kırmızı yandı. Sabah kurulan
zemin, aynı gün akşam iki kez işe yaradı.

### 2. Haritadan geofence çizme

Bölgeler SQL'e gömülüydü; yeni bir tane eklemek migration yazmak demekti — yani operasyonel
bir araç değil, bir deploy artefaktı. Artık operatör poligonu çiziyor.

Üç küçük karar: poligon **sunucuda** WKT'ye çevriliyor (halkanın kapanması ve sarım yönü
`ST_MakePolygon`'ın umurunda, ayrıca istemcinin gönderdiği metni sorguya sokmak enjeksiyon
demek); silme **`active=false`** (ihlal geçmişi o bölgeye referans veriyor, satırı silmek ya
FK'de patlar ya da açıklanamayan bir olay geçmişi bırakır); ve `GeofenceRegistry` artık
**60 saniyede bir** yenileniyor — "bir kez yükle" ile "her zaman güncel" aynı şeydi, artık
değil.

Çizim eklenti olmadan, elle yazıldı. Gereken şey "tıkla, köşe koy, kapat"tı; bunun için
sayfaya üçüncü bir CDN bağımlılığı sokmak kazandırdığından fazlasını maliyet olarak getirirdi.

### 3. Yolculuk oynatma

Backend zaten vardı: `GET /api/v1/trips/{id}/route`, `trip_point`'ten kırıntıları döndürüyor.
Ama sorgu `tp.ts`'yi **seçiyor ve DTO'ya hiç koymuyordu** — üç yıllık bir sütun, aradaki
haritalamada düşürülmüş. Bu yüzden rota yalnızca statik bir çizgi olarak çizilebiliyordu.

`ts` eklenince oynatma anlamlı oldu: her karede noktanın **kendi saati** gösteriliyor. Sabit
bir sayaç, aracın hiç sürmediği düzgün bir hızı anlatırdı; kırıntılar 30 saniyede bir, yani
aracın beklediği yerde saat atlıyor.

### 4. Bakım — ve günün asıl dersi

Bakım tabloları V9'dan beri boştu ve gecelik hatırlatma işi her gece sıfır sayıyordu. İki
eksik vardı: plan yoktu, ve **kilometre sayacı sahteydi** — `vehicle.odometer_km` V3'ten beri
duruyor, hiçbir şey yazmıyordu. Cihaz kanalı odometreyi zaten taşıyordu; processing artık onu
araç satırına yazıyor, monoton olarak (kapsama boşluğundan dönen bir ölçüm filonun
kilometresini geri sarardı).

Ve sonra plan seed'i patladı. V33 taban çizgisini o anda hâlâ kurgu olan odometreye göre kurdu
(685 km); aynı sürümde odometre gerçeğe atladı (94 570 km). Sonuç: filonun **%76'sı "78 000 km
gecikmiş"**. Hiçbir hata yok, veri tutarlı, anlamı sıfır.

> **Bir sütunu ilk kez doldurmaya başlayan deploy, o sütunun eski değerine dayanan her şeyi
> aynı anda geçersiz kılar.**

İlk düzeltme denemesi (V34) yalnızca yarısını çözdü: yayma adımı çıpayı yine `last_service_km`
almıştı ve o sütun düzeltilmemiş planlarda hâlâ kurguydu — 23 plan 25 000 km "gecikmiş" kaldı.
Yani aynı hatanın daha küçük bir kopyasını yazdım. V35 çıpayı tek doğru şeye bağladı: aracın
**şu anki** kilometresi, `telemetry`den okunarak. V36 aynısını tarihler için yaptı; V33 bütün
muayeneleri aynı güne kurmuştu, üstelik 30 günlük pencereyi **bir gün** ıskalayarak — özellik
doğru çalışıyor, gösterecek hiçbir şeyi yok.

V36 ayrıca `make_interval(days => bigint)` ile düştü (`vehicle_id` BIGINT). Flyway temiz
şekilde geri aldı, gateway açılmadı — yani bu da sessizce geçmedi.

**Ölçülen (düzeltmeden sonra):** gecikmiş 0, kalan mesafe 1 298–14 862 km'ye yayılmış,
muayeneler 2026-07-23 ile 2027-06-07 arasında, "yaklaşan" penceresinde her an 16 plan.

### Puan boşluğu kapandı

Formül yedi türün üçünü sayıyordu. Dördü eklendi; ağırlık sırası bir karar:
bölge ihlali (4.0) > sürekli hız (2.5) > sert fren (5/3) > hız (1.0) > rölanti (2/3) >
yakıt/batarya (1/3).

2.5'in 2.0 yerine seçilmesini **kendi testim dayattı**: puan tam sayıya yuvarlanıyor, yani
birbirine %20'den yakın iki ağırlık her gerçekçi ihlal sayısında aynı sayıya düşüyor.
Çıktının ifade edemediği bir ayrım, ayrım değildir.

**Ölçülen etki:** 30 dakikada kapanan 26 seferin puan dağılımı 1·5, 5·2, 7·1, 8·8, 9·6, 10·4
— ortalama **8.86 → 6.96**. 1 alan seferler gerçekten kötü: 200 km'de 28 sürekli-hız ihlali,
100 km'de 22 sert fren. Artık 10 gerçekten "hiçbir şey ters gitmedi" demek.

### Sağlık taraması

| Ne | Ölçülen |
|---|---|
| Konteyner | 15/15, restart 0, OOM 0 |
| Servis context'i | 7/7 |
| Prometheus | **8/8 up** (sabah 5/8 idi) |
| Cihaz oturumu | 105 |
| Telemetri | 60 sn'de 6300 ölçüm / 105 araç |
| İhlal (10 dk) | SPEED_LIMIT 26, HARSH_BRAKING 16, LOW_FUEL 11, LOW_BATTERY 2 |
| Helikopterde yeni yol ihlali | 0 |
| Trip (30 dk) | 27 kapandı, ortalama puan 6.96 |
| Flyway | 36/36 |
| DLQ | 5 topic + `vehicle.command.dlq`, hepsi 0 |
| Kafka lag | processing 0, gateway 0, notification 0, stream-analytics 1935 |

---

## 22 Temmuz 2026

Üç commit. Konu: testin ve CI'ın hiç olmaması, cihazın gerçekte ne konuştuğu, ikincisinin
birincisi olmadan sistemi **sessizce yanlış** hale getirmesi — ve üç servisin metriğini
üretip okutamaması.

### 1. Test zemini ve CI — `4796efb`

225 ana dosyaya karşı 10 test dosyası vardı, CI yoktu ve **hiçbir test Spring context'i ayağa
kaldırmıyordu**. Bu son cümle diğer ikisinden daha önemliydi.

Geçen hafta üretime iki hata gitti; ikisi de yalnızca elle deploy edilip loglara bakıldığı
için yakalandı. İkisi de derlemeden ve birim testlerden **geçmişti**:

- `vts-common`'a konan ortak error handler `KafkaTemplate<String, Object>` istiyordu.
  Scheduler yalnızca üretici ve `<String, String>` tutuyor. `@ConditionalOnBean` ham tipe
  bakıp geçti, jenerik enjeksiyon uymadı, servis **57 kez** yeniden başlayıp çöktü.
- V22 migration'ı `scope_type` VARCHAR(10) iken 12 karakterlik `VEHICLE_TYPE` yazıyordu.
  Geliştirme veritabanında sütun daha önceki bir denemede zaten genişletilmişti; hata
  **yalnızca temiz bir şemada** görünüyor.

Ortak yanları: bir tanesi bile "bean grafiğini bir kez aç" ya da "migration'ları boş bir
veritabanına uygula" diyen bir testle yakalanırdı. Yazılan tam olarak bu ikisi.

**Migrasyonlar kopyalanmadı.** Şemanın sahibi gateway, ama şemayı okuyan her servisin
testinde ona ihtiyacı var. SQL'i kopyalamak, kopyalanması unutulan ilk migration'da kayardı;
`vts-test-support` onları gateway modülünden **diskten** okuyor. Bu yalnızca bir monorepo'da
mümkün — ki burası öyle.

**Postgres imajı compose'unkiyle aynı** (`timescale/timescaledb-ha:pg16`). Farklı bir
Postgres'e karşı geçen bir context testi, üretimin sorduğundan daha kolay bir soruya cevap
vermiş olurdu: PostGIS ve TimescaleDB burada taşıyıcı, tesadüfi değil.

**Flyway testi bilinçli olarak yeniden kullanılmayan bir konteyner alıyor.** Paylaşılan,
migrasyonlu bir veritabanı "0 migration uygulandı" deyip yeşil yanar ve hiçbir şey kanıtlamaz.

`SchemaValidationTest` silindi. Aynı soruyu soruyordu ama `@EnabledIfSystemProperty` ile
korunuyordu: yalnızca birisi elle veritabanı başlatıp `-Dvts.itest=true` verdiğinde. Yani hiç.

**Kanıt.** İki hata kasten geri konup build çalıştırıldı:

| Geri konan hata | Build çıktısı |
|---|---|
| `KafkaTemplate<?, ?>` → `<String, Object>` | `SchedulerContextIT`: *No qualifying bean of type `KafkaTemplate<String, Object>`* — üretimdeki hatanın aynısı |
| V22'deki `ALTER COLUMN ... VARCHAR(20)` satırı silindi | `FlywayMigrationIT`: *Migration V22 failed — value too long for type character varying(10)*, satır 64 |

Sonra ikisi de geri alındı. **Ölçülen:** `mvn verify` 4 dk 50 sn, tam reactor yeşil.

**GitHub tarafında koşamadı.** Üç push, üç workflow run, üçü de 4–5 saniyede `Failure`.
Sebep kodda değil: *"The job was not started because your account is locked due to a billing
issue."* Yani `ci.yml` GitHub tarafından ayrıştırıldı, iş oluşturuldu, ama runner
zamanlanamadı. Dolayısıyla **"CI yeşil" diyemiyorum**; diyebildiğim, CI'ın çalıştıracağı
komutun (`mvn -B -ntp verify`) yerelde yeşil olduğu ve iki hata geri konduğunda kırmızıya
döndüğü. Faturalandırma çözüldüğünde ilk push bunu GitHub'da da gösterecek.

Bir ortam tuzağı buradan çıktı: Docker Engine 29 API 1.44'ün altını reddediyor, docker-java
hâlâ 1.32 istiyor, el sıkışma çıplak bir HTTP 400 ile düşüyor — ve Testcontainers bunu "no
valid Docker environment" diye raporluyor, yani gerçek sebebin yanından bile geçmiyor. Kök pom
`api.version`'ı sabitliyor.

İkinci tuzak daha öğreticiydi: `mvn -pl vts-scheduler-service verify`, `vts-common`'ı **yerel
depodaki 9 gün eski jar'dan** çözüyordu; auto-configuration hiç yüklenmiyordu ve test yanlış
sebeple kırmızıydı. CI tam reactor'u kuruyor.

### 2. Cihaz protokolü ve olay zamanı — `1115f36`

Sistem dört varsayımla yaşıyordu: cihaz HTTP konuşur, veri JSON'dur, cihaz hep çevrimiçidir,
veri sıralı gelir. Dördü de yanlış; dördü de doğru göründü, çünkü tek kaynak ölçümü yaptığı
anda gönderen bir simülatördü.

**Kodek `vts-common`'a kondu**, çünkü iki modülün aynı tel biçimine ters yönlerden ihtiyacı
var: ingestion çözer, simülatör üretir. İki yerde yazılsaydı birbirinden kayardı ve kayma bir
cihaz hatası gibi görünürdü. Çözücü **Teltonika'nın kendi belgelenmiş örnek paketine** karşı
doğrulanıyor; yalnızca kendi üreticimize karşı test etmek, iki tarafın aynı yanlışta anlaşması
ihtimalini test dışında bırakırdı.

**ACK'in anlamı bir karar.** Cihaz, sunucu onaylayana kadar hiçbir kaydı silmez. ACK
*ayrıştırılan* kayıt sayısını bildiriyor, iş kabulünü değil: bilinmeyen bir araca ait kaydı
"almadım" saymak, cihazı aynı kaydı emekli olana kadar yeniden göndermeye zorlardı. Buna
karşılık CRC'si tutmayan paket **hiç onaylanmıyor** — bozuk bir iletimi almış gibi yapmak
kayıtları temelli kaybettirir.

**Asıl iş buradan sonrası.** Cihaz kanalı tek başına sistemi çalışır hale getirmiyor,
**sessizce yanlış** hale getiriyor. Tamponunu boşaltan bir cihazın iki saatlik geçmişi,
Kafka'nın kayıt zaman damgasıyla "şimdi olmuş" sayılır: yolculuk ortasından kapanır, 09:00
penceresi 11:00 kayıtlarıyla dolar, salı işlenen ihlal çarşambaya yazılır. Hiçbiri hata vermez.

`EventTimeExtractor` akış zamanını olayın kendi anına bağlıyor. Üstüne iki grace:

- **Pencere grace'i 15 dk.** Bedeli açık ve ölçülebilir: bastırılmış pencere tam bu kadar geç
  yayınlanır. 15 dakika, emülatörün 1 saatlik tamponuna göre değil, "bundan uzun bir boşluk
  artık bir kesintidir ve kayıtları uyarıya değil geçmişe aittir" kararına göre seçildi.
- **Trip kapatma grace'i 15 dk.** Akış zamanı bütün araçların ölçümüyle ilerler; kapsama
  boşluğundaki bir cihazın sessizliği park etmiş araçtan **ayırt edilemez**. Punctuator bu
  kadar bekliyor. Not: bu yalnızca punctuator yolunu geciktiriyor — park edip hız 0 göndermeye
  devam eden araç trip'ini yine 90 saniyede kapatıyor.

**Ölçülen fark** (`EventTimeTopologyTest`, aynı girdi iki kez): 10 dk susup tamponunu
boşaltan cihazın yolculuğu, grace ile **tek trip**; grace olmadan `ONGOING → CLOSED →
ONGOING`. Bir sefer ikiye bölünür, mesafe ikiye ayrılır ve iki puan birden yanlış çıkar.

Üç yer daha zamanı geriye almayı reddediyor. `vehicle_last_position`'ın UPSERT'inde zaten
vardı; **canlı harita** ve **konum önbelleğine** eklendi. Aksi halde dönen cihazın markörü bir
saat öncesine sıçrayıp yeniden sürünürdü — bu, ölçümlerin sahte olduğu anlamına gelmez;
yalnızca "şimdiki konum" sorusuna geçmişle cevap verilmiş olurdu.

Continuous aggregate'ler zaten olay zamanıyla (`ts`) kovalanıyordu; eksik olan, geç gelen
satırın kovayı **yeniden hesaplatabileceği** refresh penceresiydi (V31). `materialized_only =
false` bunu kurtarmıyor: gerçek zamanlı toplama, materyalizasyon işaretinden **sonraki** ham
satırları birleştirir; işaretin gerisindeki delik delik kalır.

### Ölçüm — yeniden derlenmiş yığın

| Ne | Ölçülen |
|---|---|
| Konteyner | 15/15 ayakta, restart 0, OOM 0 |
| Servis context'i | 7/7 `Started ...Application` |
| Cihaz oturumu | **105 cihaz** ikili TCP ile bağlı (`tcp/5027`) |
| Telemetri | son 60 sn'de **6195 ölçüm / 105 araç** |
| İhlal (10 dk) | SPEED_LIMIT 26, HARSH_BRAKING 14, LOW_BATTERY 2 |
| Helikopterde yol ihlali | Yeni **0**. Tablodaki 10 kayıt 16 Temmuz'dan, yani V22'den önce |
| Trip | yeniden başlatmadan sonra 9 trip kapandı ve puanlandı |
| Kafka lag | processing 74, gateway 29, notification 0, **stream-analytics 1337** |
| DLQ | 5 topic, hepsi **0** |
| Flyway | 31/31 başarılı |
| Arayüz | Giriş sonrası "Bağlı · canlı", 105 ARAÇ / 105 CANLI; DOM'da 2 Leaflet haritası, 527 markör, 5 rota çizgisi |

**Store-and-forward, ölçülmüş hâliyle:** cihaz 7 on dakika susturuldu.

```
08:31:12  sustur          → tampon 0
08:33:34  tampon 141      → DB'deki son ölçüm 08:31:11'de donmuş, filo akmaya devam ediyor
08:41:12  bağlantı döndü  → 5 paket × 120 kayıt, en eskisi 600 sn geç
08:47:43  toplam          → sessizlik penceresine ait 604 kayıt, kendi zaman damgalarıyla
          vehicle_last_position = 08:47:43 (şimdiki an) — geç yığın konumu geriye çekmedi
          araç 7 için o pencerede kapanan trip: 0 (yolculuk bölünmedi)
```

### 3. Sağlık taramasında çıkan sorun — kapatıldı

**Üç servisin metriği hiç toplanmıyordu.** Prometheus hedeflerinin üçü `down`:
`vts-processing-service:8082`, `vts-stream-analytics:8083`, `vts-scheduler-service:8086`.
Sebep basit — bu üçünde `spring-boot-starter-web` yok, dolayısıyla actuator'ın asacağı bir HTTP
sunucusu da yok. README'nin "Metrikler: `telemetry.persisted`, `violation.produced`…" cümlesi
bu üçü için **hiçbir zaman doğru olmamıştı**.

Gün içinde kapatıldı. Bekleten şey kod değildi — analytics'in belleği 896 MB'ın %85'indeydi ve
oraya bir Tomcat eklemek **ölçmeden** yapılacak bir şey değildi. Ölçüldü.

**Neden `spring-boot-starter-web`.** Alternatif, `management.server.port` ile ayrı bir hafif
sunucuydu; ama servlet konteynerinden kaçılmıyor, yalnızca ikinci bir porta taşınıyordu. Aynı
bedele iki port. Bunun yerine tek yüzey `/actuator/*` bırakıldı ve asıl tasarruf iş havuzundan
alındı: tek istemci 15 saniyede bir gelen Prometheus olduğu için Tomcat havuzu 200'den **5**'e
indirildi. Kullanılmayacak 195 iş parçacığının yığın alanını ayırmanın anlamı yoktu.

**Ölçüm — 10 dakika akış altında, `docker stats`:**

| Servis | Öncesi (2 sa) | Sonrası (10 dk) | Fark |
|---|---|---|---|
| `processing` (değişti) | 344.6 / 512 | 378.8 / 512 — %74 | **+34 MB** |
| `scheduler` (değişti) | 180.6 / 448 | 177.0 / 448 — %40 | fark yok |
| `stream-analytics` (değişti) | 763.5 / 896 — %85 | 629.9 / 896 — %70 | kıyaslanamaz |
| `ingestion` (kontrol) | 307.3 / 448 | 296.9 / 448 | −10 MB |
| `notification` (kontrol) | 241.2 / 448 | 223.2 / 448 | −18 MB |
| `gateway` (kontrol) | 407.8 / 512 | 416.4 / 512 | +9 MB |

**Kontrol servisleri neden tabloda.** İlk ölçüm yeniden başlatmadan hemen sonra alındı ve
`processing` %88 gösterdi — Tomcat'in faturası gibi duruyordu. Ama hiç dokunulmamış
`ingestion` aynı anda %88'deydi. İkisi değişiklikten önce de birbirinin 1.3 puan içindeydi.
Yani o sıçrama Tomcat değil, biriken kuyruğun yakalanmasıydı; 10 dakika sonra ikisi de indi.
Değişmemiş servisler −46 ile +9 MB arasında gezindi, gerçek bedel bu gürültünün üstünde
kalan +34 MB.

**Analytics'in sayısına güvenmedim ve limiti yine de yükselttim.** Öncesi 2 saatlik, sonrası
10 dakikalık bir süreçten; RocksDB durumu uptime ile büyüdüğü için düşük çıkması bir kazanç
değil, yalnızca daha genç bir süreç. 763'ün üstüne ölçülen +34 MB, 896'nın **%89**'u eder.
Sınır 1024 MB'a çıkarıldı — bir rezervasyon değil tavan olduğu için kullanılmadıkça hiçbir
şeye mal olmuyor, ama dar bırakmak bir gece boyunca OOM demek.

**Projeksiyon aynı oturumda doğrulandı.** Yeni sınırla başlayan süreç ~20 dakikada 286 →
**788 MB**'a çıktı. Eski 896 MB sınırına karşı bu %88 eder — yani tahmin edilen %89'un
neredeyse tam üstü. Yükseltme gerekliymiş; 896'da bırakılsaydı gece boyu OOM'a en fazla
bir RocksDB compaction'ı kalırdı. **OOM 0, restart 0.**

Aynı ölçüm sırasında Docker Engine iki kez düştü (yığın imajları derlenirken bir, sonra boşta
bir). Konteyner OOM'u değil, WSL VM'inin kendisi: 16 GB'lık makinede `.wslconfig` yok, WSL
varsayılan olarak yarısını alıyor ve yığın o 7.4 GB'ın ~6.5'ini istiyor. Değişikliğin payı
+34 MB; sebep bu değil, ama zemin bu kadar dar.

### Günün açık bıraktıkları

- **Analytics'in değişiklik sonrası uzun soluklu bellek ölçümü.** 20 dakikada 788 MB'a
  çıktığı görüldü (yeni 1024 sınırının %77'si) ama nerede düzleştiği hâlâ bilinmiyor.
- **stream-analytics lag'i** üç ölçümde 1798 → 1337 → 1532. Büyümüyor, sıfıra da inmiyor.
  Beklenen davranış: Kafka Streams `at_least_once` ile 30 saniyede bir commit ediyor, saniyede
  ~103 ölçümde bu tek başına ~3100'lük bir testere dişi demek. Yani gözlenen aralık bir birikme
  değil, commit aralığının kendisi. Yine de değişiklik öncesi bir taban ölçümüm yok, bu yüzden
  "aynı kaldı" değil "bu seviyede ve büyümüyor" diyebiliyorum.
- Puana girmeyen ihlal türleri (`GEOFENCE_ENTER`, `SUSTAINED_SPEEDING`, `LOW_FUEL`,
  `LOW_BATTERY`) — dünden devrediyor.
- Batch dinleyicide kopan iz zinciri — dünden devrediyor.
- Grace'ten (15 dk) daha geç gelen ölçüm pencereli kuralı kaçırıyor. Veritabanına, trip'e ve
  panolara giriyor; kaybolmuyor. Ama bu bir seçim, kusursuzluk değil.

---

## 21 Temmuz 2026

Beş commit. Konu: yolculuk puanlaması, operatör taşıma davranışı ve dağıtık izleme.

### 1. Taşınan araç rotasını bitirsin, varışta 2 dk beklesin — `a3aff85`

Operatör bir aracı haritada taşıdığında araç artık **eski hedefine devam ediyor**.

Bu, `42be867`'de yapılanın bilinçli olarak geri alınmasıdır. Orada "yeni hedef seç" denmişti,
çünkü Hatay'dan Ankara'ya taşınan araç Kayseri'ye gitmek için güneydoğuya dönüyordu ve
bu "geri gidiyor" gibi görünmüştü. Karar değişti: **görevi iptal etmek, geldiği yöne
dönmekten daha büyük bir sürpriz.** Araç seferini bitirir, park eder, puanlanır, sonra
yeni rota alır. Bedeli açık: taşınan araç bazen geldiği yöne doğru sürecek.

**Varış beklemesi 5.5–12 dk arasından sabit 2 dk'ya indi** — ve buradaki asıl iş, bu
değişikliğin tetiklediği tuzaktı.

`TripRule` bir trip'i, hareketsizlik penceresi dolunca kapatıyor. Pencere 5 dakikaydı.
Bekleme 2 dakikaya inince pencere **hiç dolmaz**: araç önce yola çıkar, trip sonsuza kadar
açık kalır ve `trip`, `trip_point`, `stop_event` ile sürücü skorlaması tamamen boş kalırdı.

Pencere 90 saniyeye indirildi. Bu sayı iki taraftan sınırlıdır:

```
ikmal molası (60 sn)  <  trip penceresi (90 sn)  <  varış beklemesi (120 sn)
```

Alt sınır: depo doldurmak yolculuğu bitirmiş sayılmasın. Üst sınır: araç yola çıkmadan trip
kapansın. **Ölçüm:** yeniden başlatmadan sonraki 12 dakikada 83 trip kapandı — 5 dakikalık
pencereyle hiçbiri kapanmazdı.

Bu arada bir hata bulundu: `startJourney`, `refuelTrip` bayrağını temizlemiyordu. İkmale
giderken rotası değişen araç, pompa olmayan sıradan bir ile varınca deposunu dolduruyordu.

### 2. Yolculuk puanı — `010f157`

Biten her sefer 1–10 arası puan alıyor.

Puan `StreamOutputPersister`'da hesaplanıyor, çünkü trip satırının yazıldığı ve ihlal
tablosunun erişilebildiği tek nokta orası. Trip olayının kendisi kullanılamazdı: stream
topolojisi `violationCount`'u **sabit sıfır** yayıyor — ihlalleri bulan kurallar grafiğin
başka bir yerinde çalışıyor ve trip'i izleyen kısımla hiç buluşmuyor.

Orada saymak, veritabanındaki `violation_count` sütununu da anlamlı kıldı: 66 trip'in 32'si
artık ihlal taşıyor, eskiden hepsi sıfırdı.

**Mesafe tabanı (25 km)** formülün en ince yeri: 2 km'de tek bir sert fren, taban olmadan
100 km başına 250 ceza puanı eder ve skoru 1'e çakardı. Taban, "kısa sefer ne lehte ne
aleyhte delildir" der.

### 3. Puanlama yeniden ayarlandı, gecelik skor kaldırıldı — `835cc7b`

Kullanıcı ihlallerin puana daha sert yansımasını istedi. İstek, ölçülen bir sorunu
doğruluyordu: eski ayarda 100 km'de bir hız ihlali 10 üzerinden **0.3 puan** düşürüyor,
yuvarlanınca 10/10'a geri dönüyordu. **395 yolculuğun 212'si tam on almıştı** ve bir kısmı
ihlalliydi. Herkesin kusursuz göründüğü bir tablo kimseye bir şey anlatmaz.

Yeni oran: **100 km'de bir hız ihlali = 0.5 puan.** Türler birbirine göre hâlâ ağırlıklı
(hız referans, sert fren 5/3, rölanti 2/3) — kütle ve sürüş tarzı açısından sert fren bir
anlık hız aşımından fazlasını söyler.

Puan artık **aşağı** yuvarlanıyor. 9.5'i 10'a yuvarlamak, göstermek için var olduğu ihlali
silerdi. Böylece **10 tek bir şey demek oluyor: hiçbir şey ters gitmedi.**

**Gecelik sürücü skoru tamamen kaldırıldı** (`driver_score_daily`, `driver_score_period`,
iki zamanlanmış iş, iki entity, iki repository). Aynı sürüşü iki farklı ölçekte ölçen iki
puan, tek puandan kötüdür: operatör 9/10 alkışlanan bir seferi görüp ertesi gün aynı sürüş
için sürücünün 62/100 aldığını fark ederse iki sayıya da güvenmez. Sürücünün durumu artık
yolculuklarının ortalaması — okuma anında `trip.score`'dan türüyor.

**Ölçüm:** yeni formülle 10 alan trip'lerin **hepsi ihlalsiz**; ihlali olanlar 9'a düştü.

Ayrıca kullanıcı puanı arayüzde göremediğini bildirmişti ve haklıydı: puan yalnızca araç
**park halindeyken** gösteriliyordu, şehirlerarası seferler saatler sürdüğü için o an
neredeyse hiç yakalanmıyordu (105 aracın 104'ü sürekli yolda). Artık park şartı yok.

### 4. Harita balonuna sürücü puanı — `af8cf50`

Araca tıklanınca açılan balonda sürücü ve puanı görünüyor. Tüm skor listesi tek seferde
çekilip bellekte tutuluyor; sürücü başına istek atmak aynı listeyi 105 kez parça parça
sormak olurdu.

Bu sırada bir hata yakalandı: ucun limit tavanı 100'dü ama filoda **200 sürücü** var —
yarısının balonu, puanı olduğu hâlde "henüz yok" gösterecekti. Tavan 500'e çıkarıldı.

### 5. Dağıtık izleme — `5684740`

Jaeger eklendi (`:16686`), örnekleme %2, izler bellekte ve sınırlı.

**Asıl iş YAML değildi.** Bağımlılıklar ve ayarlar eklendi, her şey "açık" göründü — ama
hiçbir şey izlenmiyordu. Sebep: `spring.kafka.*.observation-enabled` ve Boot'un
`RestClient`'ı yalnızca **Boot'un kendi kurduğu** bean'lere ulaşır; bu projede ise her
`KafkaTemplate`, her listener factory ve her `RestClient` elle kuruluyor. Beş template'e,
iki factory'ye ve iki `RestClient`'a gözlem açıkça set edildi.

**Sonuç, olduğu gibi — hat iki yarım iz hâlinde:**

| Zincir | Durum |
|---|---|
| simulator → ingestion | ✅ Bağlantılı (1 HTTP + 105 Kafka span'i) |
| ingestion → processing | ❌ **Kopuk** |
| processing → gateway | ✅ Bağlantılı |

Kopmanın sebebi `vts-processing`'in `vehicle.telemetry.raw`'ı **batch** tüketmesi: Spring
Kafka'nın gözlem desteği batch dinleyicileri kapsamaz, çünkü farklı izlerden gelen 500
kayıtlık bir yığının tek bir ebeveyni olamaz. Kapatmak için batch dinleyicide her kaydın
başlığından iz bağlamını elle çıkarmak gerekir — sıcak yolda kayıt başına span demek,
ölçmeden yapılacak bir şey değil.

`vts-stream-analytics` hiç iz göndermiyor: Kafka Streams bu enstrümantasyonun kapsamında
değil.

---

### Günün açık bıraktıkları

- **Puana girmeyen ihlal türleri.** Formül hız/sert fren/rölanti sayıyor; `GEOFENCE_ENTER`,
  `SUSTAINED_SPEEDING`, `LOW_FUEL`, `LOW_BATTERY` puana hiç girmiyor. Bu eski gecelik
  formülden miras — ama artık **tek puan** bu olduğu için gerçek bir boşluk: yasak bölgeye
  giren bir araç (CRITICAL) 10/10 alabiliyor.
- **Batch dinleyici iz bağlamı** (yukarıda).
- **CI + Testcontainers.** Hâlâ en değerli açık iş: 228 ana dosyaya karşı 9 test dosyası,
  CI yok, `vts-scheduler-service`'in sıfır testi var ve **hiçbir test Spring context'i
  ayağa kaldırmıyor**. Bu hafta üretilen iki gerçek hata (scheduler'ın 57 kez çökmesi, V22
  migration'ının patlaması) yalnızca elle deploy edilip bakıldığı için yakalandı.

### Ortam notu

Docker Desktop bu makinede kararsız: gün içinde birkaç kez altyapı konteynerleri
(postgres/kafka/redis) **loglarında tek bir hata olmadan** toplu `exit 255` verdi. Bu
uygulama hatası değil, motor altlarından çekiliyor. Çözüm: `docker compose up -d`.
