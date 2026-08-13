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
    initTabs();
    showTab("tabDrive");
    requestWakeLock();
    startGps();
    flushBacklog();
    updateQueued();
    loadReplyOptions();
    startMessages();
    startBroadcasts();
    startMaintenance();
    startJob();
    buildInspection();
  }

  $("stopBtn").onclick = function () {
    showDriveSummary();
    fetch("/api/v1/track/logout", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sessionToken: session && session.sessionToken }), keepalive: true
    }).catch(function () {});
    stopGps();
    stopMessages();
    stopBroadcasts();
    stopMaintenance();
    stopJob();
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
    setGpsOff();
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
    setGpsChip(c.accuracy);
    updateMap(c.latitude, c.longitude, speedKmh);
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
    var ml = $("mapLimit"); if (ml) ml.textContent = speedLimit ? speedLimit : "–";
  }

  function evaluateOverspeed(speedKmh) {
    if (speedLimit && speedKmh > speedLimit + SPEED_NOISE) {
      if (!overStart) overStart = Date.now();
      if (Date.now() - overStart >= OVER_TOLERANCE_MS) {
        $("speedTile").classList.add("over");
        $("mapSpeedChip").classList.add("over");
        if (!overWarned) {
          overWarned = true;
          if (navigator.vibrate) navigator.vibrate([200, 100, 200]);
          speak("Hız limitini aştınız");
        }
      }
    } else {
      overStart = 0; overWarned = false;
      $("speedTile").classList.remove("over");
      $("mapSpeedChip").classList.remove("over");
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
    stopBroadcasts();
    stopMaintenance();
    stopJob();
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
    navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 } }).then(function (stream) {
      recChunks = [];
      var opts = (window.MediaRecorder && MediaRecorder.isTypeSupported && MediaRecorder.isTypeSupported("audio/webm;codecs=opus"))
        ? { mimeType: "audio/webm;codecs=opus", audioBitsPerSecond: 128000 } : {};
      mediaRec = new MediaRecorder(stream, opts);
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

  // ── Sekmeler (alt bar ile SPA geçişi) ─────────────────────────────────────
  var tabsInit = false;
  function initTabs() {
    if (tabsInit) return; tabsInit = true;
    document.querySelectorAll(".navBtn").forEach(function (b) {
      b.onclick = function () { showTab(b.getAttribute("data-tab")); };
    });
    var rc = $("mapRecenter");
    if (rc) rc.onclick = function () {
      mapFollow = true;
      if (mapReady && meMarker) lmap.setView(meMarker.getLatLng(), Math.max(lmap.getZoom(), 16));
    };
    initSwipe();
  }

  // Kaydırarak sekme geçişi: sola kaydır → sonraki sekme, sağa → önceki.
  var TAB_ORDER = ["tabDrive", "tabMap", "tabMsg", "tabAnnounce", "tabMaintenance"];
  function currentTabId() {
    var a = document.querySelector(".navBtn.active");
    return a ? a.getAttribute("data-tab") : "tabDrive";
  }
  var swipeInit = false, lastSwipeAt = 0;
  function initSwipe() {
    if (swipeInit) return; swipeInit = true;   // dinleyici yalnızca bir kez bağlansın
    var view = $("trackView");
    if (!view) return;
    var sx = 0, sy = 0, tracking = false;
    view.addEventListener("touchstart", function (e) {
      if (e.touches.length !== 1 || (e.target.closest && e.target.closest("#map"))) { tracking = false; return; }
      tracking = true; sx = e.touches[0].clientX; sy = e.touches[0].clientY;   // harita dokunuşu Leaflet'in
    }, { passive: true });
    view.addEventListener("touchend", function (e) {
      if (!tracking) return; tracking = false;
      var t = e.changedTouches[0], dx = t.clientX - sx, dy = t.clientY - sy;
      if (Math.abs(dx) < 60 || Math.abs(dx) < Math.abs(dy) * 1.5) return;       // belirgin + yatay
      if (Date.now() - lastSwipeAt < 400) return;                               // çift-tetiklemeye karşı
      var i = TAB_ORDER.indexOf(currentTabId()), ni = dx < 0 ? i + 1 : i - 1;
      if (ni >= 0 && ni < TAB_ORDER.length) { lastSwipeAt = Date.now(); showTab(TAB_ORDER[ni]); }
    }, { passive: true });
  }
  function showTab(id) {
    ["tabDrive", "tabMap", "tabMsg", "tabAnnounce", "tabMaintenance"].forEach(function (t) {
      var el = $(t); if (!el) return;
      var show = t === id;
      el.classList.toggle("hidden", !show);
      // Yumuşak giriş: paneli yeniden akışa sokarken enter animasyonunu baştan tetikle.
      if (show) { el.classList.remove("tab-enter"); void el.offsetWidth; el.classList.add("tab-enter"); }
    });
    document.querySelectorAll(".navBtn").forEach(function (b) {
      b.classList.toggle("active", b.getAttribute("data-tab") === id);
    });
    if (id === "tabMap") {
      initMap();
      // Harita gizliyken kurulduysa boyutu 0 olur; görünür olunca ve giriş animasyonu
      // bitince iki kez yeniden ölç (kayma/gri alan olmasın).
      var remeasure = function () {
        if (!lmap) return;
        lmap.invalidateSize();
        if (meMarker && mapFollow) lmap.setView(meMarker.getLatLng(), Math.max(lmap.getZoom(), 15));
      };
      setTimeout(remeasure, 80);
      setTimeout(remeasure, 380);
    }
    if (id === "tabAnnounce") clearAnnounceBadge();
    if (id === "tabMaintenance") { clearMtnBadge(); loadMaintenance(); }
  }

  // ── Gömülü harita (kendi konum + rota izi) ────────────────────────────────
  var lmap = null, meMarker = null, trail = null, trailPts = [], mapFollow = true, mapReady = false;
  function initMap() {
    if (mapReady || typeof L === "undefined") return;
    lmap = L.map("map", { zoomControl: true, attributionControl: false }).setView([41.02, 29.0], 15);
    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19 }).addTo(lmap);
    trail = L.polyline(trailPts, { color: "#00f5d4", weight: 4, opacity: .85 }).addTo(lmap);
    lmap.on("dragstart", function () { mapFollow = false; });   // kullanıcı gezerken takibi bırak
    mapReady = true;
    if (lastFix) placeMe(lastFix.lat, lastFix.lon);
  }
  function meIcon() {
    return L.divIcon({ className: "", iconSize: [18, 18], iconAnchor: [9, 9],
      html: '<div style="width:14px;height:14px;border-radius:50%;background:#00f5d4;border:3px solid #0b1220;box-shadow:0 0 10px rgba(0,245,212,.9)"></div>' });
  }
  function placeMe(lat, lon) {
    if (!mapReady) return;
    if (!meMarker) meMarker = L.marker([lat, lon], { icon: meIcon(), zIndexOffset: 1000 }).addTo(lmap);
    else meMarker.setLatLng([lat, lon]);
    if (mapFollow) lmap.panTo([lat, lon], { animate: true, duration: .4 });
  }
  // "Bugün" mini özeti (mesafe/süre/durak) — iz noktalarından türetilir.
  var tripDistM = 0, tripStartMs = 0, tripStops = 0, stopStartMs = 0, stopCounted = false;
  var STOP_KMH = 3, TRIP_MIN_STOP_MS = 120000;   // hız<3 km/s ve 2 dk → 1 durak

  // onPos'tan beslenir: iz noktası ekle (harita kapalıyken bile birikir), marker'ı taşı.
  function updateMap(lat, lon, speedKmh) {
    var now = Date.now();
    var last = trailPts[trailPts.length - 1];
    if (!last || haversine(last[0], last[1], lat, lon) > 4) {   // >4 m: gürültüyle iz şişmesin
      if (last) tripDistM += haversine(last[0], last[1], lat, lon);
      trailPts.push([lat, lon]);
      if (trailPts.length > 1000) trailPts.shift();
      if (trail) trail.setLatLngs(trailPts);
    }
    if (!tripStartMs) tripStartMs = now;
    if (speedKmh != null && speedKmh <= STOP_KMH) {            // durak tespiti (kesintisiz duruş)
      if (!stopStartMs) stopStartMs = now;
      else if (!stopCounted && now - stopStartMs >= TRIP_MIN_STOP_MS) { tripStops++; stopCounted = true; }
    } else if (speedKmh != null) { stopStartMs = 0; stopCounted = false; }
    placeMe(lat, lon);
    var ms = $("mapSpeed"); if (ms) ms.textContent = speedKmh == null ? "–" : speedKmh;
    updateTripSummary();
  }
  function updateTripSummary() {
    var d = $("tripDist"); if (d) d.textContent = (tripDistM / 1000).toFixed(1);
    var du = $("tripDur"); if (du) du.textContent = fmtDur(tripStartMs ? Date.now() - tripStartMs : 0);
    var s = $("tripStops"); if (s) s.textContent = tripStops;
  }
  function fmtDur(ms) {
    var m = Math.floor(ms / 60000);
    return m < 60 ? m + "dk" : Math.floor(m / 60) + "s " + (m % 60) + "dk";
  }

  // Sürüş bitince özet kartı (mesafe/süre/durak) — sonra sürüş sayaçlarını sıfırlar.
  function showDriveSummary() {
    if (!tripStartMs) return;
    var km = (tripDistM / 1000).toFixed(1), dur = fmtDur(Date.now() - tripStartMs), stops = tripStops;
    var el = document.createElement("div");
    el.id = "driveSummary";
    el.style.cssText = "position:fixed;inset:0;z-index:90;display:flex;align-items:center;justify-content:center;background:rgba(5,10,18,.7);backdrop-filter:blur(4px);padding:24px";
    var tile = "flex:1;background:#0c1a2b;border:1px solid var(--line);border-radius:12px;padding:12px";
    el.innerHTML = '<div style="background:var(--panel);border:1px solid var(--line);border-radius:18px;padding:22px;max-width:340px;width:100%;text-align:center;backdrop-filter:blur(14px)">'
      + '<div style="font-size:34px">🚗</div>'
      + '<div style="font-weight:800;font-size:18px;margin:6px 0 4px">Sürüş özeti</div>'
      + '<div style="color:var(--muted);font-size:13px;margin-bottom:16px">İyi sürüşler!</div>'
      + '<div style="display:flex;gap:10px;margin-bottom:18px">'
      + '<div style="' + tile + '"><div style="font-size:22px;font-weight:800">' + km + '</div><div style="color:var(--muted);font-size:10px;text-transform:uppercase">km</div></div>'
      + '<div style="' + tile + '"><div style="font-size:22px;font-weight:800">' + dur + '</div><div style="color:var(--muted);font-size:10px;text-transform:uppercase">süre</div></div>'
      + '<div style="' + tile + '"><div style="font-size:22px;font-weight:800">' + stops + '</div><div style="color:var(--muted);font-size:10px;text-transform:uppercase">durak</div></div>'
      + '</div><button id="driveSummaryClose">Kapat</button></div>';
    document.body.appendChild(el);
    el.querySelector("#driveSummaryClose").onclick = function () { el.remove(); resetTrip(); };
  }
  function resetTrip() {
    tripDistM = 0; tripStartMs = 0; tripStops = 0; stopStartMs = 0; stopCounted = false;
    trailPts.length = 0; if (trail) trail.setLatLngs([]); updateTripSummary();
  }

  // GPS kalite mikro-göstergesi: doğruluğa göre iyi/orta/zayıf.
  function setGpsChip(acc) {
    var chip = $("gpsChip"), txt = $("gpsText");
    if (!chip) return;
    chip.classList.remove("good", "mid", "low");
    if (acc == null) { txt.textContent = "GPS —"; return; }
    var a = Math.round(acc);
    if (a <= 20) { chip.classList.add("good"); txt.textContent = "GPS iyi"; }
    else if (a <= 50) { chip.classList.add("mid"); txt.textContent = "GPS orta ±" + a + "m"; }
    else { chip.classList.add("low"); txt.textContent = "GPS zayıf ±" + a + "m"; }
  }
  function setGpsOff() {
    var chip = $("gpsChip"), txt = $("gpsText");
    if (!chip) return;
    chip.classList.remove("good", "mid"); chip.classList.add("low"); txt.textContent = "GPS yok";
  }

  // ── Admin duyuruları (poll — telefonda JWT yok, /topic yerine anket) ───────
  var BC_POLL_MS = 12000, bcTimer = null, bcPolling = false;
  var BC_SEEN_KEY = "vts_bcast_seen";
  var bcSeen = loadBcSeen();
  var bcPrimed = localStorage.getItem(BC_SEEN_KEY) != null;   // daha önce açıldıysa geçmiş "görülmüş"
  var bcUnread = 0, bcBannerTimer = null;

  function loadBcSeen() { try { return new Set(JSON.parse(localStorage.getItem(BC_SEEN_KEY) || "[]")); } catch (e) { return new Set(); } }
  function saveBcSeen() { try { localStorage.setItem(BC_SEEN_KEY, JSON.stringify(Array.from(bcSeen).slice(-300))); } catch (e) {} }

  function startBroadcasts() { if (bcTimer) clearInterval(bcTimer); pollBroadcasts(); bcTimer = setInterval(pollBroadcasts, BC_POLL_MS); }
  function stopBroadcasts() { if (bcTimer) { clearInterval(bcTimer); bcTimer = null; } hideBcBanner(); }

  function pollBroadcasts() {
    if (!session || !navigator.onLine || bcPolling) return;
    bcPolling = true;
    fetch("/api/v1/track/broadcasts", { headers: { "X-Device-Session": session.sessionToken } })
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (list) {
        list = list || [];
        renderAnnounce(list);
        var fresh = list.filter(function (b) { return b.id != null && !bcSeen.has(b.id); });
        list.forEach(function (b) { if (b.id != null) bcSeen.add(b.id); });
        saveBcSeen();
        if (!bcPrimed) { bcPrimed = true; return; }        // ilk yükleme: banner yok (geçmiş)
        fresh.slice().reverse().forEach(onNewBroadcast);   // eskiden yeniye sırayla göster
      })
      .catch(function () {})
      .then(function () { bcPolling = false; });
  }

  function onNewBroadcast(b) {
    if ($("tabAnnounce").classList.contains("hidden")) { bcUnread++; updateAnnounceBadge(); }
    showBcBanner(b);
    // Acil duyuruda daha güçlü/uzun titreşim.
    if (navigator.vibrate) navigator.vibrate(b.severity === "URGENT" ? [300, 120, 300, 120, 300] : [120, 60, 120]);
    speak((b.severity === "URGENT" ? "Acil. " : "") + (b.title ? b.title + ". " : "") + (b.body || ""));
  }

  function showBcBanner(b) {
    var el = $("bcastBanner");
    var tt = $("bcastBannerTitle");
    var urgent = b.severity === "URGENT";
    el.classList.toggle("urgent", urgent);
    var lab = el.querySelector(".lab"); if (lab) lab.textContent = urgent ? "Acil duyuru" : "Genel duyuru";
    tt.textContent = b.title || ""; tt.style.display = b.title ? "" : "none";
    $("bcastBannerBody").textContent = b.body || "";
    el.classList.remove("hidden");
    el.onclick = function () { hideBcBanner(); showTab("tabAnnounce"); };
    el.style.top = "-200px"; el.style.opacity = "0";
    requestAnimationFrame(function () { requestAnimationFrame(function () {
      el.style.top = "calc(env(safe-area-inset-top) + 10px)"; el.style.opacity = "1";
    }); });
    if (bcBannerTimer) clearTimeout(bcBannerTimer);
    bcBannerTimer = setTimeout(hideBcBanner, 10000);   // 10 sn ekranda kalır
  }
  function hideBcBanner() {
    var el = $("bcastBanner"); if (!el) return;
    el.style.top = "-200px"; el.style.opacity = "0";
    setTimeout(function () { el.classList.add("hidden"); }, 420);
  }

  function renderAnnounce(list) {
    var box = $("announceList");
    if (!list.length) { box.innerHTML = '<div class="note" style="text-align:left">Henüz duyuru yok.</div>'; return; }
    box.innerHTML = "";
    list.forEach(function (b) {
      var urgent = b.severity === "URGENT";
      var el = document.createElement("div"); el.className = "announce" + (urgent ? " urgent" : "");
      if (b.title || urgent) {
        var t = document.createElement("div"); t.className = "t"; t.textContent = b.title || "";
        if (urgent) { var sv = document.createElement("span"); sv.className = "sev"; sv.textContent = "Acil"; t.appendChild(sv); }
        el.appendChild(t);
      }
      var body = document.createElement("div"); body.textContent = b.body || ""; el.appendChild(body);
      var meta = document.createElement("div"); meta.className = "meta";
      var when = b.at ? new Date(b.at).toLocaleString("tr-TR", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" }) : "";
      meta.textContent = (b.sender || "admin") + (when ? " · " + when : ""); el.appendChild(meta);
      box.appendChild(el);
    });
  }
  function updateAnnounceBadge() {
    var el = $("announceBadge");
    if (bcUnread > 0) { el.textContent = bcUnread > 99 ? "99+" : bcUnread; el.classList.remove("hidden"); }
    else el.classList.add("hidden");
  }
  function clearAnnounceBadge() { bcUnread = 0; updateAnnounceBadge(); }

  // ── Bakım (sürücü işaretler; admin yalnızca görür) ────────────────────────
  var MTN_POLL_MS = 30000, mtnTimer = null, mtnPolling = false, mtnData = [];
  var MTN_LABEL = { PENDING: "Bekleniyor", IN_PROGRESS: "Bakımda", DONE: "Yapıldı" };
  var MTN_STEPS = ["PENDING", "IN_PROGRESS", "DONE"];

  function startMaintenance() { if (mtnTimer) clearInterval(mtnTimer); pollMaintenance(); mtnTimer = setInterval(pollMaintenance, MTN_POLL_MS); }
  function stopMaintenance() { if (mtnTimer) { clearInterval(mtnTimer); mtnTimer = null; } }

  function pollMaintenance() {
    if (!session || !navigator.onLine || mtnPolling) return;
    mtnPolling = true;
    fetch("/api/v1/track/maintenance", { headers: { "X-Device-Session": session.sessionToken } })
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (list) {
        mtnData = list || [];
        updateMtnBadge();
        if (!$("tabMaintenance").classList.contains("hidden")) renderMaintenance(mtnData);
      })
      .catch(function () {})
      .then(function () { mtnPolling = false; });
  }

  // Sekmeye girince hızlı yükleme (poll'u beklemeden).
  function loadMaintenance() {
    if (mtnData.length) renderMaintenance(mtnData);
    pollMaintenance();
  }

  function dueNeedingAction() {
    return mtnData.filter(function (m) { return m.due && m.status !== "DONE"; }).length;
  }
  function updateMtnBadge() {
    var el = $("mtnBadge"); if (!el) return;
    var n = dueNeedingAction();
    if (n > 0 && $("tabMaintenance").classList.contains("hidden")) {
      el.textContent = n > 99 ? "99+" : n; el.classList.remove("hidden");
    } else el.classList.add("hidden");
  }
  function clearMtnBadge() { var el = $("mtnBadge"); if (el) el.classList.add("hidden"); }

  function renderMaintenance(list) {
    var box = $("mtnList"); if (!box) return;
    if (!list.length) { box.innerHTML = '<div class="note" style="text-align:left">Bu araç için tanımlı bakım yok.</div>'; return; }
    box.innerHTML = "";
    list.forEach(function (m) {
      var card = document.createElement("div");
      card.className = "mtnCard" + (m.due && m.status !== "DONE" ? " due" : "");

      var head = document.createElement("div"); head.className = "h";
      var nm = document.createElement("div"); nm.className = "nm"; nm.textContent = m.name || "Bakım";
      var badge = document.createElement("span");
      badge.className = "mtnBadge " + (m.status || "PENDING");
      badge.textContent = MTN_LABEL[m.status] || "Bekleniyor";
      head.appendChild(nm); head.appendChild(badge); card.appendChild(head);

      var bits = [];
      if (m.nextDueKm != null) bits.push(Number(m.nextDueKm).toLocaleString("tr") + " km");
      if (m.nextDueAt) bits.push(new Date(m.nextDueAt).toLocaleDateString("tr-TR"));
      var sub = document.createElement("div"); sub.className = "sub";
      sub.textContent = (m.due ? "Vadesi geldi · " : "Sonraki: ") + (bits.join(" / ") || "tarih yok");
      card.appendChild(sub);

      var actions = document.createElement("div"); actions.className = "mtnActions";
      MTN_STEPS.forEach(function (st) {
        var b = document.createElement("button");
        b.textContent = MTN_LABEL[st];
        if (m.status === st) b.className = "on";
        b.onclick = function () { setMtnStatus(m.planId, st, b); };
        actions.appendChild(b);
      });
      card.appendChild(actions);
      box.appendChild(card);
    });
  }

  function setMtnStatus(planId, status, btn) {
    if (btn) btn.disabled = true;
    fetch("/api/v1/track/maintenance/" + planId + "/status", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Device-Session": session.sessionToken },
      body: JSON.stringify({ status: status })
    })
      .then(function (r) { if (!r.ok) throw 0; return r.json(); })
      .then(function () {
        if (navigator.vibrate) navigator.vibrate(40);
        pollMaintenance();   // sunucudaki gerçek durumu (DONE sonrası kaydırma dahil) geri al
      })
      .catch(function () { if (btn) btn.disabled = false; });
  }

  // ── Görev (admin atar; sürücü durumu ilerletir) ──────────────────────────
  var JOB_POLL_MS = 20000, jobTimer = null, jobPolling = false, curJob = null;
  var JOB_BADGE = { ASSIGNED: "PENDING", EN_ROUTE: "IN_PROGRESS", ARRIVED: "DONE" };
  var JOB_LABEL = { ASSIGNED: "Atandı", EN_ROUTE: "Yolda", ARRIVED: "Vardı" };

  function startJob() {
    if (jobTimer) clearInterval(jobTimer);
    var card = $("jobCard");
    if (card) card.querySelectorAll(".mtnActions button").forEach(function (b) {
      b.onclick = function () { setJobStatus(b.getAttribute("data-jst"), b); };
    });
    pollJob();
    jobTimer = setInterval(pollJob, JOB_POLL_MS);
  }
  function stopJob() { if (jobTimer) { clearInterval(jobTimer); jobTimer = null; } }

  function pollJob() {
    if (!session || !navigator.onLine || jobPolling) return;
    jobPolling = true;
    fetch("/api/v1/track/job", { headers: { "X-Device-Session": session.sessionToken } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (j) { curJob = (j && j.jobId) ? j : null; renderJob(); })
      .catch(function () {})
      .then(function () { jobPolling = false; });
  }

  function renderJob() {
    var card = $("jobCard"); if (!card) return;
    if (!curJob) { card.classList.add("hidden"); return; }
    card.classList.remove("hidden");
    $("jobCardTitle").textContent = curJob.title || "Görev";
    var badge = $("jobCardBadge");
    badge.className = "mtnBadge " + (JOB_BADGE[curJob.status] || "PENDING");
    badge.textContent = JOB_LABEL[curJob.status] || curJob.status;
    var bits = [];
    if (curJob.destLabel) bits.push(curJob.destLabel);
    if (curJob.remainingKm != null) bits.push(curJob.remainingKm + " km");
    if (curJob.etaMin != null) bits.push("~" + curJob.etaMin + " dk");
    $("jobCardSub").textContent = bits.join(" · ") || "Hedefe git";
    $("jobNavBtn").href = "https://www.google.com/maps/dir/?api=1&destination=" + curJob.destLat + "," + curJob.destLon;
  }

  function setJobStatus(status, btn) {
    if (!curJob) return;
    if (btn) btn.disabled = true;
    fetch("/api/v1/track/job/" + curJob.jobId + "/status", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Device-Session": session.sessionToken },
      body: JSON.stringify({ status: status })
    })
      .then(function (r) { if (!r.ok) throw 0; return r.json(); })
      .then(function () { if (navigator.vibrate) navigator.vibrate(40); pollJob(); })
      .catch(function () { if (btn) btn.disabled = false; });
  }

  // ── Sefer öncesi araç kontrolü (DVIR) ─────────────────────────────────────
  var INSP_ITEMS = [["tires", "Lastikler"], ["brakes", "Frenler"], ["lights", "Far & sinyaller"],
    ["fluids", "Sıvı seviyeleri"], ["body", "Kaporta & hasar"], ["horn", "Korna & aynalar"]];
  var inspState = {}, inspBuilt = false;

  function buildInspection() {
    if (inspBuilt) return;
    var box = $("inspList"); if (!box) return;
    inspBuilt = true;
    box.innerHTML = "";
    INSP_ITEMS.forEach(function (it) {
      var key = it[0];
      inspState[key] = "ok";   // varsayılan: iyi (tek dokunuşla "hepsi iyi" gönderilebilir)
      var row = document.createElement("div"); row.className = "inspRow";
      var nm = document.createElement("div"); nm.className = "nm"; nm.textContent = it[1];
      var btns = document.createElement("div"); btns.className = "inspBtns";
      var ok = document.createElement("button"); ok.textContent = "İyi"; ok.className = "ok";
      var bad = document.createElement("button"); bad.textContent = "Kusurlu";
      ok.onclick = function () { inspState[key] = "ok"; ok.className = "ok"; bad.className = ""; };
      bad.onclick = function () { inspState[key] = "defect"; bad.className = "bad"; ok.className = ""; };
      btns.appendChild(ok); btns.appendChild(bad);
      row.appendChild(nm); row.appendChild(btns); box.appendChild(row);
    });
    var btn = $("inspSubmit"); if (btn) btn.onclick = submitInspection;
  }

  function submitInspection() {
    if (!session) return;
    var btn = $("inspSubmit"), res = $("inspResult");
    btn.disabled = true;
    fetch("/api/v1/track/inspection", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Device-Session": session.sessionToken },
      body: JSON.stringify({ items: inspState, note: ($("inspNote").value || "").trim() })
    })
      .then(function (r) { if (!r.ok) throw 0; return r.json(); })
      .then(function (d) {
        if (navigator.vibrate) navigator.vibrate(60);
        res.style.color = d.overall === "DEFECT" ? "var(--danger)" : "var(--teal)";
        res.textContent = d.overall === "DEFECT"
          ? "Kusur bildirildi — merkez bilgilendirildi." : "Kontrol tamam — araç uygun. Teşekkürler.";
        $("inspNote").value = "";
      })
      .catch(function () { res.style.color = "var(--danger)"; res.textContent = "Gönderilemedi, tekrar dene."; })
      .then(function () { btn.disabled = false; });
  }

  // ── PWA service worker ────────────────────────────────────────────────────
  if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register("/sw.js").catch(function () {});
  }
})();
