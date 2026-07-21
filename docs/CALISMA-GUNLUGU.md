# Çalışma Günlüğü

Ne yapıldığının değil, **neden öyle yapıldığının** kaydı. Commit mesajları tek tek neyi
değiştirdiğini anlatır; burada bir günün kararları ve o kararların bedeli durur.

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
