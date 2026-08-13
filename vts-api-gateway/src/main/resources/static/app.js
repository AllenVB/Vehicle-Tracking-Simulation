/* FleetFlow — tek harita + Canlı/Geçmiş, emerald tema, gerçek VTS gateway'e bağlı. */
(function () {
  "use strict";
  let token = null, map = null, stomp = null, view = "live";
  let selected = null, followId = null, alertCount = 0, selTrackLayer = null;
  const vehicles = new Map();   // id -> {trackId, plate, make, model, type}
  const pos = new Map();        // id -> position
  const markers = new Map();    // id -> L.marker
  const tripCache = new Map();  // id -> {distanceKm, ecoScore, score}
  const recentVios = [];        // {vehicleId,ruleCode,value,threshold,occurredAt}
  let fleetFilter = "all", dash = null, cluster = null, showPlates = false, fleetSig = "";
  const broadcasts = [];          // {id, sender, title, body, at} — en yeni önce (bildirim paneli)
  let inbox = [];                 // {id, vehicleId, plate, body, audio, at} — gelen sürücü mesajları
  const inboxDismissed = new Set(JSON.parse(localStorage.getItem("vts_inbox_dismissed") || "[]"));
  const bcastSeen = new Set();    // aynı duyuruyu iki kez işlemeyi önle (id)
  const notifRead = new Set(JSON.parse(localStorage.getItem("vts_read") || "[]"));              // okundu anahtarları (b:id / d:id)
  const bcastDismissed = new Set(JSON.parse(localStorage.getItem("vts_bcast_dismissed") || "[]")); // silinen duyurular
  let bcastSeverity = "INFO";     // composer'da seçili önem (INFO | URGENT)
  const bannerQueue = []; let bannerBusy = false;   // ortadaki 10 sn banner kuyruğu (üst üste binmesin)
  const BANNER_MS = 10000;        // "10 saniye boyunca kalsın"
  const STALE_MS = 30000, VIO_SPEED = 82, STOP_SPEED = 3, MIN_STOP_SEC = 120, STOP_RADIUS_M = 40;
  const RED_WINDOW_MS = 120000;   // 6b: bir nokta, gerçek hız ihlalinin ±2 dk'sındaysa kırmızı

  const $ = id => document.getElementById(id);
  const auth = () => ({ Authorization: "Bearer " + token });
  const esc = s => (s || "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  const RULE_TR = { SPEED_LIMIT: "Hız aşımı", HARSH_BRAKING: "Sert fren", HARSH_ACCELERATION: "Ani hızlanma", HARSH_ACCEL: "Ani hızlanma", HARSH_CORNERING: "Sert viraj", IDLING: "Uzun rölanti", GEOFENCE: "Bölge ihlali", ROUTE_DEVIATION: "Rota sapması" };
  const ruleLabel = c => RULE_TR[c] || c || "İhlal";
  // "Son görülme" relatif zaman: az önce / N dk önce / N sa önce / N gün önce.
  function relTime(ts) {
    if (!ts) return "—";
    const t = Date.parse(ts); if (isNaN(t)) return "—";
    const s = Math.max(0, Math.floor((Date.now() - t) / 1000));
    if (s < 60) return "az önce";
    const m = Math.floor(s / 60); if (m < 60) return m + " dk önce";
    const h = Math.floor(m / 60); if (h < 24) return h + " sa önce";
    return Math.floor(h / 24) + " gün önce";
  }
  function vioDetail(e) {
    if (e.value == null) return "";
    const v = Math.round(e.value), t = e.threshold != null ? Math.round(e.threshold) : null;
    if ((e.ruleCode || "").indexOf("SPEED") >= 0) return v + " km/s" + (t != null ? " · limit " + t : "");
    return Math.abs(v) + (t != null ? " · eşik " + Math.abs(t) : "");   // fren/ivme: büyüklük göster
  }

  // ── Otomatik giriş ─────────────────────────────────────────────────────────
  // Admin paneli şifre sormaz: token arka planda alınır (backend yine JWT ile korunur).
  autoLogin();
  async function autoLogin() {
    try {
      const r = await fetch("/api/v1/auth/login", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: "admin", password: "password" }) });
      if (!r.ok) throw 0;
      token = (await r.json()).token;
      start();
    } catch (e) { setTimeout(autoLogin, 2000); }   // gateway hazır olana kadar tekrar dene
  }

  async function start() {
    map = L.map("map", { zoomControl: false, attributionControl: false }).setView([41.02, 29.0], 11);
    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19 }).addTo(map);
    // Marker kümeleme: yakın araçlar zoom-out'ta tek daireye toplanır (üzerinde adet),
    // zoom ≥16'da tek tek gösterilir. Küme ikonu emerald temaya uygun.
    cluster = L.markerClusterGroup({
      maxClusterRadius: 50, showCoverageOnHover: false, disableClusteringAtZoom: 16,
      iconCreateFunction: cl => {
        const n = cl.getChildCount();
        return L.divIcon({ className: "", iconSize: [36, 36], html:
          `<div style="background:rgba(78,222,163,.92);color:#003824;font-weight:800;width:36px;height:36px;border-radius:50%;display:flex;align-items:center;justify-content:center;border:3px solid #131313;box-shadow:0 0 10px rgba(78,222,163,.6)">${n}</div>` });
      }
    });
    map.addLayer(cluster);
    window._map = map;
    window._locate = fitAll;
    await loadVehicles();
    await loadSnapshot();
    await seedViolations();
    connectWs();
    initDaySelect();
    initQr();
    initSelActions();
    initPlayback();
    initFleet();
    initGeofence();
    initJobs();
    initMaintenance();
    initInspections();
    initChat();
    initBroadcast();
    Promise.all([loadBroadcasts(), loadInbox()]).then(seedReadOnce);   // ilk açılışta geçmiş = okundu
    loadDashboard().then(maybeDailySummary);
    setInterval(loadVehicles, 15000);
    setInterval(sweep, 5000);
    setInterval(loadDashboard, 30000);
    toggleView("live");
  }

  // ── Araçlar (adminin oluşturduğu tüm filo) ────────────────────────────────
  async function loadVehicles() {
    const r = await fetch("/api/v1/vehicles", { headers: auth() });
    if (!r.ok) return;
    const list = await r.json();
    vehicles.clear();
    const el = $("vehicleList"), hv = $("histVehicle"), prev = hv.value;
    el.innerHTML = ""; hv.innerHTML = "";
    list.forEach((v, i) => {
      v.trackId = i + 1;
      vehicles.set(v.id, v);
      const model = esc([v.make, v.model].filter(Boolean).join(" ") || "Telefon GPS");
      const card = document.createElement("div");
      card.className = "vcard group bg-white/5 hover:bg-white/10 p-3 rounded-xl border border-white/5 transition-all cursor-pointer";
      card.dataset.vid = v.id;
      card.innerHTML =
        `<div class="flex justify-between items-start gap-2">
           <div class="min-w-0">
             <div class="text-body-md text-on-surface font-semibold truncate">#${v.trackId} · ${esc(v.plate)}</div>
             <div class="text-label-sm text-on-surface-variant truncate">${model}</div>
           </div>
           <div class="flex flex-col items-end gap-1 flex-none">
             <span class="vchip inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold border">
               <span class="vdot w-1.5 h-1.5 rounded-full"></span><span class="vstate">—</span></span>
             <span class="vspeed text-[10px] text-on-surface-variant font-medium">— km/s</span>
             <span class="vlast text-[9px] opacity-50 font-normal text-on-surface-variant"></span>
           </div>
         </div>`;
      card.onclick = () => select(v.id);
      el.appendChild(card);
      const o = document.createElement("option"); o.value = v.id; o.textContent = `#${v.trackId} · ${v.plate}`; hv.appendChild(o);
    });
    if (!list.length) el.innerHTML = '<div class="text-label-sm text-on-surface-variant px-1">Henüz araç yok — Filo sekmesinden + ile ekle.</div>';
    if (prev && vehicles.has(+prev)) hv.value = prev;
    $("statTotal").textContent = list.length;
    refreshCards();
  }

  // ── Canlı ────────────────────────────────────────────────────────────────
  async function loadSnapshot() { const r = await fetch("/api/v1/live/positions", { headers: auth() }); if (r.ok) apply(await r.json()); }
  function connectWs() {
    stomp = new StompJs.Client({ webSocketFactory: () => new SockJS("/ws"), connectHeaders: { Authorization: "Bearer " + token }, reconnectDelay: 3000 });
    stomp.onConnect = () => { setConn(true);
      stomp.subscribe("/topic/fleet/live", m => apply(JSON.parse(m.body).vehicles || []));
      stomp.subscribe("/topic/violations", m => onViolation(JSON.parse(m.body), true));
      stomp.subscribe("/topic/vehicle-messages", m => onDriverMessage(JSON.parse(m.body)));
      stomp.subscribe("/topic/broadcast", m => onBroadcast(JSON.parse(m.body)));   // admin duyurusu (pub/sub)
    };
    stomp.onWebSocketClose = () => setConn(false);
    stomp.activate();
  }
  function setConn(ok) { $("connDot").style.background = ok ? "#4edea3" : "#ffb4ab"; $("connText").textContent = ok ? "Bağlı · canlı" : "Bağlantı koptu"; }

  function apply(list) {
    list.forEach(p => {
      if (p.lat == null || p.lon == null || !vehicles.has(p.vehicleId)) return;
      pos.set(p.vehicleId, p);
      drawMarker(p);
      if (view === "history") extendHistory(p);
      if (p.vehicleId === selected) updateOverlay();
    });
    updateStats();
    if (followId != null && view === "live") { const fp = pos.get(followId); if (fp) map.panTo([fp.lat, fp.lon], { animate: true }); }
    refreshCards();
    if (cluster && view !== "history") cluster.refreshClusters();   // taşınan marker'ları yeniden kümele
  }
  function isStale(p) { if (!p || !p.ts) return false; const t = Date.parse(p.ts); return !isNaN(t) && (Date.now() - t) > STALE_MS; }
  function liveCount() { let n = 0; pos.forEach(p => { if (!isStale(p)) n++; }); return n; }
  function updateStats() {
    const live = liveCount(), total = vehicles.size;
    $("statLive").textContent = live;
    const ratio = total ? Math.round(live / total * 100) : 0;
    $("fleetRatio").textContent = ratio + "%";
    $("fleetBar").style.width = ratio + "%";
  }

  // durum: çevrimdışı / ihlal(hız) / hareket / durgun
  function stateOf(id) {
    const p = pos.get(id);
    if (!p) return { key: "idle", label: "Beklemede", color: "#86948a" };
    if (isStale(p)) return { key: "off", label: "Çevrimdışı", color: "#86948a" };
    if (p.speedKmh != null && p.speedKmh > VIO_SPEED) return { key: "vio", label: "İhlal", color: "#ffb4ab" };
    if (p.speedKmh != null && p.speedKmh > STOP_SPEED) return { key: "move", label: "Hareket", color: "#4edea3" };
    return { key: "park", label: "Durgun", color: "#60a5fa" };
  }
  function colorOf(id) { return stateOf(id).color; }
  function drawMarker(p) {
    const c = colorOf(p.vehicleId), live = c === "#4edea3";
    const icon = L.divIcon({ className: "", iconSize: [16, 16], iconAnchor: [8, 8],
      html: `<div style="background:${c};width:14px;height:14px;border-radius:50%;border:3px solid #131313;${live ? "box-shadow:0 0 8px rgba(78,222,163,.7);" : ""}"></div>` });
    let m = markers.get(p.vehicleId);
    if (!m) { m = L.marker([p.lat, p.lon], { icon }); m.on("click", () => select(p.vehicleId)); markers.set(p.vehicleId, m); if (cluster) cluster.addLayer(m); }
    else { m.setLatLng([p.lat, p.lon]); m.setIcon(icon); }
    applyPlateLabel(m, p.vehicleId);
  }
  // Haritada plaka etiketi (kalıcı Leaflet tooltip) — toggle ile açılır/kapanır.
  function applyPlateLabel(m, id) {
    if (!m) return;
    if (showPlates) {
      if (!m.getTooltip()) {
        const v = vehicles.get(id);
        m.bindTooltip(esc(v ? v.plate : ""), { permanent: true, direction: "top", className: "plate-label", offset: [0, -8] });
      }
    } else if (m.getTooltip()) { m.unbindTooltip(); }
  }
  function togglePlates() {
    showPlates = !showPlates;
    const btn = $("plateToggle");
    if (btn) { btn.classList.toggle("text-primary", showPlates); btn.classList.toggle("text-on-surface-variant", !showPlates); }
    markers.forEach((m, id) => applyPlateLabel(m, id));
    if (cluster) cluster.refreshClusters();
  }
  function sweep() { pos.forEach(p => drawMarker(p)); updateStats(); refreshCards(); if (cluster && view !== "history") cluster.refreshClusters(); if (selected != null) updateOverlay(); if (view === "fleet") refreshFleetCards(); }
  function fitAll() {
    const pts = []; pos.forEach(p => { if (p.lat != null) pts.push([p.lat, p.lon]); });
    if (pts.length) map.fitBounds(L.latLngBounds(pts).pad(0.25));
  }

  function refreshCards() {
    document.querySelectorAll(".vcard").forEach(card => {
      const id = +card.dataset.vid, p = pos.get(id), st = stateOf(id);
      const dot = card.querySelector(".vdot"), chip = card.querySelector(".vchip"),
            state = card.querySelector(".vstate"), spd = card.querySelector(".vspeed"),
            last = card.querySelector(".vlast");
      if (dot) dot.style.background = st.color;
      if (state) state.textContent = st.label;
      if (chip) { chip.style.color = st.color; chip.style.borderColor = st.color + "55"; chip.style.background = st.color + "1a"; }
      if (spd) spd.textContent = (p && p.speedKmh != null ? p.speedKmh : "—") + " km/s";
      if (last) last.textContent = p && p.ts ? relTime(p.ts) : "";
      const on = id === selected;
      card.classList.toggle("emerald-glow", on);
      card.style.background = on ? "rgba(78,222,163,.14)" : "";
      card.style.borderColor = on ? "#4edea3" : "";
    });
  }

  // ── Seçili araç ──────────────────────────────────────────────────────────
  function select(id) {
    selected = id;
    refreshCards();
    const ov = $("selOverlay");
    if (id == null) { ov.classList.add("hidden"); followId = null; clearSelTrack(); if (view === "history") clearHistory(); return; }
    updateOverlay();
    loadTrip(id);
    ov.classList.remove("hidden");
    const p = pos.get(id);
    if (p && view === "live") map.panTo([p.lat, p.lon]);
    $("histVehicle").value = id;
    if (view === "history") loadHistory(id, currentDay(), true);
    else if (view === "live") showTodayTrack(id);
  }

  // Seçili araç AKTİFSE, bugün geçtiği yerleri canlı haritada mavi rota olarak göster.
  async function showTodayTrack(id) {
    clearSelTrack();
    if (view !== "live" || isStale(pos.get(id))) return;   // yalnızca aktif araç
    let pts;
    try { pts = await fetchDay(id, 0); } catch (_) { return; }
    if (id !== selected || view !== "live" || !pts || !pts.length) return;
    const latlngs = pts.filter(p => p.lat != null).map(p => [p.lat, p.lon]);
    if (latlngs.length < 2) return;
    selTrackLayer = L.polyline(latlngs, { color: "#3b82f6", weight: 4, opacity: .8 }).addTo(map);
  }
  function clearSelTrack() { if (selTrackLayer) { map.removeLayer(selTrackLayer); selTrackLayer = null; } }
  function updateOverlay() {
    const v = vehicles.get(selected), p = pos.get(selected);
    $("selTitle").textContent = "#" + (v ? v.trackId : "?") + " · " + (v ? v.plate : "");
    $("selSub").textContent = [v && v.make, v && v.model].filter(Boolean).join(" ") || "Telefon GPS";
    $("selSpeed").textContent = p ? (p.speedKmh != null ? p.speedKmh : 0) : "0";
    $("selFuel").textContent = p && p.fuelPct != null ? p.fuelPct : "—";
    $("selAcc").textContent = p && p.accuracy != null ? Math.round(p.accuracy) : "—";
    // Geçmişte: trip tablosu boş olduğundan eco/skor/km'yi gerçek veriden hesapla
    // (km = günün track mesafesi, puanlar = o günkü ihlallerden). Canlıda trip cache.
    const useHist = view === "history" && histScore && selected === histVeh;
    const t = tripCache.get(selected);
    const eco = useHist ? histScore.ecoScore : (t && t.ecoScore != null ? t.ecoScore : null);
    const sc = useHist ? histScore.score : (t && t.score != null ? t.score : null);
    const di = useHist ? histScore.distanceKm : (t && t.distanceKm != null ? +t.distanceKm : null);
    $("selEco").textContent = eco != null ? eco : "—";
    $("selScore").textContent = sc != null ? sc : "—";
    $("selDist").textContent = di != null ? di.toFixed(1) : "—";
  }
  // Sürüş puanı + eco puanı, o günkü GERÇEK ihlallerden (araç ne yaptıysa ona göre).
  function scoreFromVios(vios, distKm) {
    let dPen = 0, ePen = 0;
    (vios || []).forEach(v => {
      const c = v.ruleCode || "";
      if (c.indexOf("SPEED") >= 0) { dPen += 12; ePen += 8; }          // hız aşımı
      else if (c.indexOf("HARSH") >= 0) { dPen += 12; ePen += 9; }     // sert fren/ivme
      else if (c.indexOf("IDLING") >= 0) { dPen += 3; ePen += 8; }     // rölanti → yakıt
      else if (c.indexOf("GEOFENCE") >= 0) { dPen += 8; ePen += 2; }
      else { dPen += 5; ePen += 4; }
    });
    return { score: Math.max(0, 100 - dPen), ecoScore: Math.max(0, 100 - ePen), distanceKm: distKm || 0 };
  }
  async function loadTrip(id) {
    if (tripCache.has(id)) { updateOverlay(); return; }
    try {
      const r = await fetch(`/api/v1/vehicles/${id}/trips?limit=1`, { headers: auth() });
      if (r.ok) { const arr = await r.json(); const t = arr && arr[0]; if (t) tripCache.set(id, t); }
    } catch (_) {}
    if (id === selected) updateOverlay();
  }
  window._deselect = () => select(null);

  function initSelActions() {
    $("selFollow").onclick = () => {
      if (selected == null) return;
      followId = (followId === selected) ? null : selected;
      $("selFollow").textContent = followId === selected ? "Takibi durdur" : "Bu aracı takip et";
      if (followId != null) { const p = pos.get(followId); if (p) map.setView([p.lat, p.lon], Math.max(map.getZoom(), 15)); }
    };
    $("selMsgBtn").onclick = () => { if (selected != null) openChat(selected); };
    if ($("selShareBtn")) $("selShareBtn").onclick = () => { if (selected != null) shareVehicle(selected); };
    if ($("selJobBtn")) $("selJobBtn").onclick = () => { if (selected != null) startJobAssign(selected); };
    if ($("plateToggle")) $("plateToggle").onclick = togglePlates;
  }

  // ── İhlal akışı ──────────────────────────────────────────────────────────
  async function seedViolations() {
    try {
      const r = await fetch("/api/v1/violations?limit=15", { headers: auth() });
      if (!r.ok) return;
      const page = await r.json(), items = (page && (page.items || page.content)) || [];
      items.slice().reverse().forEach(v => onViolation({ vehicleId: v.vehicleId, ruleCode: v.ruleCode || v.type,
        value: v.value, threshold: v.threshold, occurredAt: v.occurredAt }, false));
    } catch (_) {}
  }
  function onViolation(e, isLive) {
    alertCount++; $("statAlert").textContent = alertCount;
    const feed = $("vioFeed");
    if (!feed.querySelector(".vitem")) feed.innerHTML = "";
    const v = vehicles.get(e.vehicleId);
    const item = document.createElement("div");
    item.className = "vitem flex gap-2 items-start p-2 rounded-lg border-l-2 border-error" + (isLive ? " anim-in" : "");
    item.style.background = "rgba(147,0,10,.12)";
    const detail = vioDetail(e);
    const when = e.occurredAt ? new Date(e.occurredAt).toLocaleTimeString("tr-TR", { hour: "2-digit", minute: "2-digit" }) : "şimdi";
    item.innerHTML = `<div class="flex-1 min-w-0"><div class="text-label-sm font-bold text-on-surface truncate">${v ? "#" + v.trackId + " " + esc(v.plate) : "Araç"} · ${esc(ruleLabel(e.ruleCode))}</div>
      <div class="text-[10px] text-on-surface-variant">${detail} · ${when}</div></div>`;
    if (v) { item.style.cursor = "pointer"; item.onclick = () => { window.toggleView("live"); select(e.vehicleId); }; }
    if (isLive) feed.prepend(item); else feed.appendChild(item);
    while (feed.children.length > 30) feed.removeChild(feed.lastChild);
    recentVios.unshift({ vehicleId: e.vehicleId, ruleCode: e.ruleCode, value: e.value, threshold: e.threshold, occurredAt: e.occurredAt || new Date().toISOString() });
    while (recentVios.length > 15) recentVios.pop();
    if (view === "fleet") renderFleetAlerts();
  }

  // Sürücünün tek-tık yanıtı (1c). Operatörün kendi gönderdiği uyarı (TO_DRIVER) burada
  // tekrar gösterilmez; yalnızca sürücüden gelen (FROM_DRIVER) "yeni olay" sayılır.
  function onDriverMessage(e) {
    if (!e || e.direction !== "FROM_DRIVER") return;
    const feed = $("vioFeed");
    if (!feed.querySelector(".vitem")) feed.innerHTML = "";
    const v = vehicles.get(e.vehicleId);
    const when = e.at ? new Date(e.at).toLocaleTimeString("tr-TR", { hour: "2-digit", minute: "2-digit" }) : "şimdi";
    const item = document.createElement("div");
    item.className = "vitem flex gap-2 items-start p-2 rounded-lg border-l-2 anim-in";
    item.style.borderLeftColor = "#4edea3";
    item.style.background = "rgba(78,222,163,.10)";
    item.style.cursor = "pointer";
    item.innerHTML = `<span class="material-symbols-outlined text-primary text-body-md" style="font-variation-settings:'FILL' 1">chat</span>
      <div class="flex-1 min-w-0"><div class="text-label-sm font-bold text-on-surface truncate">${v ? "#" + v.trackId + " " + esc(v.plate) : "Araç"} · sürücü</div>
      <div class="text-[10px] text-on-surface-variant truncate">${esc(e.body)} · ${when}</div></div>`;
    item.onclick = () => { window.toggleView("live"); select(e.vehicleId); };
    feed.prepend(item);
    while (feed.children.length > 30) feed.removeChild(feed.lastChild);
    toast((v ? "#" + v.trackId + " " + esc(v.plate) : "Sürücü") + ": " + esc(e.body));
    if (chatVehicle === e.vehicleId && !$("chatPanel").classList.contains("hidden")) renderChatMsg("FROM_DRIVER", e.body, e.at, e.audio);
    loadInbox();     // sağ üst bildirim paneline de düşür (kalıcı)
    moveToBell();    // panel kapalıysa rozet + çan sallanır
  }

  // ── Gelen sürücü mesajları (admin bildirim paneli) ────────────────────────
  async function loadInbox() {
    try {
      const r = await fetch("/api/v1/inbox", { headers: auth() });
      if (!r.ok) return;
      const list = await r.json();
      inbox = list.filter(m => !inboxDismissed.has(m.id));   // silinmişleri gösterme
    } catch (_) {}
    renderBellPanel();
  }
  function dismissInbox(id) {
    inboxDismissed.add(id);
    try { localStorage.setItem("vts_inbox_dismissed", JSON.stringify([...inboxDismissed].slice(-500))); } catch (_) {}
    inbox = inbox.filter(m => m.id !== id);
    renderBellPanel();
  }

  // Kısa süreli, tıklanınca kapanan bildirim balonu (sürücü yanıtı için).
  let toastTimer = null;
  function toast(html, icon) {
    let el = $("ffToast");
    if (!el) {
      el = document.createElement("div");
      el.id = "ffToast";
      el.className = "fixed top-20 left-1/2 -translate-x-1/2 z-[70] glass-panel px-4 py-2.5 rounded-xl text-body-md text-on-surface anim-in flex items-center gap-2 cursor-pointer";
      el.style.borderLeft = "3px solid #4edea3";
      el.onclick = () => { el.style.display = "none"; };
      document.body.appendChild(el);
    }
    const ic = icon || "info";
    el.innerHTML = `<span class="material-symbols-outlined text-primary text-body-md" style="font-variation-settings:'FILL' 1">${esc(ic)}</span><span>${html}</span>`;
    el.classList.remove("toast-out");
    el.style.display = "flex";
    el.classList.remove("anim-in"); void el.offsetWidth; el.classList.add("anim-in");   // her çağrıda girişi yeniden tetikle
    if (toastTimer) clearTimeout(toastTimer);
    // 5 sn sonra yumuşak sön, sonra gizle (ani kaybolma yerine).
    toastTimer = setTimeout(() => {
      el.classList.add("toast-out");
      setTimeout(() => { if (el.classList.contains("toast-out")) el.style.display = "none"; }, 320);
    }, 5000);
  }

  // ── Genel duyuru (admin → tüm kullanıcılar, pub/sub) ──────────────────────
  // Akış: admin duyuru gönderir → backend /topic/broadcast'e yayınlar → her bağlı
  // panelde önce ORTADA üstten bir banner düşer (10 sn), sonra sağ üstteki çana
  // "gider" (rozet artar). Geçmiş duyurular çan panelinde kalır. Araç sohbetinden
  // (vehicle-messages) tamamen ayrıdır — ona dokunulmaz.
  function initBroadcast() {
    const composer = $("bcastComposer"), panel = $("bellPanel");
    const pop = el => { el.classList.remove("anim-pop"); void el.offsetWidth; el.classList.add("anim-pop"); };
    $("bcastComposeBtn").onclick = e => { e.stopPropagation(); panel.classList.add("hidden"); composer.classList.toggle("hidden");
      if (!composer.classList.contains("hidden")) { pop(composer); $("bcastBody").focus(); } };
    $("bcastClose").onclick = () => composer.classList.add("hidden");
    // Önem seçici (Bilgi/Acil) — segmented toggle.
    document.querySelectorAll(".bcast-sev").forEach(btn => btn.onclick = () => {
      bcastSeverity = btn.dataset.sev;
      document.querySelectorAll(".bcast-sev").forEach(b => {
        const on = b === btn, urgent = b.dataset.sev === "URGENT";
        b.classList.toggle("bg-primary", on && !urgent);
        b.classList.toggle("text-on-primary", on && !urgent);
        b.classList.toggle("bg-error", on && urgent);
        b.classList.toggle("text-on-error-container", on && urgent);
        b.classList.toggle("bg-white/5", !on);
        b.classList.toggle("text-on-surface-variant", !on);
        b.classList.toggle("border", !on);
        b.classList.toggle("border-white/10", !on);
      });
    });
    $("bcastSend").onclick = sendBroadcast;
    $("bcastBody").addEventListener("keydown", e => { if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) sendBroadcast(); });
    $("bellBtn").onclick = e => { e.stopPropagation(); composer.classList.add("hidden");
      panel.classList.toggle("hidden"); if (!panel.classList.contains("hidden")) pop(panel); };   // okundu = kullanıcı işaretler
    $("bellClear").onclick = markBroadcastsRead;
    // Panel/composer/QR dışına tıklayınca kapan.
    const qrPanel = $("pairPanel");
    document.addEventListener("click", e => {
      if (!panel.classList.contains("hidden") && !panel.contains(e.target) && e.target.closest("#bellBtn") == null) panel.classList.add("hidden");
      if (!composer.classList.contains("hidden") && !composer.contains(e.target) && e.target.closest("#bcastComposeBtn") == null) composer.classList.add("hidden");
      if (qrPanel && !qrPanel.classList.contains("hidden") && !qrPanel.contains(e.target) && e.target.closest("#pairBtn") == null) qrPanel.classList.add("hidden");
    });
    // Escape ile tüm açık panelleri kapat.
    document.addEventListener("keydown", e => {
      if (e.key !== "Escape") return;
      panel.classList.add("hidden");
      composer.classList.add("hidden");
      if (qrPanel) qrPanel.classList.add("hidden");
      const cp = $("chatPanel"); if (cp) cp.classList.add("hidden");
    });
  }

  async function loadBroadcasts() {
    try {
      const r = await fetch("/api/v1/broadcasts", { headers: auth() });
      if (!r.ok) return;
      const list = await r.json();   // en yeni önce
      broadcasts.length = 0;
      list.forEach(b => { if (b.id != null && bcastDismissed.has(b.id)) return; broadcasts.push(b); if (b.id != null) bcastSeen.add(b.id); });
    } catch (_) {}
    renderBellPanel();
  }

  async function sendBroadcast() {
    const title = $("bcastTitle").value.trim(), body = $("bcastBody").value.trim(), msg = $("bcastMsg");
    if (!body) { msg.textContent = "Mesaj boş olamaz."; return; }
    msg.textContent = "Gönderiliyor…";
    try {
      const r = await fetch("/api/v1/broadcasts", { method: "POST",
        headers: { ...auth(), "Content-Type": "application/json" },
        body: JSON.stringify({ title: title || null, body: body, severity: bcastSeverity }) });
      if (!r.ok) throw 0;
      $("bcastTitle").value = ""; $("bcastBody").value = ""; msg.textContent = "";
      $("bcastComposer").classList.add("hidden");   // görünen sonuç WS ile gelir (banner + çan)
    } catch (_) { msg.textContent = "Gönderilemedi."; }
  }

  // WS'ten gelen canlı duyuru: geçmişe ekle, panele yaz, banner kuyruğuna al.
  function onBroadcast(e) {
    if (!e || !e.body) return;
    if (e.id != null && bcastSeen.has(e.id)) return;   // idempotent (yeniden abone olunca tekrar gelebilir)
    if (e.id != null) bcastSeen.add(e.id);
    broadcasts.unshift(e);
    while (broadcasts.length > 50) broadcasts.pop();
    renderBellPanel();
    bannerQueue.push(e);
    pumpBanner();
  }

  // Ortadaki banner'ları sırayla göster (üst üste binmesin). Her biri 10 sn kalır,
  // sonra çana "gider": rozet artar + çan sallanır (panel açıksa okunmuş sayılır).
  function pumpBanner() {
    if (bannerBusy || !bannerQueue.length) return;
    bannerBusy = true;
    const e = bannerQueue.shift();
    showBanner(e, () => {
      moveToBell();
      bannerBusy = false;
      pumpBanner();
    });
  }

  function showBanner(e, onDone) {
    let el = $("bcastBanner");
    if (!el) {
      el = document.createElement("div");
      el.id = "bcastBanner";
      el.className = "fixed left-1/2 z-[80] w-[min(92vw,460px)] glass-panel rounded-2xl p-4 flex items-start gap-3 cursor-pointer";
      el.style.borderLeft = "3px solid #4edea3";
      el.style.transform = "translate(-50%,0)";
      el.style.transition = "top .5s cubic-bezier(.2,.7,.2,1), opacity .4s ease";
      document.body.appendChild(el);
    }
    if (el._timer) clearTimeout(el._timer);
    const urgent = e.severity === "URGENT";
    el.style.borderLeft = "3px solid " + (urgent ? "#ffb4ab" : "#4edea3");
    const accent = urgent ? "text-error" : "text-primary";
    const title = e.title ? `<div class="text-body-md font-bold text-on-surface mb-0.5">${esc(e.title)}</div>` : "";
    el.innerHTML =
      `<span class="material-symbols-outlined ${accent}" style="font-variation-settings:'FILL' 1">campaign</span>
       <div class="flex-1 min-w-0">
         <div class="text-label-sm uppercase tracking-widest ${accent} mb-1">${urgent ? "Acil duyuru" : "Genel duyuru"}</div>
         ${title}
         <div class="text-body-md text-on-surface" style="word-break:break-word">${esc(e.body)}</div>
         <div class="text-[10px] text-on-surface-variant mt-1">${esc(e.sender || "admin")}</div>
       </div>
       <span class="material-symbols-outlined text-on-surface-variant text-body-md">close</span>`;
    el.style.display = "flex";
    el.style.top = "-160px"; el.style.opacity = "0";
    let closed = false;
    const finish = () => { if (closed) return; closed = true; if (el._timer) clearTimeout(el._timer);
      el.style.top = "-160px"; el.style.opacity = "0";
      setTimeout(() => { el.style.display = "none"; onDone(); }, 420); };
    el.onclick = finish;
    // düşür (bir sonraki frame'de geçiş tetiklensin)
    requestAnimationFrame(() => requestAnimationFrame(() => { el.style.top = "5.5rem"; el.style.opacity = "1"; }));
    el._timer = setTimeout(finish, BANNER_MS);
  }

  // Okunmamış = notifRead'de olmayan (duyuru b:id / gelen mesaj d:id).
  function notifUnread() {
    let n = 0;
    broadcasts.forEach(b => { if (b.id != null && !notifRead.has("b:" + b.id)) n++; });
    inbox.forEach(m => { if (m.id != null && !notifRead.has("d:" + m.id)) n++; });
    return n;
  }
  function updateBellBadge() {
    const n = notifUnread(), badge = $("bellBadge"), hdr = $("bellUnread");
    if (badge) { if (n > 0) { badge.textContent = n > 99 ? "99+" : n; badge.classList.remove("hidden"); } else badge.classList.add("hidden"); }
    if (hdr) { if (n > 0) { hdr.textContent = n + " okunmamış"; hdr.classList.remove("hidden"); } else hdr.classList.add("hidden"); }
  }
  function moveToBell() {   // yeni bildirim geldi: rozeti güncelle + çanı salla
    updateBellBadge();
    const bell = $("bellIcon");
    if (bell) { bell.classList.remove("bell-shake"); void bell.offsetWidth; bell.classList.add("bell-shake"); }
  }
  function persistRead() { try { localStorage.setItem("vts_read", JSON.stringify([...notifRead].slice(-800))); } catch (_) {} }
  function markNotifRead(key) { notifRead.add(key); persistRead(); updateBellBadge(); renderBellPanel(); }
  function markBroadcastsRead() {   // "Tümü okundu"
    broadcasts.forEach(b => { if (b.id != null) notifRead.add("b:" + b.id); });
    inbox.forEach(m => { if (m.id != null) notifRead.add("d:" + m.id); });
    persistRead(); updateBellBadge(); renderBellPanel();
  }
  function dismissBroadcast(id) {   // duyuruyu panelden kaldır (kalıcı, DB'ye dokunmaz)
    bcastDismissed.add(id);
    try { localStorage.setItem("vts_bcast_dismissed", JSON.stringify([...bcastDismissed].slice(-500))); } catch (_) {}
    const i = broadcasts.findIndex(b => b.id === id); if (i >= 0) broadcasts.splice(i, 1);
    updateBellBadge(); renderBellPanel();
  }
  // Artık ilk açılışta otomatik "okundu" yapılmaz: bildirimler çerçeveli (okunmamış) gelir,
  // kullanıcı okundu'ya basınca çerçeve kalkar. Sadece rozeti/sayacı günceller.
  function seedReadOnce() { updateBellBadge(); }

  const bellWhen = at => at ? new Date(at).toLocaleString("tr-TR", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" }) : "";
  const bellDot = un => un ? '<span class="bell-dot"></span>' : "";
  // Okundu (yalnızca okunmamışsa) + sil butonları — modern ikon aksiyonları.
  function bellActs(key, delAttr, unread) {
    return `<div class="bell-actions">
      ${unread ? `<button class="bell-act" data-read="${key}" title="Okundu"><span class="material-symbols-outlined">done</span></button>` : ""}
      <button class="bell-act bell-act-del" ${delAttr} title="Sil"><span class="material-symbols-outlined">delete</span></button></div>`;
  }
  function bcastRow(b) {
    const urgent = b.severity === "URGENT", unread = b.id != null && !notifRead.has("b:" + b.id);
    const badge = urgent ? `<span class="bell-badge-urgent">Acil</span>` : "";
    return `<div class="bell-row${urgent ? " bell-urgent" : ""}${unread ? " bell-unread" : ""}">
      <div class="bell-ic ${urgent ? "bell-ic-urgent" : "bell-ic-bcast"}"><span class="material-symbols-outlined" style="font-variation-settings:'FILL' 1">campaign</span></div>
      <div class="bell-main"><div class="bell-title">${bellDot(unread)}${esc(b.title || "Duyuru")} ${badge}</div>
        <div class="bell-sub" style="white-space:normal">${esc(b.body)}</div>
        <div class="bell-meta">${esc(b.sender || "admin")} · ${bellWhen(b.at)}</div></div>
      ${bellActs("b:" + b.id, `data-delb="${b.id}"`, unread)}</div>`;
  }
  function driverRow(m) {
    const snippet = m.audio ? "🎤 Sesli mesaj" : esc((m.body || "").slice(0, 48));
    const unread = m.id != null && !notifRead.has("d:" + m.id);
    return `<div class="bell-swipe" data-idel="${m.id}">
      <div class="bell-swipe-del"><span class="material-symbols-outlined">delete</span></div>
      <div class="bell-swipe-card bell-row${unread ? " bell-unread" : ""}" data-iopen="${m.vehicleId}">
        <div class="bell-ic bell-ic-msg"><span class="material-symbols-outlined" style="font-variation-settings:'FILL' 1">chat</span></div>
        <div class="bell-main"><div class="bell-title">${bellDot(unread)}${esc(m.plate || "Araç")} <span class="bell-tag">sürücü</span></div>
          <div class="bell-sub">${snippet}</div>
          <div class="bell-meta">${bellWhen(m.at)}</div></div>
        ${bellActs("d:" + m.id, `data-deld="${m.id}"`, unread)}
      </div></div>`;
  }
  function renderBellPanel() {
    const list = $("bellList");
    if (!list) return;
    const items = broadcasts.map(b => ({ kind: "b", at: b.at || "", data: b }))
      .concat(inbox.map(m => ({ kind: "d", at: m.at || "", data: m })))
      .sort((a, b) => b.at.localeCompare(a.at));   // en yeni önce (duyuru + gelen mesaj karışık)
    if (!items.length) { list.innerHTML = '<div class="text-label-sm text-on-surface-variant text-center py-8">Henüz bildirim yok.</div>'; updateBellBadge(); return; }
    list.innerHTML = items.map(it => it.kind === "b" ? bcastRow(it.data) : driverRow(it.data)).join("");
    list.querySelectorAll("[data-read]").forEach(btn => btn.onclick = e => { e.stopPropagation(); markNotifRead(btn.dataset.read); });
    list.querySelectorAll("[data-delb]").forEach(btn => btn.onclick = e => { e.stopPropagation(); dismissBroadcast(+btn.dataset.delb); });
    list.querySelectorAll("[data-deld]").forEach(btn => btn.onclick = e => { e.stopPropagation(); dismissInbox(+btn.dataset.deld); });
    wireBellSwipe();
    updateBellBadge();
  }
  // Gelen mesaj satırında modern etkileşim: tıkla→sohbet, kaydır→sil (2 aşama: 1. kaydırış
  // "Sil"i açar, 2. kaydırış siler; ✕ masaüstü için).
  function wireBellSwipe() {
    document.querySelectorAll("#bellList .bell-swipe").forEach(sw => {
      const card = sw.querySelector(".bell-swipe-card"), id = +sw.dataset.idel;
      let sx = 0, dx = 0, dragging = false, armed = false, moved = false;
      const reset = () => { armed = false; card.classList.remove("armed"); card.style.transform = ""; };
      sw.querySelector(".bell-swipe-del").onclick = () => dismissInbox(id);
      card.addEventListener("touchstart", e => { if (e.touches.length !== 1) return; dragging = true; moved = false; sx = e.touches[0].clientX; card.style.transition = "none"; }, { passive: true });
      card.addEventListener("touchmove", e => { if (!dragging) return; dx = e.touches[0].clientX - sx; if (Math.abs(dx) > 6) moved = true;
        card.style.transform = "translateX(" + Math.min(0, Math.max(-140, (armed ? -84 : 0) + dx)) + "px)"; }, { passive: true });
      card.addEventListener("touchend", () => { if (!dragging) return; dragging = false; card.style.transition = "";
        if (armed) { if (dx < -20) return dismissInbox(id); if (dx > 20) return reset(); card.style.transform = "translateX(-84px)"; return; }
        if (dx < -44) { armed = true; card.classList.add("armed"); card.style.transform = ""; } else reset(); }, { passive: true });
      card.addEventListener("click", () => { if (moved) { moved = false; return; } if (armed) return reset();
        $("bellPanel").classList.add("hidden"); openChat(+card.dataset.iopen); });
    });
  }

  // ── Haritada gör: canlı yoksa son bilinen konumu göster ───────────────────
  async function showOnMap(id) {
    toggleView("live"); select(id);
    let p = pos.get(id);
    if (p && p.lat != null) { map.setView([p.lat, p.lon], Math.max(map.getZoom(), 14)); return; }
    try {
      const r = await fetch(`/api/v1/vehicles/${id}/last-position`, { headers: auth() });
      if (!r.ok) { toast("Bu araç için konum kaydı yok."); return; }
      p = await r.json();
      pos.set(id, p);
      drawMarker(p);
      if (cluster) cluster.refreshClusters();
      map.setView([p.lat, p.lon], Math.max(map.getZoom(), 14));
      updateOverlay();
    } catch (_) {}
  }

  // ── Şifre göster (göz efekti) ─────────────────────────────────────────────
  async function revealPassword(id) {
    const line = document.querySelector(`[data-pwline="${id}"]`);
    if (!line) return;
    if (!line.classList.contains("hidden")) { line.classList.add("hidden"); return; }   // tekrar tık → gizle
    try {
      const r = await fetch(`/api/v1/vehicles/${id}/driver-credential`, { headers: auth() });
      const d = r.ok ? await r.json() : null;
      line.querySelector(".pwval").textContent = (d && d.password) ? d.password : "(atanmamış)";
      line.classList.remove("hidden");
    } catch (_) {}
  }

  // ── Destek sohbeti (admin ↔ sürücü, sağ alt) ──────────────────────────────
  let chatVehicle = null;
  function initChat() {
    $("chatClose").onclick = () => $("chatPanel").classList.add("hidden");
    $("chatSend").onclick = sendChat;
    $("chatMic").onclick = () => toggleRecord($("chatMic"), sendChatAudio);
    $("chatInput").addEventListener("keydown", e => { if (e.key === "Enter") sendChat(); });
  }
  function openChat(id) {
    chatVehicle = id;
    const v = vehicles.get(id);
    $("chatTitle").textContent = v ? ("#" + v.trackId + " · " + v.plate) : "Destek";
    $("selOverlay").classList.add("hidden");
    $("chatPanel").classList.remove("hidden");
    $("chatBody").innerHTML = '<div class="text-label-sm text-on-surface-variant text-center py-4">Yükleniyor…</div>';
    loadChat(id);
    setTimeout(() => $("chatInput").focus(), 100);
  }
  async function loadChat(id) {
    try {
      const r = await fetch(`/api/v1/vehicles/${id}/messages`, { headers: auth() });
      if (id !== chatVehicle) return;                 // bu arada başka araca geçildi → eski cevabı çizme
      const list = r.ok ? await r.json() : [];
      if (id !== chatVehicle) return;                 // (await json sonrası tekrar kontrol)
      $("chatBody").innerHTML = "";
      if (!list.length) { $("chatBody").innerHTML = '<div class="text-label-sm text-on-surface-variant text-center py-4">Henüz mesaj yok. İlk mesajı sen yaz.</div>'; return; }
      list.forEach(m => renderChatMsg(m.direction, m.body, m.at, m.audio));
      const b = $("chatBody"); b.scrollTop = b.scrollHeight;
    } catch (_) {}
  }
  function renderChatMsg(direction, body, at, audio) {
    const wrap = $("chatBody"); const ph = wrap.querySelector(".text-center"); if (ph) ph.remove();
    const mine = direction !== "FROM_DRIVER";   // admin (TO_DRIVER) sağda, sürücü solda
    const time = at ? new Date(at).toLocaleString("tr-TR", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" }) : "";
    const el = document.createElement("div");
    el.className = "max-w-[82%] rounded-xl px-3 py-2 " + (mine ? "self-end" : "self-start");
    el.style.background = mine ? "rgba(78,222,163,.16)" : "rgba(255,255,255,.06)";
    if (!mine) el.style.borderLeft = "2px solid #4edea3";
    const content = document.createElement("div"); content.className = "text-body-md text-on-surface"; content.style.wordBreak = "break-word";
    if (audio) content.appendChild(audioButton(audio)); else content.textContent = body || "";
    const meta = document.createElement("div"); meta.className = "text-[10px] text-on-surface-variant mt-0.5" + (mine ? " text-right" : "");
    meta.textContent = (mine ? "Merkez · " : "Sürücü · ") + time;
    el.appendChild(content); el.appendChild(meta);
    wrap.appendChild(el);
    wrap.scrollTop = wrap.scrollHeight;
  }
  function audioButton(ref) {
    const a = document.createElement("audio");
    a.controls = true; a.preload = "metadata"; a.src = "/api/v1/track/audio/" + ref;
    a.style.maxWidth = "210px"; a.style.height = "40px"; a.style.display = "block";
    return a;
  }
  // Tek tık başlat / durdur ses kaydı (MediaRecorder), 60 sn üst sınır.
  var mediaRec = null, recChunks = [];
  function toggleRecord(btn, onDone) {
    if (mediaRec && mediaRec.state === "recording") { mediaRec.stop(); return; }
    if (!navigator.mediaDevices || !window.MediaRecorder) { alert("Bu tarayıcı ses kaydını desteklemiyor."); return; }
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
  function sendChatAudio(blob) {
    if (chatVehicle == null) return;
    var fd = new FormData(); fd.append("file", blob, "voice.webm");
    fetch("/api/v1/vehicles/" + chatVehicle + "/messages/audio", { method: "POST", headers: auth(), body: fd })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) { if (d) renderChatMsg("TO_DRIVER", "🎤 Sesli mesaj", d.at, d.audio); else alert("Ses gönderilemedi."); })
      .catch(function () { alert("Ses gönderilemedi."); });
  }
  async function sendChat() {
    if (chatVehicle == null) return;
    const inp = $("chatInput"), body = inp.value.trim();
    if (!body) return;
    inp.value = "";
    try {
      const r = await fetch(`/api/v1/vehicles/${chatVehicle}/messages`, { method: "POST",
        headers: { ...auth(), "Content-Type": "application/json" }, body: JSON.stringify({ category: "MESAJ", body: body }) });
      if (!r.ok) throw 0;
      renderChatMsg("TO_DRIVER", body, new Date().toISOString());
    } catch (_) { inp.value = body; alert("Mesaj gönderilemedi."); }
  }

  // ── Filo (Araç Listesi) ──────────────────────────────────────────────────
  function initFleet() {
    $("fleetFilters").querySelectorAll("button").forEach(b => b.onclick = () => {
      fleetFilter = b.dataset.f;
      $("fleetFilters").querySelectorAll("button").forEach(x => {
        const on = x === b;
        x.classList.toggle("bg-primary", on); x.classList.toggle("text-on-primary", on); x.classList.toggle("font-bold", on);
        x.classList.toggle("text-on-surface-variant", !on); x.classList.toggle("font-medium", !on);
      });
      renderFleet();
    });
    const nb = $("fleetNewBtn");
    if (nb) nb.onclick = () => { const f = $("fleetNewForm"); f.classList.toggle("hidden"); if (!f.classList.contains("hidden")) $("nvPlate").focus(); };
    if ($("nvCreate")) $("nvCreate").onclick = createVehicle;
    if ($("nvPlate")) $("nvPlate").addEventListener("keydown", e => { if (e.key === "Enter") createVehicle(); });
    if ($("fleetSearch")) $("fleetSearch").oninput = renderFleet;   // plakaya göre anında süz
    if ($("fleetReportBtn")) $("fleetReportBtn").onclick = exportFleetReport;
  }

  // Tek tık filo durum raporu (CSV): anlık snapshot — ekstra API çağrısı yok.
  function exportFleetReport() {
    const rows = [["Plaka", "Durum", "Hiz_kmh", "Yakit_%", "Enlem", "Boylam", "Son_gorulme"]];
    [...vehicles.values()].forEach(v => {
      const p = pos.get(v.id), st = stateOf(v.id);
      rows.push([v.plate, st.label, p && p.speedKmh != null ? p.speedKmh : "", p && p.fuelPct != null ? p.fuelPct : "",
        p && p.lat != null ? p.lat.toFixed(5) : "", p && p.lon != null ? p.lon.toFixed(5) : "",
        p && p.ts ? new Date(p.ts).toLocaleString("tr-TR") : ""]);
    });
    const csv = rows.map(r => r.map(c => `"${String(c).replace(/"/g, '""')}"`).join(",")).join("\n");
    const url = URL.createObjectURL(new Blob(["﻿" + csv], { type: "text/csv" }));
    const a = document.createElement("a");
    a.href = url; a.download = `filo-durum-${new Date().toISOString().slice(0, 10)}.csv`; a.click();
    URL.revokeObjectURL(url);
    toast(`Filo raporu indirildi · ${vehicles.size} araç`, "download");
  }

  // Admin yeni araç oluşturur (sürücüler oluşturamaz — yalnızca adminin araçlarına girer).
  async function createVehicle() {
    const plate = $("nvPlate").value.trim(), make = $("nvMake").value.trim(), model = $("nvModel").value.trim(), msg = $("nvMsg");
    if (!plate) { msg.textContent = "Plaka gerekli."; return; }
    msg.textContent = "Oluşturuluyor…";
    try {
      const r = await fetch("/api/v1/vehicles", { method: "POST",
        headers: { ...auth(), "Content-Type": "application/json" },
        body: JSON.stringify({ plate: plate, make: make || null, model: model || null, type: "CAR" }) });
      if (!r.ok) { msg.textContent = "Oluşturulamadı (plaka tekil olmalı)."; return; }
      $("nvPlate").value = ""; $("nvMake").value = ""; $("nvModel").value = "";
      $("fleetNewForm").classList.add("hidden"); msg.textContent = "";
      await loadVehicles(); renderFleet();
    } catch (_) { msg.textContent = "Oluşturulamadı."; }
  }

  // Filodan araca sürücü şifresi atar (POST /vehicles/{id}/driver-credential).
  async function setVehiclePassword(id) {
    const v = vehicles.get(id);
    const pw = prompt((v ? v.plate : "Araç") + " için sürücü şifresi (en az 4):");
    if (pw == null) return;
    if (pw.trim().length < 4) { alert("Şifre en az 4 karakter olmalı."); return; }
    try {
      const r = await fetch(`/api/v1/vehicles/${id}/driver-credential`, { method: "POST",
        headers: { ...auth(), "Content-Type": "application/json" }, body: JSON.stringify({ password: pw.trim() }) });
      if (!r.ok) throw 0;
      toast((v ? v.plate : "Araç") + " için şifre belirlendi.", "key");
    } catch (_) { alert("Şifre belirlenemedi."); }
  }

  // Aracı ve ona bağlı tüm kayıtları kalıcı siler (onay ister).
  async function deleteVehicle(id) {
    const v = vehicles.get(id);
    var ok = confirm((v ? v.plate + " — " : "") + "bu aracı kalıcı olarak silmek istediğinize emin misiniz?\n\n" +
      "Araca ait tüm kayıtlar (konum geçmişi, mesajlar, sürücü şifresi) geri alınamaz şekilde silinir.");
    if (!ok) return;
    try {
      const r = await fetch(`/api/v1/vehicles/${id}`, { method: "DELETE", headers: auth() });
      if (!r.ok) throw 0;
      if (selected === id) select(null);
      vehicles.delete(id); pos.delete(id);
      const m = markers.get(id); if (m && cluster) { cluster.removeLayer(m); markers.delete(id); }
      toast((v ? v.plate : "Araç") + " kalıcı olarak silindi.", "delete_forever");
      await loadVehicles(); renderFleet();
    } catch (_) { alert("Araç silinemedi."); }
  }
  async function loadDashboard() {
    try { const r = await fetch("/api/v1/dashboard/summary", { headers: auth() }); if (r.ok) dash = await r.json(); } catch (_) {}
    updateFleetHealth();
  }

  // Günlük özet bildirimi: gün içinde bir kez, mevcut toast() bildirimiyle.
  function maybeDailySummary() {
    if (!vehicles.size) return;
    const key = "vts_daily_summary", today = new Date().toISOString().slice(0, 10);
    if (localStorage.getItem(key) === today) return;
    localStorage.setItem(key, today);
    const total = vehicles.size, live = liveCount();
    const vio = dash && dash.violations24h != null ? dash.violations24h : recentVios.length;
    toast(`Günlük özet · ${live}/${total} araç canlı · ${vio} ihlal (24s)`, "calendar_today");
  }
  function updateFleetHealth() {
    if (!$("fhRatio")) return;
    const total = vehicles.size, live = liveCount();
    const ratio = total ? Math.round(live / total * 100) : 0;   // sayfadaki kartlarla tutarlı: canlı/toplam
    $("fhRatio").textContent = ratio + "%"; $("fhBar").style.width = ratio + "%";
    $("fhTrips").textContent = dash ? dash.ongoingTrips : "—";
    $("fhVio").textContent = dash ? dash.violations24h : recentVios.length;
    $("fhDrivers").textContent = dash ? dash.drivers : "—";
    $("fhLive").textContent = live;
  }
  function fleetCard(id) {
    const v = vehicles.get(id), p = pos.get(id), st = stateOf(id), c = st.color;
    const model = esc([v.make, v.model].filter(Boolean).join(" ") || "Telefon GPS");
    const speed = p && p.speedKmh != null ? p.speedKmh : "—";
    const fuel = p && p.fuelPct != null ? p.fuelPct : null;
    const loc = p && p.lat != null ? p.lat.toFixed(4) + ", " + p.lon.toFixed(4) : "Konum bekleniyor";
    const seen = relTime(p && p.ts);
    return `<div class="fleet-card glass-panel p-5 rounded-2xl relative overflow-hidden" data-fid="${id}">
      <div class="fglow absolute top-0 right-0 w-28 h-28 rounded-full blur-3xl -mr-14 -mt-14" style="background:${c}22"></div>
      <div class="flex justify-between items-start mb-4 relative">
        <div class="min-w-0">
          <span class="text-label-sm font-bold text-primary block">#${v.trackId}</span>
          <h3 class="font-title-md text-on-surface truncate">${esc(v.plate)}</h3>
          <p class="text-label-sm text-on-surface-variant truncate">${model}</p>
        </div>
        <div class="flex flex-col items-end gap-1.5 flex-none">
          <span class="fchip px-3 py-1 rounded-full text-label-sm font-bold border inline-flex items-center gap-1.5" style="color:${c};border-color:${c}55;background:${c}1a">
            <span class="fdot w-1.5 h-1.5 rounded-full" style="background:${c}"></span><span class="flabel">${st.label}</span></span>
          <span class="fspeed text-label-sm text-on-surface-variant">${speed} km/s</span>
        </div>
      </div>
      <div class="ffuel mb-4 relative ${fuel == null ? "hidden" : ""}">
        <div class="flex justify-between text-[11px] text-on-surface-variant mb-1"><span>Yakıt</span><span class="ffueltxt">${fuel != null ? fuel : 0}%</span></div>
        <div class="w-full h-1.5 bg-white/5 rounded-full overflow-hidden"><div class="ffuelbar h-full rounded-full transition-all duration-500" style="width:${fuel != null ? fuel : 0}%;background:${fuel != null && fuel < 20 ? "#ffb4ab" : "#4edea3"}"></div></div></div>
      <div class="flex items-start gap-2 text-on-surface-variant mb-4 relative">
        <span class="material-symbols-outlined text-primary text-body-md">location_on</span>
        <p class="floc text-label-sm leading-tight">${loc} · ${seen}</p>
      </div>
      <div class="relative space-y-2">
        <button data-map="${id}" class="w-full py-2 bg-white/5 hover:bg-white/10 border border-white/10 rounded-xl text-label-sm font-bold text-on-surface active:scale-95 transition-all flex items-center justify-center gap-1"><span class="material-symbols-outlined text-body-md">map</span> Haritada gör</button>
        <div class="flex gap-2">
          <button data-msg="${id}" title="Sürücü ile mesajlaş" class="flex-1 flex items-center justify-center py-2 bg-white/5 hover:bg-white/10 rounded-xl border border-white/10 text-on-surface active:scale-95"><span class="material-symbols-outlined text-body-md">chat</span></button>
          <button data-pw="${id}" title="Sürücü şifresi ata" class="flex-1 flex items-center justify-center py-2 bg-white/5 hover:bg-white/10 rounded-xl border border-white/10 text-on-surface active:scale-95"><span class="material-symbols-outlined text-body-md">key</span></button>
          <button data-eye="${id}" title="Şifreyi göster" class="flex-1 flex items-center justify-center py-2 bg-white/5 hover:bg-white/10 rounded-xl border border-white/10 text-on-surface active:scale-95"><span class="material-symbols-outlined text-body-md">visibility</span></button>
          <button data-del="${id}" title="Aracı kalıcı sil" class="flex-1 flex items-center justify-center py-2 bg-error/10 hover:bg-error/20 rounded-xl border border-error/20 text-error active:scale-95"><span class="material-symbols-outlined text-body-md">delete</span></button>
        </div>
        <div data-pwline="${id}" class="hidden text-label-sm text-on-surface bg-black/20 rounded-lg px-3 py-2 border border-white/5">Sürücü şifresi: <b class="text-primary pwval">••••</b></div>
      </div>
    </div>`;
  }
  // Filtre + arama sonucu gösterilecek araç id'leri (renderFleet ve yerinde
  // güncelleme aynı mantığı paylaşsın diye tek yerde).
  function fleetFiltered() {
    const q = ($("fleetSearch") ? $("fleetSearch").value : "").trim().toLocaleLowerCase("tr");
    return [...vehicles.keys()].filter(id => {
      if (q) { const v = vehicles.get(id); if (!v || !(v.plate || "").toLocaleLowerCase("tr").includes(q)) return false; }
      if (fleetFilter === "all") return true;
      const k = stateOf(id).key;
      if (fleetFilter === "move") return k === "move" || k === "vio";  // ihlal = hızlı giden = harekette
      return k === fleetFilter;
    });
  }
  // Kartların hangi araçlar/sırayla dizildiğinin imzası: değişmediyse DOM'u
  // yeniden kurmayız (yalnızca yerinde güncelleriz) → 5 sn'de bir titreme olmaz.
  function renderFleet() {
    const grid = $("fleetGrid"); if (!grid) return;
    const filtered = fleetFiltered();
    fleetSig = filtered.join(",");
    $("fleetCount").textContent = vehicles.size + " araç yönetiliyor" + ((fleetFilter !== "all" || ($("fleetSearch") && $("fleetSearch").value.trim())) ? " · " + filtered.length + " gösteriliyor" : "");
    $("fleetEmpty").classList.toggle("hidden", vehicles.size > 0);
    grid.innerHTML = filtered.map(fleetCard).join("");
    grid.querySelectorAll("[data-map]").forEach(b => b.onclick = () => showOnMap(+b.dataset.map));
    grid.querySelectorAll("[data-msg]").forEach(b => b.onclick = () => openChat(+b.dataset.msg));
    grid.querySelectorAll("[data-pw]").forEach(b => b.onclick = () => setVehiclePassword(+b.dataset.pw));
    grid.querySelectorAll("[data-eye]").forEach(b => b.onclick = () => revealPassword(+b.dataset.eye));
    grid.querySelectorAll("[data-del]").forEach(b => b.onclick = () => deleteVehicle(+b.dataset.del));
    renderFleetAlerts();
    updateFleetHealth();
  }
  // 5 sn'lik veri yenilemesi: DOM'u yeniden KURMADAN mevcut kartların dinamik
  // alanlarını (durum çipi, hız, yakıt, konum) yerinde günceller. Yapısal değişiklik
  // (bir araç filtre kategorisi değiştirip listeye girip çıkması) varsa tam çizer.
  function refreshFleetCards() {
    const grid = $("fleetGrid"); if (!grid) return;
    const filtered = fleetFiltered();
    if (filtered.join(",") !== fleetSig) { renderFleet(); return; }   // yapı değişti → tam çiz
    filtered.forEach(id => {
      const card = grid.querySelector(`[data-fid="${id}"]`); if (!card) return;
      const p = pos.get(id), st = stateOf(id), c = st.color;
      const speed = p && p.speedKmh != null ? p.speedKmh : "—";
      const fuel = p && p.fuelPct != null ? p.fuelPct : null;
      const loc = p && p.lat != null ? p.lat.toFixed(4) + ", " + p.lon.toFixed(4) : "Konum bekleniyor";
      const glow = card.querySelector(".fglow"); if (glow) glow.style.background = c + "22";
      const chip = card.querySelector(".fchip");
      if (chip) { chip.style.color = c; chip.style.borderColor = c + "55"; chip.style.background = c + "1a"; }
      const dot = card.querySelector(".fdot"); if (dot) dot.style.background = c;
      const label = card.querySelector(".flabel"); if (label) label.textContent = st.label;
      const sp = card.querySelector(".fspeed"); if (sp) sp.textContent = speed + " km/s";
      const fu = card.querySelector(".ffuel");
      if (fu) {
        fu.classList.toggle("hidden", fuel == null);
        if (fuel != null) {
          const bar = fu.querySelector(".ffuelbar"); if (bar) { bar.style.width = fuel + "%"; bar.style.background = fuel < 20 ? "#ffb4ab" : "#4edea3"; }
          const txt = fu.querySelector(".ffueltxt"); if (txt) txt.textContent = fuel + "%";
        }
      }
      const lc = card.querySelector(".floc"); if (lc) lc.textContent = loc + " · " + relTime(p && p.ts);
    });
    updateFleetHealth();
    renderFleetAlerts();
  }
  function renderFleetAlerts() {
    const el = $("fleetAlerts"); if (!el) return;
    if (!recentVios.length) { el.innerHTML = '<div class="text-label-sm text-on-surface-variant">Henüz ihlal yok.</div>'; return; }
    el.innerHTML = recentVios.slice(0, 6).map(e => {
      const v = vehicles.get(e.vehicleId);
      const detail = vioDetail(e);
      const when = e.occurredAt ? new Date(e.occurredAt).toLocaleTimeString("tr-TR", { hour: "2-digit", minute: "2-digit" }) : "şimdi";
      return `<div class="flex gap-3 items-start border-l-2 border-error pl-3"><div class="flex-1 min-w-0">
        <p class="text-body-md font-medium text-on-surface truncate">${esc(ruleLabel(e.ruleCode))}</p>
        <p class="text-label-sm text-on-surface-variant truncate">${v ? "#" + v.trackId + " " + esc(v.plate) : "Araç"} · ${detail}</p></div>
        <span class="text-label-sm text-outline flex-none">${when}</span></div>`;
    }).join("");
  }

  // ── Geçmiş rota (gün seçici + playback) ──────────────────────────────────
  let histLayer = null, histVeh = null, histDay = 0, histPrev = null, histPts = [];
  let histVios = [], speedVios = [];   // 6b: seçili araç+günün gerçek ihlalleri / hız ihlalleri
  let histScore = null;                // {score, ecoScore, distanceKm} — o günün ihlallerinden
  function currentDay() { const el = $("daySelect"); return el ? (parseInt(el.value, 10) || 0) : 0; }
  function dayLabel(d) { return new Date(Date.now() - d * 86400000).toLocaleDateString("tr-TR", { day: "2-digit", month: "short", weekday: "short" }); }
  function initDaySelect() {
    const sel = $("daySelect");
    for (let d = 0; d <= 6; d++) { const o = document.createElement("option"); o.value = String(d); o.textContent = d === 0 ? "Bugün · " + dayLabel(0) : dayLabel(d); sel.appendChild(o); }
    sel.onchange = () => { const vid = +$("histVehicle").value; if (vid) loadHistory(vid, currentDay(), false); };
    $("histVehicle").onchange = () => { const vid = +$("histVehicle").value; if (vid) { select(vid); loadHistory(vid, currentDay(), true); } };
    $("histExport").onclick = exportCsv;
  }
  function histAdd(prev, cur) {
    if (!histLayer) return;
    const vio = speedVioAt(cur.ts), red = !!vio;   // 6b: renk gerçek backend hız ihlaline göre
    L.circleMarker([cur.lat, cur.lon], { radius: red ? 4 : 2.5, color: red ? "#ff4d4d" : "#3b82f6", weight: 1, fillColor: red ? "#ff4d4d" : "#3b82f6", fillOpacity: .85, interactive: red }).addTo(histLayer);
    if (prev) {
      const seg = L.polyline([[prev.lat, prev.lon], [cur.lat, cur.lon]], red ? { color: "#ff4d4d", weight: 5, opacity: .95 } : { color: "#3b82f6", weight: 3, opacity: .7 }).addTo(histLayer);
      if (red) seg.bindTooltip(speedVioLabel(vio), { sticky: true });
    }
  }
  // Bu noktanın zamanına (±RED_WINDOW_MS) denk gelen gerçek hız ihlali, yoksa null.
  function speedVioAt(tsStr) {
    const t = Date.parse(tsStr);
    if (isNaN(t)) return null;
    for (let i = 0; i < speedVios.length; i++) {
      if (Math.abs(t - speedVios[i].ms) <= RED_WINDOW_MS) return speedVios[i];
    }
    return null;
  }
  function speedVioLabel(v) {
    const rule = ruleLabel(v.ruleCode);
    if (v.value == null) return "⚠ " + rule;
    return "⚠ " + rule + " — " + Math.round(v.value) + " km/s" + (v.threshold != null ? " · limit " + Math.round(v.threshold) : "");
  }
  function haversine(a, b) {
    const R = 6371, dLat = (b.lat - a.lat) * Math.PI / 180, dLon = (b.lon - a.lon) * Math.PI / 180;
    const s = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * Math.PI / 180) * Math.cos(b.lat * Math.PI / 180) * Math.sin(dLon / 2) ** 2;
    return 2 * R * Math.asin(Math.min(1, Math.sqrt(s)));
  }
  // Duraklar: hız güvenilmez (telefon GPS'i null verebilir), o yüzden KONUM+SÜRE ile
  // tespit et — araç ~STOP_RADIUS_M içinde MIN_STOP_SEC boyunca kalıyorsa durak sayılır.
  function findStops(pts) {
    const stops = []; let i = 0;
    while (i < pts.length) {
      let j = i;
      while (j + 1 < pts.length && haversine(pts[i], pts[j + 1]) * 1000 <= STOP_RADIUS_M) j++;
      const dur = (new Date(pts[j].ts) - new Date(pts[i].ts)) / 1000;
      if (j > i && dur >= MIN_STOP_SEC) { stops.push({ mid: pts[Math.floor((i + j) / 2)], mins: Math.max(1, Math.round(dur / 60)) }); i = j + 1; }
      else i++;
    }
    return stops;
  }
  function summarize(pts) {
    let dist = 0, prev = null;
    pts.forEach(p => { if (prev) dist += haversine(prev, p); prev = p; });
    return { dist, stops: findStops(pts).length };
  }
  function markStops(pts) {
    findStops(pts).forEach(s => {
      L.circleMarker([s.mid.lat, s.mid.lon], { radius: 7, color: "#0f766e", weight: 2, fillColor: "#4edea3", fillOpacity: .9 })
        .bindTooltip("🅿️ Durak · " + s.mins + " dk", { direction: "top" }).addTo(histLayer);
    });
  }
  async function fetchDay(id, d) { const r = await fetch(`/api/v1/vehicles/${id}/track?daysAgo=${d}`, { headers: auth() }); return r.ok ? await r.json() : []; }
  // 6b: gösterilen noktaların zaman aralığındaki gerçek ihlaller (mevcut /violations ucundan).
  async function fetchDayViolations(id, pts) {
    const times = pts.map(p => Date.parse(p.ts)).filter(t => !isNaN(t));
    if (!times.length) return [];
    const from = new Date(Math.min(...times) - 3e5).toISOString();
    const to = new Date(Math.max(...times) + 3e5).toISOString();
    try {
      const r = await fetch(`/api/v1/violations?vehicleId=${id}&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&limit=200`, { headers: auth() });
      if (!r.ok) return [];
      const page = await r.json();
      const items = (page && (page.items || page.content)) || [];
      return items.map(v => ({ ms: Date.parse(v.occurredAt), ruleCode: v.ruleCode || v.type, value: v.value, threshold: v.threshold }));
    } catch (_) { return []; }
  }
  async function loadHistory(id, day, autoFind) {
    clearHistory(); histVeh = id; histDay = day;
    let pts = await fetchDay(id, day);
    if (autoFind && !pts.length) { for (let d = day + 1; d <= 6; d++) { const p2 = await fetchDay(id, d); if (p2.length) { day = d; histDay = d; pts = p2; $("daySelect").value = String(d); break; } } }
    if (id !== histVeh) return;
    histVios = await fetchDayViolations(id, pts);            // 6b: gerçek ihlaller (çizimden önce)
    if (id !== histVeh) return;                              // await sırasında seçim değişebilir
    speedVios = histVios.filter(v => (v.ruleCode || "").indexOf("SPEED") >= 0);
    histLayer = L.layerGroup().addTo(map);
    histPts = pts;
    let prev = null; pts.forEach(p => { histAdd(prev, p); prev = p; }); histPrev = prev;
    markStops(pts);
    if (pts.length) map.fitBounds(L.latLngBounds(pts.map(p => [p.lat, p.lon])).pad(0.2));
    const v = vehicles.get(id), s = summarize(pts), realVio = histVios.length;
    histScore = scoreFromVios(histVios, s.dist);   // km + puanlar (ihlallerden) — overlay bunu gösterir
    $("sumDistance").textContent = s.dist.toFixed(1);
    $("sumViolations").textContent = String(realVio).padStart(2, "0");
    $("sumStops").textContent = String(s.stops).padStart(2, "0");
    $("histInfo").innerHTML = `<b class="text-on-surface">#${v ? v.trackId : "?"} ${v ? esc(v.plate) : ""}</b><br>${dayLabel(day)} · <b>${pts.length}</b> nokta` +
      (pts.length ? `<br><span class="text-primary">${s.dist.toFixed(1)} km</span> · ${realVio} ihlal · ${s.stops} durak · Skor <b>${histScore.score}</b> · Eco <b>${histScore.ecoScore}</b>` : '<br><span class="text-error">Bu gün için kayıt yok.</span>');
    if (selected === id) updateOverlay();   // seçili aracın eco/skor/km kutularını doldur
    resetPlayback();
  }
  function extendHistory(p) {
    if (histDay !== 0 || p.vehicleId !== histVeh || !histLayer) return;
    const cur = { lat: p.lat, lon: p.lon, speedKmh: p.speedKmh, ts: p.ts };
    if (histPrev && histPrev.lat === cur.lat && histPrev.lon === cur.lon) return;
    histAdd(histPrev, cur); histPrev = cur; histPts.push(cur);
  }
  function clearHistory() { stopPlayback(); if (histLayer) map.removeLayer(histLayer); histLayer = null; histVeh = null; histPrev = null; histPts = []; histVios = []; speedVios = []; histScore = null; }

  function exportCsv() {
    if (!histPts.length) return;
    const v = vehicles.get(histVeh);
    const rows = [["ts", "lat", "lon", "speedKmh"]].concat(histPts.map(p => [p.ts, p.lat, p.lon, p.speedKmh == null ? "" : p.speedKmh]));
    const csv = rows.map(r => r.join(",")).join("\n");
    const url = URL.createObjectURL(new Blob([csv], { type: "text/csv" }));
    const a = document.createElement("a");
    a.href = url; a.download = `fleetflow-${v ? v.plate.replace(/\s+/g, "") : histVeh}-${dayLabel(histDay).replace(/\s+/g, "")}.csv`;
    a.click(); URL.revokeObjectURL(url);
  }

  // ── Playback (geçmiş rota oynatma) ───────────────────────────────────────
  let pbMarker = null, pbIndex = 0, pbTimer = null, pbPlaying = false, pbSpeed = 1;
  function initPlayback() {
    $("pbPlay").onclick = () => pbPlaying ? stopPlayback() : playPlayback();
    $("pbStart").onclick = () => { pbIndex = 0; renderPb(); };
    $("pbEnd").onclick = () => { pbIndex = Math.max(0, histPts.length - 1); renderPb(); };
    $("pbSpeed").onchange = () => { pbSpeed = +$("pbSpeed").value || 1; if (pbPlaying) { stopPlayback(); playPlayback(); } };
  }
  function resetPlayback() { stopPlayback(); pbIndex = 0; if (pbMarker) { map.removeLayer(pbMarker); pbMarker = null; }
    const sp = $("pbSpeedNow"); if (sp) sp.textContent = histPts[0] && histPts[0].speedKmh != null ? histPts[0].speedKmh : "–"; }
  function renderPb() {
    const p = histPts[pbIndex]; if (!p) return;
    const icon = L.divIcon({ className: "", iconSize: [22, 22], iconAnchor: [11, 11],
      html: `<div style="width:16px;height:16px;border-radius:50%;background:#4edea3;border:3px solid #131313;box-shadow:0 0 12px rgba(78,222,163,.9)"></div>` });
    if (!pbMarker) pbMarker = L.marker([p.lat, p.lon], { icon, zIndexOffset: 1000 }).addTo(map);
    else { pbMarker.setLatLng([p.lat, p.lon]); pbMarker.setIcon(icon); }
    map.panTo([p.lat, p.lon], { animate: true, duration: .3 });
    const sp = $("pbSpeedNow"); if (sp) sp.textContent = p.speedKmh != null ? p.speedKmh : "–";
  }
  function playPlayback() {
    if (histPts.length < 2) return;
    pbPlaying = true; $("pbPlay").querySelector("span").textContent = "pause";
    if (pbIndex >= histPts.length - 1) pbIndex = 0;
    pbTimer = setInterval(() => {
      pbIndex++;
      if (pbIndex >= histPts.length) { pbIndex = histPts.length - 1; renderPb(); stopPlayback(); return; }
      renderPb();
    }, Math.max(60, 320 / pbSpeed));
  }
  function stopPlayback() { pbPlaying = false; if (pbTimer) clearInterval(pbTimer); pbTimer = null; const b = $("pbPlay"); if (b) b.querySelector("span").textContent = "play_arrow"; }

  // ── Canlı/Geçmiş toggle ──────────────────────────────────────────────────
  window.toggleView = function (v) {
    view = v;
    clearSelTrack();
    const live = $("live-panel"), hist = $("history-panel"), ind = $("nav-indicator");
    document.querySelectorAll(".nav-link").forEach(l => {
      const on = l.dataset.view === v;
      l.classList.toggle("text-primary", on); l.classList.toggle("font-bold", on);
      l.classList.toggle("text-on-surface-variant", !on); l.classList.toggle("font-medium", !on);
      if (on) { const r = l.getBoundingClientRect(), pr = l.parentElement.getBoundingClientRect(); ind.style.width = r.width + "px"; ind.style.transform = "translateX(" + (r.left - pr.left) + "px)"; }
    });
    document.querySelectorAll(".float-nav-btn").forEach(b => {
      const on = b.dataset.view === v;
      b.classList.toggle("bg-primary", on); b.classList.toggle("text-on-primary", on); b.classList.toggle("emerald-glow", on); b.classList.toggle("text-on-surface-variant", !on);
    });
    const showHist = v === "history", isFleet = v === "fleet";
    $("playbackBar").classList.toggle("hidden", !showHist);
    $("histSummary").classList.toggle("hidden", !showHist);
    $("fleet-panel").classList.toggle("hidden", !isFleet);
    $("floatControls").style.display = isFleet ? "none" : "";
    $("zoomControls").style.display = isFleet ? "none" : "";
    if (isFleet) $("selOverlay").classList.add("hidden");
    if (v === "live") {
      hist.classList.add("hidden-content"); setTimeout(() => live.classList.remove("hidden-content"), 100);
      clearHistory(); if (cluster) map.addLayer(cluster);
      if (selected != null) showTodayTrack(selected);
    } else if (v === "history") {
      live.classList.add("hidden-content"); setTimeout(() => hist.classList.remove("hidden-content"), 100);
      if (cluster) map.removeLayer(cluster);
      const vid = selected != null ? selected : (+$("histVehicle").value || null);
      if (vid != null) { $("histVehicle").value = vid; loadHistory(vid, currentDay(), true); }
      else $("histInfo").textContent = "Soldan bir araç ve gün seç.";
    } else { // fleet
      live.classList.add("hidden-content"); hist.classList.add("hidden-content");
      clearHistory(); renderFleet(); loadDashboard(); loadMaintenance(); loadInspections();
      // Giriş stagger'ı yalnızca sekme açılışında oynat, sonra sınıfı kaldır ki
      // veri yenilemesi/filtre tekrar tetiklemesin.
      const g = $("fleetGrid");
      if (g) { g.classList.add("anim-cards"); setTimeout(() => g.classList.remove("anim-cards"), 700); }
    }
  };

  // ── Müşteri takip linki ───────────────────────────────────────────────────
  async function shareVehicle(id) {
    try {
      const r = await fetch(`/api/v1/vehicles/${id}/share`, { method: "POST", headers: auth() });
      if (!r.ok) { toast("Paylaşım linki oluşturulamadı", "error"); return; }
      const d = await r.json();
      const url = location.origin + "/share.html?t=" + d.token;
      await navigator.clipboard.writeText(url);
      toast("Link kopyalandı · 24 saat geçerli", "share");
    } catch (_) {
      toast("Kopyalama başarısız", "content_copy");
    }
  }

  // ── Geofence çizici ───────────────────────────────────────────────────────
  let geoLayer = null, drawControl = null, drawingLayer = null;

  function initGeofence() {
    const toggle = $("geoToggle"), panel = $("geoPanel");
    if (!toggle || !panel) return;
    toggle.onclick = () => {
      const qp = $("pairPanel"); if (qp) qp.classList.add("hidden");   // aynı köşede: QR'ı kapat
      const jp = $("jobPanel"); if (jp) jp.classList.add("hidden");    // ve Görevler'i kapat
      const hidden = panel.classList.toggle("hidden"); if (!hidden) loadGeofences();
    };
    $("geoClose").onclick = () => { panel.classList.add("hidden"); cancelDraw(); };
    $("geoDrawBtn").onclick = startDraw;
    $("geoCancelBtn").onclick = cancelDraw;
    geoLayer = L.layerGroup().addTo(map);
    loadGeofences();
  }

  async function loadGeofences() {
    try {
      const r = await fetch("/api/v1/geofences", { headers: auth() });
      if (!r.ok) return;
      const zones = await r.json();
      renderGeofenceLayers(zones);
      renderGeoList(zones);
    } catch (_) {}
  }

  function renderGeofenceLayers(zones) {
    if (!geoLayer) return;
    geoLayer.clearLayers();
    zones.forEach(z => {
      try {
        const geojson = JSON.parse(z.geojson);
        const color = z.kind === "EXCLUSION" ? "#ff5555" : "#4edea3";
        L.geoJSON(geojson, { style: { color, weight: 2, fillOpacity: 0.12, fillColor: color } })
          .bindTooltip(z.name, { permanent: false, className: "share-tooltip" })
          .addTo(geoLayer);
      } catch (_) {}
    });
  }

  function renderGeoList(zones) {
    const el = $("geoList"); if (!el) return;
    if (!zones.length) { el.innerHTML = `<div class="text-label-sm text-on-surface-variant">Henüz bölge yok.</div>`; return; }
    el.innerHTML = zones.map(z => `
      <div class="flex items-center justify-between gap-2 bg-white/5 rounded-lg px-3 py-2">
        <div>
          <span class="text-body-md font-bold text-on-surface">${esc(z.name)}</span>
          <span class="ml-2 text-[10px] uppercase px-1.5 py-0.5 rounded ${z.kind === "EXCLUSION" ? "bg-red-900/40 text-red-400" : "bg-primary/20 text-primary"}">${z.kind === "EXCLUSION" ? "Yasak" : "Güvenli"}</span>
        </div>
        <button onclick="deleteGeofence(${z.id})" class="text-on-surface-variant hover:text-error transition-colors" title="Bölgeyi kaldır"><span class="material-symbols-outlined text-body-md">delete</span></button>
      </div>`).join("");
  }

  function startDraw() {
    if (!window.L || !window.L.Draw) { toast("Çizim kütüphanesi yüklenemedi", "error"); return; }
    cancelDraw();
    drawingLayer = new L.FeatureGroup().addTo(map);
    drawControl = new L.Control.Draw({
      draw: { polygon: { shapeOptions: { color: "#4edea3", fillOpacity: 0.2 } }, polyline: false, rectangle: false, circle: false, marker: false, circlemarker: false },
      edit: { featureGroup: drawingLayer, remove: false }
    });
    map.addControl(drawControl);
    new L.Draw.Polygon(map, { shapeOptions: { color: "#4edea3", fillOpacity: 0.2 } }).enable();
    if ($("geoDrawBtn")) $("geoDrawBtn").classList.add("hidden");
    if ($("geoCancelBtn")) $("geoCancelBtn").classList.remove("hidden");
    map.once("draw:created", e => { drawingLayer.addLayer(e.layer); saveGeofence(e.layer.getLatLngs()[0]); });
  }

  function cancelDraw() {
    if (drawControl) { try { map.removeControl(drawControl); } catch (_) {} drawControl = null; }
    if (drawingLayer) { try { map.removeLayer(drawingLayer); } catch (_) {} drawingLayer = null; }
    if ($("geoDrawBtn")) $("geoDrawBtn").classList.remove("hidden");
    if ($("geoCancelBtn")) $("geoCancelBtn").classList.add("hidden");
  }

  async function saveGeofence(latlngs) {
    const flat = Array.isArray(latlngs[0]) ? latlngs.flat(Infinity) : latlngs;
    const points = flat.map(ll => [ll.lat, ll.lng]);
    const name = ($("geoName").value || "").trim() || "Yeni bölge";
    const kind = $("geoKind").value || "EXCLUSION";
    cancelDraw();
    try {
      const r = await fetch("/api/v1/geofences", {
        method: "POST", headers: { ...auth(), "Content-Type": "application/json" },
        body: JSON.stringify({ name, kind, points })
      });
      if (!r.ok) { toast("Bölge kaydedilemedi", "error"); return; }
      $("geoName").value = "";
      toast(`"${name}" bölgesi oluşturuldu`, "pentagon");
      loadGeofences();
    } catch (_) { toast("Bağlantı hatası", "error"); }
  }

  window.deleteGeofence = async function (id) {
    try {
      const r = await fetch(`/api/v1/geofences/${id}`, { method: "DELETE", headers: auth() });
      if (!r.ok) return;
      toast("Bölge kaldırıldı", "delete");
      loadGeofences();
    } catch (_) {}
  };

  // ── Araç kontrolleri (DVIR) ───────────────────────────────────────────────
  let inspDefectsOnly = false;
  const INSP_LABELS = { tires: "Lastik", brakes: "Fren", lights: "Far", fluids: "Sıvı", body: "Kaporta", horn: "Korna" };

  function initInspections() {
    const t = $("inspDefectsToggle");
    if (t) t.onclick = () => {
      inspDefectsOnly = !inspDefectsOnly;
      t.classList.toggle("text-error", inspDefectsOnly);
      t.classList.toggle("text-on-surface-variant", !inspDefectsOnly);
      t.textContent = inspDefectsOnly ? "Tümünü göster" : "Sadece kusurlu";
      loadInspections();
    };
  }

  async function loadInspections() {
    const el = $("inspectionList"); if (!el) return;
    try {
      const r = await fetch("/api/v1/inspections?defectsOnly=" + inspDefectsOnly, { headers: auth() });
      if (!r.ok) { el.innerHTML = `<div class="text-label-sm text-on-surface-variant">Yüklenemedi.</div>`; return; }
      renderInspections(await r.json());
    } catch (_) { el.innerHTML = `<div class="text-label-sm text-on-surface-variant">Bağlantı hatası.</div>`; }
  }

  function renderInspections(items) {
    const el = $("inspectionList"); if (!el) return;
    if (!items.length) { el.innerHTML = `<div class="text-label-sm text-on-surface-variant">${inspDefectsOnly ? "Kusurlu kontrol yok." : "Sefer öncesi kontrol kaydı yok."}</div>`; return; }
    el.innerHTML = items.map(it => {
      const defect = it.overall === "DEFECT";
      let defects = [];
      try { const m = JSON.parse(it.items || "{}"); defects = Object.keys(m).filter(k => m[k] === "defect").map(k => INSP_LABELS[k] || k); } catch (_) {}
      const when = it.inspectedAt ? relTime(it.inspectedAt) : "";
      return `<div class="bg-white/5 rounded-lg px-3 py-2">
        <div class="flex items-center justify-between gap-2">
          <span class="text-body-md font-bold text-on-surface truncate">${esc(it.plate)}</span>
          <span class="shrink-0 px-2 py-0.5 rounded-lg text-[10px] font-bold ${defect ? "bg-error/15 text-error" : "bg-primary/15 text-primary"}">${defect ? "Kusurlu" : "Uygun"}</span>
        </div>
        <div class="text-[10px] mt-0.5 ${defect ? "text-error" : "text-on-surface-variant"}">${when}${defects.length ? " · " + defects.join(", ") : ""}</div>
        ${it.note ? `<div class="text-[10px] mt-1 text-on-surface-variant italic">“${esc(it.note)}”</div>` : ""}
      </div>`;
    }).join("");
  }

  // ── Görev atama + ETA ─────────────────────────────────────────────────────
  let jobLayer = null, jobs = [], jobTimer = null;
  let jobAssignVehicle = null, jobPendingDest = null, jobPickMarker = null, jobPicking = false;
  const JOB_STATUS = {
    ASSIGNED: { label: "Atandı", cls: "bg-amber-500/15 text-amber-400", color: "#f5b43c" },
    EN_ROUTE: { label: "Yolda",  cls: "bg-blue-500/15 text-blue-400",   color: "#6aa8ff" },
    ARRIVED:  { label: "Vardı",  cls: "bg-primary/15 text-primary",     color: "#4edea3" }
  };

  function initJobs() {
    const toggle = $("jobToggle"), panel = $("jobPanel");
    if (!toggle || !panel) return;
    jobLayer = L.layerGroup().addTo(map);
    toggle.onclick = () => {
      $("pairPanel") && $("pairPanel").classList.add("hidden");
      $("geoPanel") && $("geoPanel").classList.add("hidden");
      const hidden = panel.classList.toggle("hidden");
      if (!hidden) loadJobs(); else exitJobAssign();
    };
    $("jobClose").onclick = () => { panel.classList.add("hidden"); exitJobAssign(); };
    $("jobAssignBtn").onclick = assignJob;
    $("jobCancelBtn").onclick = exitJobAssign;
    loadJobs();
    jobTimer = setInterval(() => { if (!$("jobPanel").classList.contains("hidden") || jobLayer.getLayers().length) loadJobs(); }, 15000);
  }

  async function loadJobs() {
    try {
      const r = await fetch("/api/v1/jobs", { headers: auth() });
      if (!r.ok) return;
      jobs = await r.json();
      renderJobLayers();
      renderJobList();
    } catch (_) {}
  }

  function renderJobLayers() {
    if (!jobLayer) return;
    jobLayer.clearLayers();
    jobs.forEach(j => {
      const st = JOB_STATUS[j.status] || JOB_STATUS.ASSIGNED;
      const dest = [j.destLat, j.destLon];
      L.marker(dest, {
        icon: L.divIcon({ className: "", iconSize: [30, 30], iconAnchor: [15, 30], html:
          `<div style="display:flex;flex-direction:column;align-items:center">
             <span class="material-symbols-outlined" style="font-size:28px;color:${st.color};text-shadow:0 1px 3px #000">place</span>
           </div>` })
      }).bindTooltip(`${esc(j.title)}${j.etaMin != null ? " · ~" + j.etaMin + " dk" : ""}`, { className: "plate-label", direction: "top", offset: [0, -26] }).addTo(jobLayer);
      // Aracın anlık konumundan hedefe kesikli çizgi
      const p = pos.get(j.vehicleId);
      if (p && p.lat != null) {
        L.polyline([[p.lat, p.lon], dest], { color: st.color, weight: 2, dashArray: "6 8", opacity: .8 }).addTo(jobLayer);
      }
    });
  }

  function renderJobList() {
    const el = $("jobList"); if (!el) return;
    if (!jobs.length) { el.innerHTML = `<div class="text-label-sm text-on-surface-variant">Aktif görev yok. Bir araç seçip “Görev ata”ya bas.</div>`; return; }
    el.innerHTML = jobs.map(j => {
      const st = JOB_STATUS[j.status] || JOB_STATUS.ASSIGNED;
      const meta = [esc(j.destLabel || "Hedef")];
      if (j.remainingKm != null) meta.push(`${j.remainingKm} km`);
      meta.push(j.etaMin != null ? `ETA ~${j.etaMin} dk` : "ETA —");
      return `<div class="bg-white/5 rounded-lg px-3 py-2">
        <div class="flex items-center justify-between gap-2">
          <span class="text-body-md font-bold text-on-surface truncate">${esc(j.plate)} · ${esc(j.title)}</span>
          <span class="shrink-0 px-2 py-0.5 rounded-lg text-[10px] font-bold ${st.cls}">${st.label}</span>
        </div>
        <div class="flex items-center justify-between mt-1">
          <span class="text-[10px] text-on-surface-variant truncate">${meta.join(" · ")}</span>
          <button onclick="cancelJob(${j.jobId})" class="shrink-0 text-on-surface-variant hover:text-error transition-colors" title="Görevi iptal et"><span class="material-symbols-outlined text-body-md">close</span></button>
        </div>
      </div>`;
    }).join("");
  }

  window.cancelJob = async function (id) {
    try {
      const r = await fetch(`/api/v1/jobs/${id}/cancel`, { method: "POST", headers: auth() });
      if (!r.ok) return;
      toast("Görev iptal edildi", "assignment");
      loadJobs();
    } catch (_) {}
  };

  // Araç seçiliyken "Görev ata": paneli aç, haritadan hedef seçtir.
  function startJobAssign(vehicleId) {
    const v = vehicles.get(vehicleId); if (!v) return;
    jobAssignVehicle = vehicleId; jobPendingDest = null;
    $("pairPanel") && $("pairPanel").classList.add("hidden");
    $("geoPanel") && $("geoPanel").classList.add("hidden");
    $("jobPanel").classList.remove("hidden");
    $("jobForm").classList.remove("hidden");
    $("jobFormPlate").textContent = v.plate;
    $("jobTitle").value = ""; $("jobDestLabel").value = "";
    $("jobDestHint").textContent = "Haritada hedefi tıkla…";
    $("jobAssignBtn").disabled = true;
    jobPicking = true;
    map.getContainer().style.cursor = "crosshair";
    map.on("click", onJobPick);
    toast("Haritada varış noktasını tıkla", "location_on");
  }

  function onJobPick(e) {
    if (!jobPicking) return;
    jobPendingDest = e.latlng;
    if (jobPickMarker) map.removeLayer(jobPickMarker);
    jobPickMarker = L.marker(jobPendingDest, {
      icon: L.divIcon({ className: "", iconSize: [30, 30], iconAnchor: [15, 30],
        html: `<span class="material-symbols-outlined" style="font-size:28px;color:#4edea3;text-shadow:0 1px 3px #000">place</span>` })
    }).addTo(map);
    $("jobDestHint").textContent = `Hedef: ${jobPendingDest.lat.toFixed(4)}, ${jobPendingDest.lng.toFixed(4)}`;
    $("jobAssignBtn").disabled = false;
  }

  function exitJobAssign() {
    jobPicking = false; jobAssignVehicle = null; jobPendingDest = null;
    map.getContainer().style.cursor = "";
    map.off("click", onJobPick);
    if (jobPickMarker) { map.removeLayer(jobPickMarker); jobPickMarker = null; }
    const f = $("jobForm"); if (f) f.classList.add("hidden");
  }

  async function assignJob() {
    const title = ($("jobTitle").value || "").trim();
    if (jobAssignVehicle == null || !jobPendingDest || !title) { toast("Görev adı ve haritada hedef gerekli", "error"); return; }
    try {
      const r = await fetch("/api/v1/jobs", {
        method: "POST", headers: { ...auth(), "Content-Type": "application/json" },
        body: JSON.stringify({
          vehicleId: jobAssignVehicle, title,
          destLabel: ($("jobDestLabel").value || "").trim() || null,
          destLat: jobPendingDest.lat, destLon: jobPendingDest.lng
        })
      });
      if (!r.ok) { toast("Görev atanamadı", "error"); return; }
      toast(`“${title}” görevi atandı`, "assignment_turned_in");
      exitJobAssign();
      loadJobs();
    } catch (_) { toast("Bağlantı hatası", "error"); }
  }

  // ── Bakım takibi ──────────────────────────────────────────────────────────
  async function loadMaintenance() {
    const el = $("maintenanceList"); if (!el) return;
    try {
      const r = await fetch("/api/v1/maintenance/plans", { headers: auth() });
      if (!r.ok) { el.innerHTML = `<div class="text-label-sm text-on-surface-variant">Yüklenemedi.</div>`; return; }
      renderMaintenance(await r.json());
    } catch (_) { if (el) el.innerHTML = `<div class="text-label-sm text-on-surface-variant">Bağlantı hatası.</div>`; }
  }

  // Durumu sürücü işaretler; admin yalnızca görür (salt-okunur rozet).
  const MTN_STATUS = {
    PENDING:     { label: "Bekleniyor", cls: "bg-amber-500/15 text-amber-400" },
    IN_PROGRESS: { label: "Bakımda",    cls: "bg-blue-500/15 text-blue-400" },
    DONE:        { label: "Yapıldı",    cls: "bg-primary/15 text-primary" }
  };

  function renderMaintenance(items) {
    const el = $("maintenanceList"); if (!el) return;
    if (!items.length) { el.innerHTML = `<div class="text-label-sm text-on-surface-variant">Bakım planı yok.</div>`; return; }
    el.innerHTML = items.map(m => {
      const kmInfo = m.nextDueKm != null ? `${Number(m.nextDueKm).toLocaleString("tr")} km` : "";
      const dateInfo = m.nextDueAt ? new Date(m.nextDueAt).toLocaleDateString("tr-TR") : "";
      const dueStr = [kmInfo, dateInfo].filter(Boolean).join(" / ");
      const st = MTN_STATUS[m.status] || MTN_STATUS.PENDING;
      return `<div class="flex items-center justify-between gap-2 bg-white/5 rounded-lg px-3 py-2">
        <div class="min-w-0">
          <div class="flex items-center gap-1 text-body-md font-bold text-on-surface">
            ${m.overdue ? `<span class="material-symbols-outlined text-body-md text-error" style="font-variation-settings:'FILL' 1">warning</span>` : ""}
            <span class="truncate">${esc(m.plate)} · ${esc(m.name)}</span>
          </div>
          <div class="text-[10px] mt-0.5 ${m.overdue ? "text-error" : "text-on-surface-variant"}">${dueStr || "Tarih yok"}</div>
        </div>
        <span class="shrink-0 px-2 py-1 rounded-lg text-[10px] font-bold ${st.cls}">${st.label}</span>
      </div>`;
    }).join("");
  }

  function initMaintenance() {
    const toggle = $("mtnAddToggle"), form = $("mtnAddForm");
    if (toggle && form) toggle.onclick = () => { const h = form.classList.toggle("hidden"); if (!h) populateMtnVehicles(); };
    if ($("mtnSaveBtn")) $("mtnSaveBtn").onclick = createMaintenancePlan;
  }

  function populateMtnVehicles() {
    const sel = $("mtnVehicle"); if (!sel) return;
    sel.innerHTML = [...vehicles.values()].map(v => `<option value="${v.id}">${esc(v.plate)}</option>`).join("");
  }

  async function createMaintenancePlan() {
    const vehicleId = +$("mtnVehicle").value;
    const name = ($("mtnName").value || "").trim();
    const intervalKm = +$("mtnKm").value || null;
    const intervalDays = +$("mtnDays").value || null;
    if (!vehicleId || !name) { toast("Araç ve plan adı gerekli", "error"); return; }
    try {
      const r = await fetch("/api/v1/maintenance/plans", {
        method: "POST", headers: { ...auth(), "Content-Type": "application/json" },
        body: JSON.stringify({ vehicleId, name, intervalKm, intervalDays })
      });
      if (!r.ok) { toast("Plan oluşturulamadı", "error"); return; }
      ["mtnName", "mtnKm", "mtnDays"].forEach(id => { if ($(id)) $(id).value = ""; });
      $("mtnAddForm").classList.add("hidden");
      toast(`"${name}" planı oluşturuldu`, "build");
      loadMaintenance();
    } catch (_) { toast("Bağlantı hatası", "error"); }
  }

  // ── Sürücü QR'ı ───────────────────────────────────────────────────────────
  // Tek genel kod: araca özel değil, sadece sürücü sayfasına yönlendirir. Sürücü
  // tarar, açılan sayfada plaka + şifre girer. Tünel varsa https adresini kullanır
  // (telefon GPS'i güvenli bağlam ister), yoksa mevcut origin'e düşer.
  async function initQr() {
    const panel = $("pairPanel"), qrEl = $("pairQr"), hint = $("pairHint");
    $("pairClose").onclick = () => panel.classList.add("hidden");
    $("pairBtn").onclick = () => {
      const gp = $("geoPanel"); if (gp) gp.classList.add("hidden");   // aynı köşede: Bölgeler'i kapat
      const jp = $("jobPanel"); if (jp) jp.classList.add("hidden");   // ve Görevler'i kapat
      panel.classList.toggle("hidden");
    };
    let base = location.origin;
    try { const cfg = await (await fetch("/api/v1/track/config")).json(); if (cfg && cfg.publicUrl) base = cfg.publicUrl.replace(/\/+$/, ""); } catch (_) {}
    const url = base + "/driver.html";
    if (typeof qrcode === "function") {
      const qr = qrcode(0, "M"); qr.addData(url); qr.make();
      qrEl.innerHTML = qr.createSvgTag({ cellSize: 3, margin: 2 });
    }
    hint.textContent = (base === location.origin && location.protocol !== "https:")
      ? "Telefon GPS'i için https tünel gerekir (start-tracking)."
      : "QR'ı telefonla tarat → plaka + şifre ile gir.";
  }
})();
