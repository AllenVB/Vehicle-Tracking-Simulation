/*
 * Sürücü PWA service worker — yalnızca uygulama kabuğunu (shell) önbelleğe alır.
 * API çağrıları (/api/**) ASLA önbelleğe girmez: telemetri ve giriş her zaman ağdan
 * gider, aksi halde eski konum/oturum geri gelirdi.
 *
 * Strateji: kabuk için AĞ ÖNCELİKLİ (network-first). Eskiden cache-first'tü ve sabit
 * cache adıyla; bu yüzden telefon uygulamayı bir kez açtıktan sonra driver.html/driver.js
 * güncellemeleri ASLA gelmiyordu (bayat kabuk). Şimdi: online'ken en güncel kabuk hemen
 * gelir ve cache tazelenir; yalnızca çevrimdışıyken cache'teki son kopyaya düşülür.
 */
var CACHE = "vts-driver-v2";
var SHELL = ["/driver.html", "/driver.js", "/driver-icon.svg", "/manifest.webmanifest"];

self.addEventListener("install", function (e) {
  e.waitUntil(caches.open(CACHE).then(function (c) { return c.addAll(SHELL); }).then(function () {
    return self.skipWaiting();
  }));
});

self.addEventListener("activate", function (e) {
  e.waitUntil(caches.keys().then(function (keys) {
    return Promise.all(keys.filter(function (k) { return k !== CACHE; }).map(function (k) { return caches.delete(k); }));
  }).then(function () { return self.clients.claim(); }));
});

self.addEventListener("fetch", function (e) {
  var url = new URL(e.request.url);
  if (e.request.method !== "GET") return;
  if (url.origin !== self.location.origin) return;   // CDN/harita karosu: tarayıcıya bırak
  if (url.pathname.indexOf("/api/") === 0) return;    // API asla önbelleğe girmez

  // Ağ öncelikli: taze kabuğu getir + cache'i tazele; ağ yoksa cache'e düş.
  e.respondWith(
    fetch(e.request).then(function (r) {
      if (r && r.ok) {
        var copy = r.clone();
        caches.open(CACHE).then(function (c) { c.put(e.request, copy); });
      }
      return r;
    }).catch(function () { return caches.match(e.request); })
  );
});
