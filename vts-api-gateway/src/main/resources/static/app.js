/* FleetFlow — tek harita + Canlı/Geçmiş, emerald tema, gerçek VTS gateway'e bağlı. */
(function () {
  "use strict";
  let token = null, map = null, stomp = null, view = "live";
  let selected = null, followId = null, alertCount = 0;
  const vehicles = new Map();   // id -> {trackId, plate, make, model, type}
  const pos = new Map();        // id -> position
  const markers = new Map();    // id -> L.marker
  const tripCache = new Map();  // id -> {distanceKm, ecoScore, score}
  const recentVios = [];        // {vehicleId,ruleCode,value,threshold,occurredAt}
  let fleetFilter = "all", dash = null, cluster = null;
  const STALE_MS = 30000, VIO_SPEED = 82, STOP_SPEED = 3, MIN_STOP_SEC = 120, STOP_RADIUS_M = 40;
  const RED_WINDOW_MS = 120000;   // 6b: bir nokta, gerçek hız ihlalinin ±2 dk'sındaysa kırmızı

  const $ = id => document.getElementById(id);
  const auth = () => ({ Authorization: "Bearer " + token });
  const esc = s => (s || "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  const RULE_TR = { SPEED_LIMIT: "Hız aşımı", HARSH_BRAKING: "Sert fren", HARSH_ACCELERATION: "Ani hızlanma", HARSH_ACCEL: "Ani hızlanma", HARSH_CORNERING: "Sert viraj", IDLING: "Uzun rölanti", GEOFENCE: "Bölge ihlali", ROUTE_DEVIATION: "Rota sapması" };
  const ruleLabel = c => RULE_TR[c] || c || "İhlal";
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
    initChat();
    loadDashboard();
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
  }
  function sweep() { pos.forEach(p => drawMarker(p)); updateStats(); refreshCards(); if (cluster && view !== "history") cluster.refreshClusters(); if (selected != null) updateOverlay(); if (view === "fleet") renderFleet(); }
  function fitAll() {
    const pts = []; pos.forEach(p => { if (p.lat != null) pts.push([p.lat, p.lon]); });
    if (pts.length) map.fitBounds(L.latLngBounds(pts).pad(0.25));
  }

  function refreshCards() {
    document.querySelectorAll(".vcard").forEach(card => {
      const id = +card.dataset.vid, p = pos.get(id), st = stateOf(id);
      const dot = card.querySelector(".vdot"), chip = card.querySelector(".vchip"),
            state = card.querySelector(".vstate"), spd = card.querySelector(".vspeed");
      if (dot) dot.style.background = st.color;
      if (state) state.textContent = st.label;
      if (chip) { chip.style.color = st.color; chip.style.borderColor = st.color + "55"; chip.style.background = st.color + "1a"; }
      if (spd) spd.textContent = (p && p.speedKmh != null ? p.speedKmh : "—") + " km/s";
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
    if (id == null) { ov.classList.add("hidden"); followId = null; if (view === "history") clearHistory(); return; }
    updateOverlay();
    loadTrip(id);
    ov.classList.remove("hidden");
    const p = pos.get(id);
    if (p && view === "live") map.panTo([p.lat, p.lon]);
    $("histVehicle").value = id;
    if (view === "history") loadHistory(id, currentDay(), true);
  }
  function updateOverlay() {
    const v = vehicles.get(selected), p = pos.get(selected);
    $("selTitle").textContent = "#" + (v ? v.trackId : "?") + " · " + (v ? v.plate : "");
    $("selSub").textContent = [v && v.make, v && v.model].filter(Boolean).join(" ") || "Telefon GPS";
    $("selSpeed").textContent = p ? (p.speedKmh != null ? p.speedKmh : 0) : "0";
    $("selFuel").textContent = p && p.fuelPct != null ? p.fuelPct : "—";
    $("selAcc").textContent = p && p.accuracy != null ? Math.round(p.accuracy) : "—";
    const t = tripCache.get(selected);
    $("selEco").textContent = t && t.ecoScore != null ? t.ecoScore : "—";
    $("selScore").textContent = t && t.score != null ? t.score : "—";
    $("selDist").textContent = t && t.distanceKm != null ? (+t.distanceKm).toFixed(1) : "—";
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
  }

  // Kısa süreli, tıklanınca kapanan bildirim balonu (sürücü yanıtı için).
  let toastTimer = null;
  function toast(html) {
    let el = $("ffToast");
    if (!el) {
      el = document.createElement("div");
      el.id = "ffToast";
      el.className = "fixed top-20 left-1/2 -translate-x-1/2 z-[70] glass-panel px-4 py-2.5 rounded-xl text-body-md text-on-surface anim-in flex items-center gap-2 cursor-pointer";
      el.style.borderLeft = "3px solid #4edea3";
      el.onclick = () => { el.style.display = "none"; };
      document.body.appendChild(el);
    }
    el.innerHTML = `<span class="material-symbols-outlined text-primary text-body-md" style="font-variation-settings:'FILL' 1">chat</span><span>${html}</span>`;
    el.style.display = "flex";
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.style.display = "none"; }, 5000);
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
      const list = r.ok ? await r.json() : [];
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
      toast((v ? v.plate : "Araç") + " için şifre belirlendi.");
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
      toast((v ? v.plate : "Araç") + " kalıcı olarak silindi.");
      await loadVehicles(); renderFleet();
    } catch (_) { alert("Araç silinemedi."); }
  }
  async function loadDashboard() {
    try { const r = await fetch("/api/v1/dashboard/summary", { headers: auth() }); if (r.ok) dash = await r.json(); } catch (_) {}
    updateFleetHealth();
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
    const seen = p && p.ts ? new Date(p.ts).toLocaleTimeString("tr-TR", { hour: "2-digit", minute: "2-digit" }) : "—";
    return `<div class="glass-panel p-5 rounded-2xl relative overflow-hidden">
      <div class="absolute top-0 right-0 w-28 h-28 rounded-full blur-3xl -mr-14 -mt-14" style="background:${c}22"></div>
      <div class="flex justify-between items-start mb-4 relative">
        <div class="min-w-0">
          <span class="text-label-sm font-bold text-primary block">#${v.trackId}</span>
          <h3 class="font-title-md text-on-surface truncate">${esc(v.plate)}</h3>
          <p class="text-label-sm text-on-surface-variant truncate">${model}</p>
        </div>
        <div class="flex flex-col items-end gap-1.5 flex-none">
          <span class="px-3 py-1 rounded-full text-label-sm font-bold border inline-flex items-center gap-1.5" style="color:${c};border-color:${c}55;background:${c}1a">
            <span class="w-1.5 h-1.5 rounded-full" style="background:${c}"></span>${st.label}</span>
          <span class="text-label-sm text-on-surface-variant">${speed} km/s</span>
        </div>
      </div>
      ${fuel != null ? `<div class="mb-4 relative">
        <div class="flex justify-between text-[11px] text-on-surface-variant mb-1"><span>Yakıt</span><span>${fuel}%</span></div>
        <div class="w-full h-1.5 bg-white/5 rounded-full overflow-hidden"><div class="h-full rounded-full" style="width:${fuel}%;background:${fuel < 20 ? "#ffb4ab" : "#4edea3"}"></div></div></div>` : ""}
      <div class="flex items-start gap-2 text-on-surface-variant mb-4 relative">
        <span class="material-symbols-outlined text-primary text-body-md">location_on</span>
        <p class="text-label-sm leading-tight">${loc} · ${seen}</p>
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
  function renderFleet() {
    const grid = $("fleetGrid"); if (!grid) return;
    const ids = [...vehicles.keys()];
    const filtered = ids.filter(id => {
      if (fleetFilter === "all") return true;
      const k = stateOf(id).key;
      if (fleetFilter === "move") return k === "move" || k === "vio";  // ihlal = hızlı giden = harekette
      return k === fleetFilter;
    });
    $("fleetCount").textContent = vehicles.size + " araç yönetiliyor" + (fleetFilter !== "all" ? " · " + filtered.length + " gösteriliyor" : "");
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
    $("sumDistance").textContent = s.dist.toFixed(1);
    $("sumViolations").textContent = String(realVio).padStart(2, "0");
    $("sumStops").textContent = String(s.stops).padStart(2, "0");
    $("histInfo").innerHTML = `<b class="text-on-surface">#${v ? v.trackId : "?"} ${v ? esc(v.plate) : ""}</b><br>${dayLabel(day)} · <b>${pts.length}</b> nokta` +
      (pts.length ? `<br><span class="text-primary">${s.dist.toFixed(1)} km</span> · ${realVio} ihlal · ${s.stops} durak` : '<br><span class="text-error">Bu gün için kayıt yok.</span>');
    resetPlayback();
  }
  function extendHistory(p) {
    if (histDay !== 0 || p.vehicleId !== histVeh || !histLayer) return;
    const cur = { lat: p.lat, lon: p.lon, speedKmh: p.speedKmh, ts: p.ts };
    if (histPrev && histPrev.lat === cur.lat && histPrev.lon === cur.lon) return;
    histAdd(histPrev, cur); histPrev = cur; histPts.push(cur);
  }
  function clearHistory() { stopPlayback(); if (histLayer) map.removeLayer(histLayer); histLayer = null; histVeh = null; histPrev = null; histPts = []; histVios = []; speedVios = []; }

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
  function resetPlayback() { stopPlayback(); pbIndex = 0; if (pbMarker) { map.removeLayer(pbMarker); pbMarker = null; } }
  function renderPb() {
    const p = histPts[pbIndex]; if (!p) return;
    const icon = L.divIcon({ className: "", iconSize: [22, 22], iconAnchor: [11, 11],
      html: `<div style="width:16px;height:16px;border-radius:50%;background:#4edea3;border:3px solid #131313;box-shadow:0 0 12px rgba(78,222,163,.9)"></div>` });
    if (!pbMarker) pbMarker = L.marker([p.lat, p.lon], { icon, zIndexOffset: 1000 }).addTo(map);
    else { pbMarker.setLatLng([p.lat, p.lon]); pbMarker.setIcon(icon); }
    map.panTo([p.lat, p.lon], { animate: true, duration: .3 });
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
    } else if (v === "history") {
      live.classList.add("hidden-content"); setTimeout(() => hist.classList.remove("hidden-content"), 100);
      if (cluster) map.removeLayer(cluster);
      const vid = selected != null ? selected : (+$("histVehicle").value || null);
      if (vid != null) { $("histVehicle").value = vid; loadHistory(vid, currentDay(), true); }
      else $("histInfo").textContent = "Soldan bir araç ve gün seç.";
    } else { // fleet
      live.classList.add("hidden-content"); hist.classList.add("hidden-content");
      clearHistory(); renderFleet(); loadDashboard();
    }
  };

  // ── Sürücü QR'ı ───────────────────────────────────────────────────────────
  // Tek genel kod: araca özel değil, sadece sürücü sayfasına yönlendirir. Sürücü
  // tarar, açılan sayfada plaka + şifre girer. Tünel varsa https adresini kullanır
  // (telefon GPS'i güvenli bağlam ister), yoksa mevcut origin'e düşer.
  async function initQr() {
    const panel = $("pairPanel"), qrEl = $("pairQr"), hint = $("pairHint");
    $("pairClose").onclick = () => panel.classList.add("hidden");
    $("pairBtn").onclick = () => panel.classList.toggle("hidden");
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
