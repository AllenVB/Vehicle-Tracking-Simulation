/*
 * FleetFlow — Sürücü uygulaması (web/PWA).
 *
 * Katman 1a: plaka + model + şifre ile araca giriş, tek-oturum kilidi (X-Device-Session),
 * telefon GPS'ini ≤1/sn gateway'e (/api/v1/track) gönderme. Backend sözleşmeleri native
 * Android app'e birebir taşınabilecek şekilde ayrık tutuldu.
 *
 * Web'in sınırı (bilinçli): tarayıcı, sayfa/uygulama tamamen kapandığında arka planda GPS
 * gönderemez. Ekran açıkken Wake Lock ile uyanık kalır; gerçek "foreground service" native
 * sürümde gelecek. Çevrimdışıyken ölçümler yerelde birikir ve bağlantı gelince gönderilir
 * (1a'da basit kuyruk; 1b'de IndexedDB + toplu gönderim).
 */
(function () {
  "use strict";

  var $ = function (id) { return document.getElementById(id); };
  var MIN_INTERVAL_MS = 1000;
  var QUEUE_KEY = "vts_driver_queue";
  var SESSION_KEY = "vts_driver_session";

  // Telefonu tanımlayan kalıcı kimlik — hangi cihazın araca kilitlendiğini belirler.
  var deviceId = localStorage.getItem("vts_device_key");
  if (!deviceId) {
    deviceId = (crypto.randomUUID ? crypto.randomUUID()
      : "d-" + Date.now() + "-" + Math.random().toString(16).slice(2));
    localStorage.setItem("vts_device_key", deviceId);
  }

  var session = null;        // { sessionToken, imei, plate, model }
  var watchId = null;
  var wakeLock = null;
  var lastSentAt = 0;
  var lastFix = null;        // { lat, lon, t } — kaynak hızı yoksa hız türetmek için
  var sentCount = 0;
  var battery = null;

  // ── QR / derin bağlantı ile plaka+model ön-doldurma ───────────────────────
  var qs = new URLSearchParams(location.search);
  if (qs.get("plate")) $("plate").value = qs.get("plate");
  if (qs.get("model")) $("model").value = qs.get("model");

  // Pil seviyesini (varsa) izle — telemetriye eklenir.
  if (navigator.getBattery) {
    navigator.getBattery().then(function (b) { battery = b; }).catch(function () {});
  }

  // ── Giriş ─────────────────────────────────────────────────────────────────
  $("loginBtn").onclick = doLogin;
  $("password").addEventListener("keydown", function (e) { if (e.key === "Enter") doLogin(); });

  function doLogin() {
    var err = $("loginErr"); err.textContent = "";
    var plate = $("plate").value.trim();
    var model = $("model").value.trim();
    var password = $("password").value;
    if (!plate || !model || !password) { err.textContent = "Plaka, model ve şifre gerekli."; return; }
    $("loginBtn").disabled = true;

    fetch("/api/v1/track/login", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ plate: plate, model: model, password: password, deviceId: deviceId })
    }).then(function (r) {
      if (r.status === 401) throw new Error("Plaka, model veya şifre hatalı.");
      if (r.status === 409) throw new Error("Bu araçta şu an başka bir cihaz aktif. Biraz sonra tekrar dene.");
      if (!r.ok) throw new Error("Giriş başarısız. Bağlantını kontrol et.");
      return r.json();
    }).then(function (d) {
      session = { sessionToken: d.sessionToken, imei: d.imei, plate: d.plate, model: d.model };
      localStorage.setItem(SESSION_KEY, JSON.stringify(session));
      enterTracking();
    }).catch(function (e) {
      err.textContent = e.message || "Giriş başarısız.";
      $("loginBtn").disabled = false;
    });
  }

  // Sayfa yeniden yüklendiğinde geçerli oturumu sürdür (token backend'de hâlâ canlıysa).
  (function resume() {
    var raw = localStorage.getItem(SESSION_KEY);
    if (!raw) return;
    try { session = JSON.parse(raw); } catch (e) { return; }
    if (session && session.sessionToken) enterTracking();
  })();

  // ── Takip ─────────────────────────────────────────────────────────────────
  function enterTracking() {
    $("loginView").classList.add("hidden");
    $("trackView").classList.remove("hidden");
    $("platePill").textContent = session.plate || "";
    $("loginBtn").disabled = false;
    setStatus("wait", "Konum bekleniyor…");
    requestWakeLock();
    startGps();
    flushQueue();
    updateQueued();
  }

  $("stopBtn").onclick = function () {
    fetch("/api/v1/track/logout", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sessionToken: session && session.sessionToken }), keepalive: true
    }).catch(function () {});
    stopGps();
    releaseWakeLock();
    localStorage.removeItem(SESSION_KEY);
    session = null;
    $("trackView").classList.add("hidden");
    $("loginView").classList.remove("hidden");
    $("password").value = "";
  };

  function startGps() {
    if (!navigator.geolocation) { setStatus("off", "Bu cihaz konum desteklemiyor."); return; }
    watchId = navigator.geolocation.watchPosition(onPos, onErr, {
      enableHighAccuracy: true, maximumAge: 1000, timeout: 15000
    });
  }

  function stopGps() {
    if (watchId != null) { navigator.geolocation.clearWatch(watchId); watchId = null; }
  }

  function onErr(e) {
    setStatus("off", e.code === 1 ? "Konum izni verilmedi." : "Konum alınamıyor.");
  }

  function onPos(pos) {
    var c = pos.coords;
    var now = Date.now();
    var speedKmh = speedFrom(c, pos.timestamp);

    $("mSpeed").textContent = speedKmh == null ? "–" : speedKmh;
    $("mAcc").textContent = c.accuracy == null ? "–" : Math.round(c.accuracy);

    if (now - lastSentAt < MIN_INTERVAL_MS) return;   // ≤1/sn kıs
    lastSentAt = now;

    var fix = {
      imei: session.imei,
      ts: new Date(pos.timestamp).toISOString(),
      lat: c.latitude, lon: c.longitude,
      speedKmh: speedKmh,
      heading: (c.heading != null && !isNaN(c.heading)) ? Math.round(c.heading) % 360 : null,
      accuracy: c.accuracy != null ? c.accuracy : null,
      battery: battery ? Math.round(battery.level * 100) : null
    };
    sendFix(fix);
  }

  // Kaynak hızı yoksa (coords.speed null), ardışık iki konumdan türet.
  function speedFrom(c, tsMs) {
    if (c.speed != null && !isNaN(c.speed) && c.speed >= 0) {
      lastFix = { lat: c.latitude, lon: c.longitude, t: tsMs };
      return Math.min(400, Math.round(c.speed * 3.6));
    }
    var v = null;
    if (lastFix) {
      var dt = (tsMs - lastFix.t) / 1000;
      if (dt > 0.3) {
        var d = haversine(lastFix.lat, lastFix.lon, c.latitude, c.longitude);
        v = Math.min(400, Math.round((d / dt) * 3.6));
      }
    }
    lastFix = { lat: c.latitude, lon: c.longitude, t: tsMs };
    return v;
  }

  function haversine(la1, lo1, la2, lo2) {
    var R = 6371000, rad = Math.PI / 180;
    var dLa = (la2 - la1) * rad, dLo = (lo2 - lo1) * rad;
    var a = Math.sin(dLa / 2) * Math.sin(dLa / 2) +
      Math.cos(la1 * rad) * Math.cos(la2 * rad) * Math.sin(dLo / 2) * Math.sin(dLo / 2);
    return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  // ── Gönderim + çevrimdışı kuyruk ──────────────────────────────────────────
  function sendFix(fix) {
    if (!navigator.onLine) { enqueue(fix); return; }
    post(fix).then(function (res) {
      if (res === "taken") return sessionLost();
      sentCount++; $("mSent").textContent = sentCount;
      setStatus("live", "Canlı — konum gönderiliyor");
      flushQueue();
    }).catch(function () {
      enqueue(fix);
      setStatus("warn", "Bağlantı yok — konum yerelde birikiyor");
    });
  }

  // Tek bir ölçümü gönderir. 409 → oturum devralındı ("taken"); diğer hatalar → reject.
  function post(fix) {
    return fetch("/api/v1/track", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Device-Session": session.sessionToken },
      body: JSON.stringify(fix), keepalive: true
    }).then(function (r) {
      if (r.status === 409) return "taken";
      if (!r.ok) throw new Error("HTTP " + r.status);
      return "ok";
    });
  }

  function sessionLost() {
    setStatus("off", "Bu araca başka bir cihaz giriş yaptı.");
    stopGps();
    releaseWakeLock();
    localStorage.removeItem(SESSION_KEY);
    setTimeout(function () {
      $("trackView").classList.add("hidden");
      $("loginView").classList.remove("hidden");
      $("loginErr").textContent = "Oturum başka bir cihazda açıldı. Tekrar giriş yap.";
    }, 2500);
  }

  function enqueue(fix) {
    var q = readQueue(); q.push(fix);
    if (q.length > 5000) q = q.slice(q.length - 5000);   // ~1.5 saat @1sn üst sınır
    localStorage.setItem(QUEUE_KEY, JSON.stringify(q));
    updateQueued();
  }

  function readQueue() {
    try { return JSON.parse(localStorage.getItem(QUEUE_KEY) || "[]"); } catch (e) { return []; }
  }

  // Bağlantı gelince kuyruğu sırayla boşalt (1b'de toplu /batch gönderimine yükseltilecek).
  var flushing = false;
  function flushQueue() {
    if (flushing || !session || !navigator.onLine) return;
    var q = readQueue();
    if (!q.length) return;
    flushing = true;
    var fix = q[0];
    post(fix).then(function (res) {
      flushing = false;
      if (res === "taken") return sessionLost();
      q.shift();
      localStorage.setItem(QUEUE_KEY, JSON.stringify(q));
      sentCount++; $("mSent").textContent = sentCount;
      updateQueued();
      if (q.length) flushQueue();
    }).catch(function () { flushing = false; });
  }

  function updateQueued() { $("mQueued").textContent = readQueue().length; }

  window.addEventListener("online", flushQueue);

  // ── Durum + Wake Lock ─────────────────────────────────────────────────────
  function setStatus(kind, text) {
    var pill = $("statusPill");
    pill.className = "pill " + ({ live: "live", warn: "warnp", off: "offp", wait: "" }[kind] || "");
    $("statusText").textContent = text;
    var hint = kind === "off"
      ? "Sürüşe devam için sorunu giderip tekrar dene."
      : "Ekran açık kaldığı sürece konum gönderilir.";
    $("hint").textContent = hint;
  }

  function requestWakeLock() {
    if (!("wakeLock" in navigator)) return;
    navigator.wakeLock.request("screen").then(function (w) { wakeLock = w; }).catch(function () {});
  }
  function releaseWakeLock() { if (wakeLock) { wakeLock.release().catch(function () {}); wakeLock = null; } }
  document.addEventListener("visibilitychange", function () {
    if (document.visibilityState === "visible" && session && !wakeLock) requestWakeLock();
  });

  // ── PWA service worker ────────────────────────────────────────────────────
  if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register("/sw.js").catch(function () {});
  }
})();
