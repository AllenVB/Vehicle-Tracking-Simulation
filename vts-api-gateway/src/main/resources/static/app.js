/*
 * VTS — tek sayfa, iki harita (2/5/5 grid), tek servis (gateway).
 *
 *  - Sol harita  : canlı filo (OpenStreetMap)   — tipe göre logo, yöne dönük
 *  - Sağ harita  : operatör (CartoDB)            — logo + plaka no, çift tıkla taşı
 *  - Marker'lar araç tipine göre: otomobil / tır / motor / helikopter logosu.
 *  - Kara araçları taşınırken yol dışına tıklanırsa en yakın yola oturtulur ve uyarı
 *    verilir; helikopterler uçtuğu için istenen her yere (deniz, bina üstü) konulabilir.
 *  - TEK WebSocket (/topic/fleet/live) her iki haritayı da besler.
 */
(function () {
    "use strict";

    let token = null;
    let live = null, ctrl = null;             // iki Leaflet haritası
    const liveMarkers = new Map();            // vehicleId -> marker (sol)
    const ctrlMarkers = new Map();            // vehicleId -> marker (sağ)
    const pos = new Map();                    // vehicleId -> {lat,lon,speedKmh,heading}
    const vehicles = new Map();               // vehicleId -> {plate, plateNo, type, model}
    const byPlateNo = new Map();              // plakaNo -> vehicleId
    const manual = new Set();                 // elle sabitlenen vehicleId'ler
    const journey = new Map();                // vehicleId -> {destination, remainingKm, etaMinutes, parked, flying}
    let selected = null;                      // seçili vehicleId
    let routeLayer = null;                    // seçili aracın gideceği rota
    let alerts = 0, fitted = false;

    // Tip -> renk (logolar bu renkte çizilir)
    const TYPE_COLOR = { CAR: "#378add", TRUCK: "#e0912a", MOTORCYCLE: "#1d9e75", HELICOPTER: "#a855f7" };

    // ── Giriş ───────────────────────────────────────────────────────────────
    const loginBtn = document.getElementById("loginBtn");
    loginBtn.addEventListener("click", doLogin);
    document.getElementById("password").addEventListener("keydown",
        e => { if (e.key === "Enter") doLogin(); });

    async function doLogin() {
        const err = document.getElementById("loginErr");
        err.textContent = "";
        loginBtn.disabled = true;
        try {
            const res = await fetch("/api/v1/auth/login", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    username: document.getElementById("username").value.trim(),
                    password: document.getElementById("password").value
                })
            });
            if (!res.ok) throw new Error("Kullanıcı adı veya parola hatalı.");
            token = (await res.json()).token;
            document.getElementById("login").style.display = "none";
            start();
        } catch (e) {
            err.textContent = e.message;
            loginBtn.disabled = false;
        }
    }

    const auth = () => ({ "Authorization": "Bearer " + token });

    // ── Başlat ──────────────────────────────────────────────────────────────
    async function start() {
        initMaps();
        await loadVehicles();
        await loadSnapshot();
        connectWs();
        refreshDispatch();
        setInterval(refreshDispatch, 3000);
        loadScores();
        setInterval(loadScores, 60000);
        loadGeofences();

        document.getElementById("plateNo").addEventListener("input", e => {
            const n = parseInt(e.target.value, 10);
            select(byPlateNo.get(n) ?? null);
        });
        document.getElementById("releaseBtn").addEventListener("click", release);
    }

    function initMaps() {
        live = L.map("mapLive", { zoomControl: true, attributionControl: false })
            .setView([39.0, 35.0], 6);
        L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19 }).addTo(live);

        ctrl = L.map("mapCtrl", { zoomControl: true, attributionControl: false })
            .setView([39.0, 35.0], 6);
        L.tileLayer("https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png",
            { maxZoom: 19, subdomains: "abcd" }).addTo(ctrl);

        ctrl.on("dblclick", onCtrlDblClick);
    }

    // ── Araç listesi ────────────────────────────────────────────────────────
    async function loadVehicles() {
        const res = await fetch("/api/v1/vehicles", { headers: auth() });
        if (!res.ok) return;
        const list = await res.json();
        const el = document.getElementById("vehicleList");
        el.innerHTML = "";
        list.sort((a, b) => plateNo(a.plate) - plateNo(b.plate));
        list.forEach(v => {
            const no = plateNo(v.plate);
            vehicles.set(v.id, {
                plate: v.plate, plateNo: no, type: v.type,
                model: [v.make, v.model].filter(Boolean).join(" ")
            });
            byPlateNo.set(no, v.id);
            const row = document.createElement("div");
            row.className = "row";
            row.dataset.vid = v.id;
            row.innerHTML = `<b>${v.plate}</b><div class="meta" data-model="${vehicles.get(v.id).model}">${vehicles.get(v.id).model}</div>`;
            row.addEventListener("click", () => {
                document.getElementById("plateNo").value = no;
                select(v.id);
            });
            el.appendChild(row);
        });
        document.getElementById("statTotal").textContent = list.length;
    }

    const plateNo = p => { const m = /VTS-(\d+)/.exec(p || ""); return m ? parseInt(m[1], 10) : 0; };

    // ── Konumlar ────────────────────────────────────────────────────────────
    async function loadSnapshot() {
        const res = await fetch("/api/v1/live/positions", { headers: auth() });
        if (res.ok) apply(await res.json());
    }

    function connectWs() {
        const stomp = new StompJs.Client({
            webSocketFactory: () => new SockJS("/ws"),
            connectHeaders: { Authorization: "Bearer " + token },
            reconnectDelay: 3000
        });
        stomp.onConnect = () => {
            setStatus("Bağlı · canlı", true);
            stomp.subscribe("/topic/fleet/live", m => apply(JSON.parse(m.body).vehicles || []));
            stomp.subscribe("/topic/violations", m => onViolation(JSON.parse(m.body)));
        };
        stomp.onWebSocketClose = () => setStatus("Bağlantı koptu · yeniden deneniyor…", false);
        stomp.activate();
    }

    function apply(list) {
        list.forEach(p => {
            if (p.lat == null || p.lon == null) return;
            pos.set(p.vehicleId, p);
            drawLive(p);
            drawCtrl(p);
        });
        document.getElementById("statLive").textContent = pos.size;
        if (!fitted && pos.size > 0) {
            fitted = true;
            const pts = [...pos.values()].map(p => [p.lat, p.lon]);
            const b = L.latLngBounds(pts).pad(0.15);
            live.fitBounds(b);
            ctrl.fitBounds(b);
        }
    }

    // Sol harita: tipe göre logo, yöne dönük (badge yok)
    function drawLive(p) {
        let m = liveMarkers.get(p.vehicleId);
        const icon = typeIcon(p.vehicleId, p.heading || 0, false, false);
        if (!m) {
            m = L.marker([p.lat, p.lon], { icon }).addTo(live);
            m.on("click", () => select(p.vehicleId));
            liveMarkers.set(p.vehicleId, m);
        } else {
            m.setLatLng([p.lat, p.lon]);
            if (!m._alerting) m.setIcon(icon);
        }
        m.bindPopup(popup(p));
    }

    // Sağ harita: tipe göre logo + plaka no badge'i
    function drawCtrl(p) {
        let m = ctrlMarkers.get(p.vehicleId);
        const icon = typeIcon(p.vehicleId, p.heading || 0, false, true);
        if (!m) {
            m = L.marker([p.lat, p.lon], { icon }).addTo(ctrl);
            m.on("click", () => {
                const v = vehicles.get(p.vehicleId);
                if (v) document.getElementById("plateNo").value = v.plateNo;
                select(p.vehicleId);
            });
            ctrlMarkers.set(p.vehicleId, m);
        } else {
            m.setLatLng([p.lat, p.lon]);
            m.setIcon(icon);
        }
    }

    // Tipe göre yukarı (kuzey) bakan silüet; heading ile döndürülür.
    function vehSvg(type, color) {
        const dark = "#0b1016";
        switch (type) {
            case "TRUCK":
                return `<svg viewBox="0 0 24 24" width="26" height="26">
                    <rect x="6" y="8" width="12" height="14" rx="1.5" fill="${color}" stroke="${dark}" stroke-width="1"/>
                    <rect x="7.5" y="2" width="9" height="6.5" rx="1.5" fill="${color}" stroke="${dark}" stroke-width="1"/>
                    <rect x="9" y="3.5" width="6" height="2.6" rx=".6" fill="#dbeafe"/></svg>`;
            case "MOTORCYCLE":
                return `<svg viewBox="0 0 24 24" width="24" height="24">
                    <circle cx="12" cy="6" r="2.6" fill="${dark}"/>
                    <circle cx="12" cy="18" r="2.6" fill="${dark}"/>
                    <rect x="10.3" y="6" width="3.4" height="12" rx="1.7" fill="${color}" stroke="${dark}" stroke-width=".7"/>
                    <rect x="7.5" y="4" width="9" height="1.8" rx=".9" fill="${color}"/></svg>`;
            case "HELICOPTER":
                return `<svg viewBox="0 0 24 24" width="28" height="28">
                    <line x1="2.5" y1="8" x2="21.5" y2="8" stroke="${color}" stroke-width="2" stroke-linecap="round"/>
                    <rect x="11" y="14" width="2" height="7.5" fill="${color}"/>
                    <line x1="9.5" y1="21" x2="14.5" y2="21" stroke="${color}" stroke-width="1.7" stroke-linecap="round"/>
                    <ellipse cx="12" cy="11" rx="3.6" ry="6" fill="${color}" stroke="${dark}" stroke-width="1"/>
                    <circle cx="12" cy="8" r="1.1" fill="#fff"/></svg>`;
            default: // CAR
                return `<svg viewBox="0 0 24 24" width="24" height="24">
                    <path d="M12 2.5c-3 0-4.5 2-4.5 5v11c0 2 1.8 3 4.5 3s4.5-1 4.5-3v-11c0-3-1.5-5-4.5-5z"
                          fill="${color}" stroke="${dark}" stroke-width="1"/>
                    <rect x="8.5" y="5" width="7" height="3.4" rx="1" fill="#dbeafe"/></svg>`;
        }
    }

    function typeIcon(vehicleId, heading, alerting, showBadge) {
        const v = vehicles.get(vehicleId) || {};
        const type = v.type || "CAR";
        const color = alerting ? "#e24b4a" : (TYPE_COLOR[type] || "#378add");
        const sel = vehicleId === selected ? " selected" : "";
        const badge = showBadge ? `<span class="veh-badge">${v.plateNo != null ? v.plateNo : "?"}</span>` : "";
        return L.divIcon({
            html: `<div class="veh-mk${sel}">
                     <div class="veh-ico${alerting ? " alert" : ""}" style="transform:rotate(${heading}deg)">${vehSvg(type, color)}</div>
                     ${badge}
                   </div>`,
            className: "", iconSize: [30, 30], iconAnchor: [15, 15]
        });
    }

    /** Marker ikonlarını (seçim/logo) her iki haritada tazele. */
    function refreshMarkers() {
        liveMarkers.forEach((m, id) => {
            if (m._alerting) return;
            const p = pos.get(id);
            m.setIcon(typeIcon(id, p ? p.heading || 0 : 0, false, false));
        });
        ctrlMarkers.forEach((m, id) => {
            const p = pos.get(id);
            m.setIcon(typeIcon(id, p ? p.heading || 0 : 0, false, true));
        });
    }

    function popup(p) {
        const v = vehicles.get(p.vehicleId);
        const j = journey.get(p.vehicleId);
        return `<b>${v ? v.plate : "#" + p.vehicleId}</b><br>Hız: ${p.speedKmh ?? "-"} km/s`
            + (j && j.destination ? `<br>${journeyText(j)}` : "");
    }

    // ── Seçim ───────────────────────────────────────────────────────────────
    function select(vehicleId) {
        const changedSelection = vehicleId !== selected;
        selected = vehicleId;
        if (changedSelection && vehicleId == null && routeLayer) {
            live.removeLayer(routeLayer);
            routeLayer = null;
        }
        if (selected != null) ctrl.doubleClickZoom.disable();
        else ctrl.doubleClickZoom.enable();

        document.querySelectorAll("#vehicleList .row").forEach(r =>
            r.classList.toggle("sel", Number(r.dataset.vid) === selected));
        refreshMarkers();

        const info = document.getElementById("selInfo");
        const btn = document.getElementById("releaseBtn");
        const v = selected != null ? vehicles.get(selected) : null;
        if (v) {
            const isManual = manual.has(selected);
            const j = journey.get(selected);
            const p = pos.get(selected);
            const speed = p ? `${p.speedKmh} km/s` : "-";
            const heli = j && j.flying ? '<span class="pill heli">HELİKOPTER</span>' : "";
            info.innerHTML = `<b>${v.plate}</b>${heli}` +
                `<span class="pill ${isManual ? "manual" : "auto"}">${isManual ? "ELLE" : "OTOMATİK"}</span>` +
                `<div class="meta" style="margin-top:4px">Hız: ${speed}${j ? " · " + journeyText(j) : ""}</div>`;
            btn.disabled = !isManual;
            if (p) { ctrl.panTo([p.lat, p.lon]); }
            if (changedSelection) showPlannedRoute(selected);
            const row = document.querySelector(`#vehicleList .row[data-vid="${selected}"]`);
            if (row) row.scrollIntoView({ block: "nearest" });
        } else {
            info.textContent = "";
            btn.disabled = true;
        }
    }

    // Seçili aracın GİDECEĞİ rota (mevcut konum -> hedef). Helikopterde düz uçuş hattı.
    async function showPlannedRoute(vehicleId) {
        if (routeLayer) { live.removeLayer(routeLayer); routeLayer = null; }
        try {
            const res = await fetch(`/api/v1/control/${vehicleId}/route`, { headers: auth() });
            if (!res.ok) return;
            const pts = await res.json();
            if (!Array.isArray(pts) || pts.length < 2) return;
            const j = journey.get(vehicleId);
            const color = j && j.flying ? "#a855f7" : "#378add";
            routeLayer = L.polyline(pts, { color, weight: 3, opacity: .85, dashArray: "6 6" }).addTo(live);
            live.fitBounds(routeLayer.getBounds().pad(0.25));
        } catch (_) { /* yoksay */ }
    }

    // ── Kontrol (gateway proxy) ─────────────────────────────────────────────
    async function onCtrlDblClick(e) {
        if (selected == null) return;   // seçim yok -> Leaflet zaten yakınlaştırdı
        const lat = +e.latlng.lat.toFixed(6), lon = +e.latlng.lng.toFixed(6);
        const plate = (vehicles.get(selected) || {}).plate || ("#" + selected);
        try {
            const res = await fetch(`/api/v1/control/${selected}/position`, {
                method: "POST",
                headers: { ...auth(), "Content-Type": "application/json" },
                body: JSON.stringify({ lat, lon })
            });
            if (!res.ok) { flash("Taşıma başarısız."); return; }
            const r = await res.json().catch(() => ({}));
            const plat = r.lat != null ? r.lat : lat;
            const plon = r.lon != null ? r.lon : lon;

            manual.add(selected);
            const p = { ...(pos.get(selected) || {}), vehicleId: selected, lat: plat, lon: plon, speedKmh: 0 };
            pos.set(selected, p);
            drawLive(p); drawCtrl(p);
            select(selected);

            if (r.snapped) {
                flash(`⚠ Yol dışına tıklandı — ${plate} en yakın yola oturtuldu (${r.offRoadMeters} m).`);
            } else if (r.flying) {
                flash(`🚁 ${plate} taşındı (helikopter, her yere konabilir).`);
            } else {
                flash(`${plate} taşındı → sol harita güncelleniyor.`);
            }
        } catch (_) { flash("Taşıma başarısız (bağlantı)."); }
    }

    async function release() {
        if (selected == null) return;
        await fetch(`/api/v1/control/${selected}/position`, { method: "DELETE", headers: auth() });
        manual.delete(selected);
        select(selected);
        flash("Araç otomatiğe döndü.");
    }

    // Sevkiyat durumu: hedef, kalan km, ETA, park/elle/uçuş bayrakları.
    async function refreshDispatch() {
        if (!token) return;
        try {
            const res = await fetch("/api/v1/control/state", { headers: auth() });
            if (!res.ok) return;
            manual.clear();
            (await res.json()).forEach(s => {
                if (s.manual) manual.add(s.vehicleId);
                journey.set(s.vehicleId, {
                    destination: s.destination, remainingKm: s.remainingKm,
                    etaMinutes: s.etaMinutes, parked: s.parked, flying: s.flying
                });
            });
            refreshMarkers();
            renderJourneyMeta();
            if (selected != null) select(selected);
        } catch (_) { /* yoksay */ }
    }

    function renderJourneyMeta() {
        document.querySelectorAll("#vehicleList .row").forEach(row => {
            const j = journey.get(Number(row.dataset.vid));
            const meta = row.querySelector(".meta");
            if (meta) meta.textContent = journeyText(j) || meta.dataset.model || "";
        });
    }

    function journeyText(j) {
        if (!j) return "";
        if (j.parked) return (j.flying ? "🚁 " : "🅿 ") + "varışta · durdu";
        if (j.destination == null) return "rota bekleniyor…";
        return `→ ${j.destination} · ${j.remainingKm} km`
            + (j.etaMinutes >= 0 ? ` · ~${j.etaMinutes} dk` : "");
    }

    // ── Geofence bölgeleri ──────────────────────────────────────────────────
    async function loadGeofences() {
        try {
            const res = await fetch("/api/v1/geofences", { headers: auth() });
            if (!res.ok) return;
            (await res.json()).forEach(g => {
                const exclusion = g.kind === "EXCLUSION";
                L.geoJSON(JSON.parse(g.geojson), {
                    style: {
                        color: exclusion ? "#e24b4a" : "#5dcaa5",
                        weight: 2, fillOpacity: 0.12, dashArray: exclusion ? null : "4"
                    }
                }).bindTooltip(`${g.name} (${exclusion ? "yasak" : "izinli"})`).addTo(live);
            });
        } catch (_) { /* yoksay */ }
    }

    // ── Sürücü skorları ─────────────────────────────────────────────────────
    async function loadScores() {
        try {
            const res = await fetch("/api/v1/drivers/scores?days=30&limit=5", { headers: auth() });
            if (!res.ok) return;
            const el = document.getElementById("scoreList");
            el.innerHTML = "";
            (await res.json()).forEach((d, i) => {
                const s = Number(d.score);
                const cls = s >= 85 ? "good" : s >= 65 ? "mid" : "bad";
                const row = document.createElement("div");
                row.className = "row scoreRow";
                row.innerHTML = `<span class="rank">${i + 1}</span>` +
                    `<span class="nm">${d.name}<div class="meta">${d.distanceKm} km · ${d.violationCount} ihlal</div></span>` +
                    `<span class="sc ${cls}">${s.toFixed(1)}</span>`;
                el.appendChild(row);
            });
        } catch (_) { /* yoksay */ }
    }

    // ── İhlaller ────────────────────────────────────────────────────────────
    function onViolation(v) {
        alerts++;
        document.getElementById("statAlerts").textContent = alerts;

        const m = liveMarkers.get(v.vehicleId);
        const p = pos.get(v.vehicleId);
        if (m && p) {
            m._alerting = true;
            m.setIcon(typeIcon(v.vehicleId, p.heading || 0, true, false));
            setTimeout(() => {
                m._alerting = false;
                const cur = pos.get(v.vehicleId) || p;
                m.setIcon(typeIcon(v.vehicleId, cur.heading || 0, false, false));
            }, 4000);
        }
        if (v.lat != null && v.lon != null) {
            const ring = L.circle([v.lat, v.lon], { radius: 220, color: "#e24b4a", weight: 2, fillOpacity: .15 }).addTo(live);
            setTimeout(() => live.removeLayer(ring), 8000);
        }
        const veh = vehicles.get(v.vehicleId);
        const el = document.getElementById("alertList");
        const row = document.createElement("div");
        row.className = "row alertRow";
        const time = v.occurredAt ? new Date(v.occurredAt).toLocaleTimeString("tr-TR") : "";
        row.innerHTML = `<span class="code">${v.ruleCode || "İHLAL"}</span>` +
            `<div class="meta">${veh ? veh.plate : "#" + v.vehicleId} · ${time}</div>`;
        row.addEventListener("click", () => select(v.vehicleId));
        el.prepend(row);
        while (el.children.length > 40) el.removeChild(el.lastChild);
    }

    // ── Durum satırı ────────────────────────────────────────────────────────
    function setStatus(text, ok) {
        const el = document.getElementById("statusLine");
        el.textContent = text;
        el.style.color = ok ? "#5dcaa5" : "#e24b4a";
    }
    let flashTimer = null;
    function flash(text) {
        const el = document.getElementById("statusLine");
        const prev = el.textContent, prevColor = el.style.color;
        el.textContent = text; el.style.color = "#f0997b";
        clearTimeout(flashTimer);
        flashTimer = setTimeout(() => { el.textContent = prev; el.style.color = prevColor; }, 3500);
    }
})();
