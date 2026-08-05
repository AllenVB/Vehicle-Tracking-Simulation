/*
 * FleetFlow — Sürücü uygulaması (web/PWA).
 *
 * Katman 1a: plaka + model + şifre ile araca giriş, tek-oturum kilidi (X-Device-Session),
 * telefon GPS'ini ≤1/sn gateway'e (/api/v1/track) gönderme. Backend sözleşmeleri native
 * Android app'e birebir taşınabilecek şekilde ayrık tutuldu.
 *
 * Web'in sınırı (bilinçli): tarayıcı, sayfa/uygulama tamamen kapandığında arka planda GPS
 * gönderemez. Ekran açıkken Wake Lock ile uyanık kalır; gerçek "foreground service" native
 * sürümde gelecek. Çevrimdışıyken ölçümler dayanıklı IndexedDB'de birikir (tarayıcı kapansa
 * da kalır) ve bağlantı gelince paket halinde /api/v1/track/batch'e boşaltılır (Store & Forward).
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

  // 1d — akıllı hız limiti (client): Overpass'tan yol limiti, hücre önbelleği, tolerans.
  var SPEED_CACHE_KEY = "vts_speedcache";
  var OVERPASS_MIN_MS = 12000;    // Overpass'a en sık istek aralığı (maliyet/rate-limit)
  var OVER_TOLERANCE_MS = 15000;  // kesintisiz aşım süresi — anlık ceza yok
  var SPEED_NOISE = 3;            // km/s GPS gürültü payı
  var speedLimit = null;          // bilinen limit (km/s) veya null
  var overStart = 0, overWarned = false, lastOverpassAt = 0;
  var speedCache = loadSpeedCache();

  // ── QR / derin bağlantı ile plaka+model ön-doldurma ───────────────────────
  var qs = new URLSearchParams(location.search);
  if (qs.get("plate")) $("plate").value = qs.get("plate");

  // Pil seviyesini (varsa) izle — telemetriye eklenir.
  if (navigator.getBattery) {
    navigator.getBattery().then(function (b) { battery = b; }).catch(function () {});
  }

  // Çevrimdışı depo erken kurulur: resume() sayfa yüklenince flushBacklog çağırabilir.
  // (idbQueue/lsQueue/migrateLegacyQueue aşağıda function declaration — hoist edilir.)
  var BATCH_SIZE = 200;
  var Q = ("indexedDB" in window) ? idbQueue() : lsQueue();
  migrateLegacyQueue();

  // ── Giriş ─────────────────────────────────────────────────────────────────
  $("loginBtn").onclick = doLogin;
  $("password").addEventListener("keydown", function (e) { if (e.key === "Enter") doLogin(); });
  $("msgSend").onclick = function () { sendText($("msgInput").value); };
  $("msgInput").addEventListener("keydown", function (e) { if (e.key === "Enter") sendText($("msgInput").value); });
  $("msgMic").onclick = function () { toggleRecord($("msgMic"), sendDriverAudio); };

  function doLogin() {
    var err = $("loginErr"); err.textContent = "";
    var plate = $("plate").value.trim();
    var password = $("password").value;
    if (!plate || !password) { err.textContent = "Plaka ve şifre gerekli."; return; }
    $("loginBtn").disabled = true;

    fetch("/api/v1/track/login", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ plate: plate, password: password, deviceId: deviceId })
    }).then(function (r) {
      if (r.status === 401) throw new Error("Plaka veya şifre hatalı.");
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
    flushBacklog();
    updateQueued();
    loadReplyOptions();
    startMessages();
  }

  $("stopBtn").onclick = function () {
    fetch("/api/v1/track/logout", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sessionToken: session && session.sessionToken }), keepalive: true
    }).catch(function () {});
    stopGps();
    stopMessages();
    releaseWakeLock();
    localStorage.removeItem(SESSION_KEY);
    session = null;
    $("trackView").classList.add("hidden");
    $("loginView").classList.remove("hidden");
    $("password").value = "";
  };

  // Konum izni reddedildiyse: ayarlardan izin verip yeniden dene.
  $("permRetry").onclick = function () {
    showPerm(false);
    stopGps();
    setStatus("wait", "Konum bekleniyor…");
    startGps();
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
    showPerm(true);
  }
  function showPerm(show) { $("permBanner").classList.toggle("hidden", !show); }

  function onPos(pos) {
    var c = pos.coords;
    showPerm(false);
    var now = Date.now();
    var speedKmh = speedFrom(c, pos.timestamp);

    $("mSpeed").textContent = speedKmh == null ? "–" : speedKmh;
    $("mAcc").textContent = c.accuracy == null ? "–" : Math.round(c.accuracy);
    checkSpeedLimit(c.latitude, c.longitude, speedKmh);

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

  // ── Akıllı hız limiti (Overpass + hücre önbelleği + tolerans) ─────────────
  // Yolun hız limiti OSM'den (Overpass) çekilir ve ~110m'lik hücrelerde önbelleğe
  // alınır (aynı yolda tekrar sorgu yok → API maliyeti düşer). Aşım ANLIK
  // cezalandırılmaz: kesintisiz OVER_TOLERANCE_MS boyunca aşılırsa sürücü uyarılır.
  // Bu bir sürücü UYARISIDIR; resmi ihlal kararını backend kural motoru verir.
  var HW_DEFAULT = {
    motorway: 120, motorway_link: 80, trunk: 90, trunk_link: 70,
    primary: 90, primary_link: 50, secondary: 70, secondary_link: 50,
    tertiary: 60, tertiary_link: 40, residential: 50, living_street: 20,
    service: 20, unclassified: 50
  };

  function loadSpeedCache() {
    try { return JSON.parse(localStorage.getItem(SPEED_CACHE_KEY) || "{}"); } catch (e) { return {}; }
  }
  function cellKey(lat, lon) { return lat.toFixed(3) + "," + lon.toFixed(3); }   // ~110m ızgara

  function checkSpeedLimit(lat, lon, speedKmh) {
    var key = cellKey(lat, lon);
    if (Object.prototype.hasOwnProperty.call(speedCache, key)) {
      speedLimit = speedCache[key];                 // önbellekten (null = bilinmiyor)
    } else if (navigator.onLine && Date.now() - lastOverpassAt > OVERPASS_MIN_MS) {
      lastOverpassAt = Date.now();
      fetchOverpass(lat, lon, key);
    }
    setLimitUI();
    if (speedKmh != null) evaluateOverspeed(speedKmh);
  }

  function fetchOverpass(lat, lon, key) {
    var q = "[out:json][timeout:8];way(around:30," + lat + "," + lon + ")[highway];out tags 8;";
    fetch("https://overpass-api.de/api/interpreter", {
      method: "POST", headers: { "Content-Type": "text/plain" }, body: "data=" + encodeURIComponent(q)
    }).then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        var limit = d ? bestLimit(d.elements || []) : null;
        speedCache[key] = limit;                    // null da saklanır: hücre tekrar sorgulanmaz
        try { localStorage.setItem(SPEED_CACHE_KEY, JSON.stringify(speedCache)); } catch (e) {}
        speedLimit = limit; setLimitUI();
      }).catch(function () {});
  }

  // Önce maxspeed etiketli yolu; yoksa yakındaki en büyük highway sınıfının varsayılanı.
  function bestLimit(els) {
    for (var i = 0; i < els.length; i++) {
      var v = parseMaxspeed(els[i].tags);
      if (v) return v;
    }
    var best = null;
    for (var j = 0; j < els.length; j++) {
      var t = els[j].tags || {}, d = HW_DEFAULT[t.highway];
      if (d && (best == null || d > best)) best = d;
    }
    return best;
  }

  function parseMaxspeed(tags) {
    if (!tags || !tags.maxspeed) return null;
    var ms = String(tags.maxspeed);
    if (/mph/i.test(ms)) { var n = parseInt(ms, 10); return n ? Math.round(n * 1.60934) : null; }
    var k = parseInt(ms, 10);
    return k ? k : null;                             // "RO:urban" gibi sayısal olmayan → yok say
  }

  function setLimitUI() {
    $("mLimit").textContent = "Limit: " + (speedLimit ? speedLimit + " km/s" : "–");
  }

  function evaluateOverspeed(speedKmh) {
    if (speedLimit && speedKmh > speedLimit + SPEED_NOISE) {
      if (!overStart) overStart = Date.now();
      if (Date.now() - overStart >= OVER_TOLERANCE_MS) {
        $("speedTile").classList.add("over");
        if (!overWarned) {
          overWarned = true;
          if (navigator.vibrate) navigator.vibrate([200, 100, 200]);
          speak("Hız limitini aştınız");
        }
      }
    } else {
      overStart = 0; overWarned = false;
      $("speedTile").classList.remove("over");
    }
  }

  // ── Çevrimdışı depo (Store & Forward): IndexedDB, localStorage'a fallback ──
  // Aynı arayüz (add/take/remove/count) iki uygulamayı gizler; üstteki tek satır
  // hangisinin kullanılacağını seçer.
  function idbQueue() {
    var DB_NAME = "vts-driver", STORE = "queue", dbP = null;
    function db() {
      if (dbP) return dbP;
      dbP = new Promise(function (res, rej) {
        var r = indexedDB.open(DB_NAME, 1);
        r.onupgradeneeded = function () { r.result.createObjectStore(STORE, { keyPath: "id", autoIncrement: true }); };
        r.onsuccess = function () { res(r.result); };
        r.onerror = function () { rej(r.error); };
      });
      return dbP;
    }
    return {
      add: function (fix) {
        return db().then(function (d) { return new Promise(function (res, rej) {
          var tx = d.transaction(STORE, "readwrite"); tx.objectStore(STORE).add({ fix: fix });
          tx.oncomplete = function () { res(); }; tx.onerror = function () { rej(tx.error); };
        }); });
      },
      take: function (n) {
        return db().then(function (d) { return new Promise(function (res, rej) {
          var out = [], cur = d.transaction(STORE, "readonly").objectStore(STORE).openCursor();
          cur.onsuccess = function () {
            var c = cur.result;
            if (c && out.length < n) { out.push({ id: c.key, fix: c.value.fix }); c.continue(); } else res(out);
          };
          cur.onerror = function () { rej(cur.error); };
        }); });
      },
      remove: function (ids) {
        return db().then(function (d) { return new Promise(function (res, rej) {
          var tx = d.transaction(STORE, "readwrite"), s = tx.objectStore(STORE);
          ids.forEach(function (id) { s.delete(id); });
          tx.oncomplete = function () { res(); }; tx.onerror = function () { rej(tx.error); };
        }); });
      },
      count: function () {
        return db().then(function (d) { return new Promise(function (res, rej) {
          var req = d.transaction(STORE, "readonly").objectStore(STORE).count();
          req.onsuccess = function () { res(req.result); }; req.onerror = function () { rej(req.error); };
        }); });
      }
    };
  }

  function lsQueue() {
    function read() { try { return JSON.parse(localStorage.getItem(QUEUE_KEY) || "[]"); } catch (e) { return []; } }
    function write(a) { localStorage.setItem(QUEUE_KEY, JSON.stringify(a)); }
    return {
      add: function (fix) { var a = read(); a.push(fix); if (a.length > 5000) a = a.slice(-5000); write(a); return Promise.resolve(); },
      take: function (n) { return Promise.resolve(read().slice(0, n).map(function (fix, i) { return { id: i, fix: fix }; })); },
      remove: function (ids) { write(read().slice(ids.length)); return Promise.resolve(); },   // hep baştan boşaltılır
      count: function () { return Promise.resolve(read().length); }
    };
  }

  // 1a'nın localStorage kuyruğunda kalan ölçümleri IndexedDB'ye taşı (bir kereliğine).
  function migrateLegacyQueue() {
    if (!("indexedDB" in window)) return;
    var old;
    try { old = JSON.parse(localStorage.getItem(QUEUE_KEY) || "[]"); } catch (e) { old = []; }
    if (!old.length) return;
    old.reduce(function (p, fix) { return p.then(function () { return Q.add(fix); }); }, Promise.resolve())
      .then(function () { localStorage.removeItem(QUEUE_KEY); updateQueued(); })
      .catch(function () {});
  }

  // ── Gönderim ──────────────────────────────────────────────────────────────
  function sendFix(fix) {
    if (!navigator.onLine) { enqueue(fix); return; }
    post(fix).then(function (res) {
      if (res === "taken") return sessionLost();
      sentCount++; $("mSent").textContent = sentCount;
      setStatus("live", "Canlı — konum gönderiliyor");
      flushBacklog();   // fırsat buldukça biriken çevrimdışı veriyi paket halinde gönder
    }).catch(function () {
      enqueue(fix);
      setStatus("warn", "Bağlantı yok — konum yerelde birikiyor");
    });
  }

  // Tek (canlı) ölçüm. 409 → oturum devralındı ("taken"); diğer hatalar → reject.
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

  // Biriken ölçümleri toplu gönder (keepalive yok — paket 64KB'ı aşabilir).
  function postBatch(fixes) {
    return fetch("/api/v1/track/batch", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Device-Session": session.sessionToken },
      body: JSON.stringify(fixes)
    }).then(function (r) {
      if (r.status === 409) return "taken";
      if (!r.ok) throw new Error("HTTP " + r.status);
      return "ok";
    });
  }

  function sessionLost() {
    setStatus("off", "Bu araca başka bir cihaz giriş yaptı.");
    stopGps();
    stopMessages();
    releaseWakeLock();
    localStorage.removeItem(SESSION_KEY);
    setTimeout(function () {
      $("trackView").classList.add("hidden");
      $("loginView").classList.remove("hidden");
      $("loginErr").textContent = "Oturum başka bir cihazda açıldı. Tekrar giriş yap.";
    }, 2500);
  }

  function enqueue(fix) { Q.add(fix).then(updateQueued).catch(function () {}); }
  function updateQueued() { Q.count().then(function (n) { $("mQueued").textContent = n; }).catch(function () {}); }

  // Biriken veriyi bağlantı gelince paketler halinde boşalt.
  var flushing = false;
  function flushBacklog() {
    if (flushing || !session || !navigator.onLine) return;
    flushing = true;
    Q.take(BATCH_SIZE).then(function (items) {
      if (!items.length) { flushing = false; return; }
      return postBatch(items.map(function (it) { return it.fix; })).then(function (res) {
        if (res === "taken") { flushing = false; return sessionLost(); }
        return Q.remove(items.map(function (it) { return it.id; })).then(function () {
          sentCount += items.length; $("mSent").textContent = sentCount;
          updateQueued();
          flushing = false;
          flushBacklog();   // kuyruk bitene kadar devam
        });
      });
    }).catch(function () { flushing = false; });   // hâlâ çevrimdışı/hata → sonra tekrar dener
  }

  window.addEventListener("online", flushBacklog);

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

  // ── Mesajlaşma: merkezden gelen mesajları TTS ile oku + tek-tık yanıt ──────
  var MSG_POLL_MS = 5000;
  var msgTimer = null;
  var msgEmpty = true;   // "Henüz mesaj yok." yer tutucusu hâlâ duruyor mu

  function startMessages() {
    if (msgTimer) clearInterval(msgTimer);   // idempotent: çift interval → çift/üçlü mesaj olmasın
    loadDriverHistory().then(function () {
      pollMessages();
      msgTimer = setInterval(pollMessages, MSG_POLL_MS);
    });
  }

  // Tam sohbet geçmişini yükle (iki yön, kronolojik) + merkez mesajlarını "görüldü" işaretle,
  // böylece hemen ardından çalışan poll onları TEKRAR getirmez (tekrar okumaz/basmaz).
  function loadDriverHistory() {
    if (!session) return Promise.resolve();
    return fetch("/api/v1/track/history", { headers: { "X-Device-Session": session.sessionToken } })
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (list) {
        var box = $("msgList"); box.innerHTML = "";
        if (!list || !list.length) { box.innerHTML = '<div class="note" style="text-align:left">Henüz mesaj yok.</div>'; msgEmpty = true; return; }
        msgEmpty = false;
        list.forEach(function (m) { appendMsg(m, m.direction === "FROM_DRIVER"); });
      })
      .catch(function () {});
  }
  function stopMessages() {
    if (msgTimer) { clearInterval(msgTimer); msgTimer = null; }
    if (window.speechSynthesis) speechSynthesis.cancel();
  }

  var polling = false;
  function pollMessages() {
    if (!session || !navigator.onLine || polling) return;   // örtüşen poll'leri engelle
    polling = true;
    fetch("/api/v1/track/messages", { headers: { "X-Device-Session": session.sessionToken } })
      .then(function (r) {
        if (r.status === 401) { sessionLost(); return []; }
        if (!r.ok) return [];
        return r.json();
      })
      .then(function (list) {
        (list || []).forEach(function (m) { appendMsg(m, false); if (!m.audio) speak(m.body); });
      })
      .catch(function () {})
      .then(function () { polling = false; });
  }

  function appendMsg(m, mine) {
    var box = $("msgList");
    if (msgEmpty) { box.innerHTML = ""; msgEmpty = false; }
    var el = document.createElement("div");
    el.className = "msg" + (mine ? " me" : "");
    var head = document.createElement("div"); head.className = "cat";
    var t = m.at ? new Date(m.at).toLocaleString("tr-TR", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" }) : "";
    head.textContent = (mine ? "Sen" : "Merkez") + (t ? " · " + t : "");
    var body = document.createElement("div"); body.className = "body";
    if (m.audio) {
      var au = document.createElement("audio"); au.controls = true; au.preload = "metadata";
      au.src = "/api/v1/track/audio/" + m.audio; au.style.maxWidth = "210px"; au.style.height = "40px";
      body.appendChild(au);
    } else { body.textContent = m.body || ""; }
    el.appendChild(head); el.appendChild(body);
    box.appendChild(el);
    box.scrollTop = box.scrollHeight;
  }

  // Türkçe sesli okuma. Uygun bir tr-TR sesi varsa onu seçer.
  function speak(text) {
    if (!text || !window.speechSynthesis) return;
    var u = new SpeechSynthesisUtterance(text);
    u.lang = "tr-TR";
    var tr = speechSynthesis.getVoices().filter(function (v) { return /tr/i.test(v.lang); })[0];
    if (tr) u.voice = tr;
    speechSynthesis.speak(u);
  }

  function loadReplyOptions() {
    fetch("/api/v1/track/reply-options")
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (opts) {
        var box = $("replies"); box.innerHTML = "";
        (opts || []).forEach(function (o) {
          var b = document.createElement("button");
          b.type = "button"; b.textContent = o.label;
          b.onclick = function () { sendText(o.label, b); };
          box.appendChild(b);
        });
      }).catch(function () {});
  }

  // Sürücü → merkez mesajı. Serbest metin ya da hızlı-yanıt çipi; ikisi de aynı uca gider.
  function sendText(text, btn) {
    text = (text || "").trim();
    if (!text || !session) return;
    if (btn) btn.disabled = true;
    fetch("/api/v1/track/message", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Device-Session": session.sessionToken },
      body: JSON.stringify({ text: text })
    }).then(function (r) {
      if (r.status === 401) return sessionLost();
      if (r.ok) { appendMsg({ body: text, at: new Date().toISOString() }, true); $("msgInput").value = ""; }
    }).catch(function () {}).then(function () { if (btn) btn.disabled = false; });
  }

  // ── Sesli mesaj kaydı (MediaRecorder) + gönderme ─────────────────────────
  var mediaRec = null, recChunks = [];
  function toggleRecord(btn, onDone) {
    if (mediaRec && mediaRec.state === "recording") { mediaRec.stop(); return; }
    if (!navigator.mediaDevices || !window.MediaRecorder) { alert("Bu cihaz ses kaydını desteklemiyor."); return; }
    navigator.mediaDevices.getUserMedia({ audio: true }).then(function (stream) {
      recChunks = []; mediaRec = new MediaRecorder(stream);
      mediaRec.ondataavailable = function (e) { if (e.data && e.data.size) recChunks.push(e.data); };
      var to = setTimeout(function () { if (mediaRec && mediaRec.state === "recording") mediaRec.stop(); }, 60000);
      mediaRec.onstop = function () {
        clearTimeout(to); stream.getTracks().forEach(function (t) { t.stop(); }); btn.classList.remove("recording");
        var blob = new Blob(recChunks, { type: (mediaRec && mediaRec.mimeType) || "audio/webm" }); mediaRec = null;
        if (blob.size) onDone(blob);
      };
      mediaRec.start(); btn.classList.add("recording");
    }).catch(function () { alert("Mikrofon izni gerekli."); });
  }
  function sendDriverAudio(blob) {
    if (!session) return;
    var fd = new FormData(); fd.append("file", blob, "voice.webm");
    fetch("/api/v1/track/audio", { method: "POST", headers: { "X-Device-Session": session.sessionToken }, body: fd })
      .then(function (r) {
        if (r.status === 401) return sessionLost();
        return r.ok ? r.json() : null;
      })
      .then(function (d) { if (d && d.audio) appendMsg({ body: "🎤 Sesli mesaj", at: d.at, audio: d.audio }, true); })
      .catch(function () {});
  }

  // ── PWA service worker ────────────────────────────────────────────────────
  if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register("/sw.js").catch(function () {});
  }
})();
