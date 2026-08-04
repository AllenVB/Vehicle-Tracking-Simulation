/*
 * Sürücü PWA service worker — yalnızca uygulama kabuğunu (shell) önbelleğe alır.
 * API çağrıları (/api/**) ASLA önbelleğe girmez: telemetri ve giriş her zaman ağdan
 * gider, aksi halde eski konum/oturum geri gelirdi. Kabuk cache'i, kötü bağlantıda
 * sayfanın yine de açılmasını sağlar.
 */
var CACHE = "vts-driver-v1";
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
  if (e.request.method !== "GET" || url.pathname.indexOf("/api/") === 0) return;   // API'yi asla önbellekleme
  e.respondWith(caches.match(e.request).then(function (r) { return r || fetch(e.request); }));
});
