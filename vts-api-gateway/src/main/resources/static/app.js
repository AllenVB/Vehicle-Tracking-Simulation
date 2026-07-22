/*
 * VTS — tek sayfa, iki harita (2/5/5 grid), tek servis (gateway).
 *  - Sol harita  : canlı filo (OpenStreetMap) — tipe göre logo, yöne dönük + benzin istasyonları
 *  - Sağ harita  : operatör (CartoDB)          — logo + plaka no, çift tıkla taşı
 *  - Kara aracı yalnızca yol üzerine taşınabilir: yol dışı tıklama reddedilir ve araç
 *    yerinde kalır. Helikopterler her yere konabilir.
 *  - Taşıma bir kilit değil, ışınlanmadır: araç bırakıldığı noktadan yeni bir hedefe
 *    kendi hızıyla yola çıkar (durmaz, geldiği yöne dönmez).
 */
(function () {
    "use strict";

    let token = null;
    let live = null, ctrl = null;
    const liveMarkers = new Map();
    const ctrlMarkers = new Map();
    const pos = new Map();
    const vehicles = new Map();               // vehicleId -> {plate, plateNo, type, model}
    const byPlateNo = new Map();
    const journey = new Map();
    /** vehicleId -> son yolculukların ortalama puanı (1..10). */
    const vehicleScore = new Map();
    /** driverId -> {name, score} — harita balonu ve skor tablosu aynı kaynaktan beslenir. */
    const driverScores = new Map();
    let fuelStations = [];
    const messages = new Map();               // vehicleId -> [{category, body, at}]
    let selected = null;
    let geofenceLayer = null;          // yeniden çizilebilsin diye tutuluyor
    let commandCatalogue = [];
    let cmdTimer = null;               // seçili aracın komut geçmişi anketi
    const replay = { points: [], layer: null, marker: null, idx: 0, timer: null, tripId: null };
    const draw = { on: false, points: [], layer: null };
    let routeLayer = null;
    let alerts = 0, fineTotal = 0, fitted = false;

    // Tip -> renk: otomobil mavi, tır sarı, motor beyaz, helikopter mor
    const TYPE_COLOR = { CAR: "#2b7fff", TRUCK: "#f5b301", MOTORCYCLE: "#f8fafc", HELICOPTER: "#a855f7" };
    /** Bu seviyenin altında araç sarı yanıp söner ve en yakın istasyona yönelir (sunucuyla aynı eşik). */
    const LOW_FUEL_PCT = 25;

    // İhlal kodları: Türkçe ad + TL ceza
    const RULES = {
        SPEED_LIMIT:        { tr: "Hız Limiti",          fine: 1500 },
        SUSTAINED_SPEEDING: { tr: "Sürekli Hız Aşımı",   fine: 2500 },
        HARSH_BRAKING:      { tr: "Sert Fren",           fine: 1000 },
        GEOFENCE_ENTER:     { tr: "Yasak Bölge Girişi",  fine: 5000 },
        GEOFENCE_EXIT:      { tr: "Bölge Çıkışı",        fine: 1000 },
        IDLING:             { tr: "Rölanti",             fine: 500 },
        LOW_BATTERY:        { tr: "Düşük Batarya",       fine: 750 },
        LOW_FUEL:           { tr: "Düşük Yakıt",         fine: 750 }
    };
    const tl = n => n.toLocaleString("tr-TR") + " ₺";

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
                method: "POST", headers: { "Content-Type": "application/json" },
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
        loadFuelStations();

        document.getElementById("plateNo").addEventListener("input", e => {
            const n = parseInt(e.target.value, 10);
            select(byPlateNo.get(n) ?? null);
        });
        loadProvinces();
        document.getElementById("routeBtn").addEventListener("click", openRoutePicker);
        document.getElementById("routeCancel").addEventListener("click", closeRoutePicker);
        document.getElementById("routeGo").addEventListener("click", createRoute);
        document.getElementById("msgSend").addEventListener("click", sendMessage);
        document.getElementById("msgText").addEventListener("keydown",
            e => { if (e.key === "Enter") sendMessage(); });

        loadCommandCatalogue();
        document.getElementById("cmdSend").addEventListener("click", sendCommand);
        document.getElementById("replayBtn").addEventListener("click", () => startReplay(selected));
        document.getElementById("replayPlay").addEventListener("click", toggleReplayPlay);
        document.getElementById("replayClose").addEventListener("click", stopReplay);
        document.getElementById("replayRange").addEventListener("input", e => {
            pauseReplay();
            showReplayFrame(Number(e.target.value));
        });

        document.getElementById("zoneBtn").addEventListener("click", toggleDraw);
        document.getElementById("zoneSave").addEventListener("click", saveZone);
        document.getElementById("zoneCancel").addEventListener("click", cancelDraw);
        ctrl.on("click", onDrawClick);

        loadMaintenance();
        setInterval(loadMaintenance, 60000);
    }

    function initMaps() {
        live = L.map("mapLive", { zoomControl: true, attributionControl: false }).setView([39.0, 35.0], 6);
        L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19 }).addTo(live);
        ctrl = L.map("mapCtrl", { zoomControl: true, attributionControl: false }).setView([39.0, 35.0], 6);
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
                model: [v.make, v.model].filter(Boolean).join(" "),
                driverId: v.currentDriverId
            });
            byPlateNo.set(no, v.id);
            const row = document.createElement("div");
            row.className = "row";
            row.dataset.vid = v.id;
            row.innerHTML = `<b>${v.plate}</b><div class="meta" data-model="${vehicles.get(v.id).model}">${vehicles.get(v.id).model}</div>`;
            row.addEventListener("click", () => { document.getElementById("plateNo").value = no; select(v.id); });
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
            stomp.subscribe("/topic/vehicle-messages", m => onIncomingMessage(JSON.parse(m.body)));
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
            const b = L.latLngBounds([...pos.values()].map(p => [p.lat, p.lon])).pad(0.15);
            live.fitBounds(b); ctrl.fitBounds(b);
        }
    }

    function drawLive(p) {
        let m = liveMarkers.get(p.vehicleId);
        const icon = typeIcon(p.vehicleId, p.heading || 0, false, false);
        if (!m) {
            m = L.marker([p.lat, p.lon], { icon, zIndexOffset: 1000 }).addTo(live);
            m.on("click", () => select(p.vehicleId));
            liveMarkers.set(p.vehicleId, m);
        } else {
            m.setLatLng([p.lat, p.lon]);
            if (!m._alerting) m.setIcon(icon);
        }
        m.bindPopup(popup(p));
    }

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

    // Tipe göre yukarı (kuzey) bakan detaylı silüet; heading ile döndürülür.
    // Gölge + gövde + parlama + cam/tekerlek/detay ile derinlik.
    function vehSvg(type, color) {
        const d = "#0b1016", g = "#141a20";
        switch (type) {
            case "TRUCK":
                return `<svg viewBox="0 0 34 42" width="29" height="36">
                    <rect x="4.6" y="24" width="2.6" height="6" rx="1.2" fill="${g}"/>
                    <rect x="26.8" y="24" width="2.6" height="6" rx="1.2" fill="${g}"/>
                    <rect x="4.6" y="32" width="2.6" height="6" rx="1.2" fill="${g}"/>
                    <rect x="26.8" y="32" width="2.6" height="6" rx="1.2" fill="${g}"/>
                    <rect x="6.8" y="13" width="20.4" height="27" rx="2.2" fill="${color}" stroke="${d}" stroke-width="1.4"/>
                    <rect x="9" y="15" width="16" height="23" rx="1" fill="#ffffff" opacity=".12"/>
                    <line x1="6.8" y1="26.5" x2="27.2" y2="26.5" stroke="${d}" stroke-width=".8" opacity=".5"/>
                    <rect x="6.2" y="6.5" width="2.2" height="5" rx="1" fill="${g}"/>
                    <rect x="25.6" y="6.5" width="2.2" height="5" rx="1" fill="${g}"/>
                    <rect x="7.8" y="2" width="18.4" height="11" rx="2.6" fill="${color}" stroke="${d}" stroke-width="1.4"/>
                    <rect x="9.6" y="3.4" width="14.8" height="4.4" rx="1.2" fill="#2b3540"/>
                    <rect x="9.6" y="3.4" width="14.8" height="2" rx="1" fill="#8fb3d9" opacity=".5"/></svg>`;
            case "MOTORCYCLE":
                return `<svg viewBox="0 0 26 40" width="22" height="34">
                    <ellipse cx="13" cy="7" rx="3.5" ry="4.1" fill="${g}"/>
                    <ellipse cx="13" cy="7" rx="1.6" ry="2" fill="#3a4652"/>
                    <ellipse cx="13" cy="33" rx="3.5" ry="4.1" fill="${g}"/>
                    <ellipse cx="13" cy="33" rx="1.6" ry="2" fill="#3a4652"/>
                    <path d="M10.6 8h4.8l-.6 6.5h-3.6z" fill="${color}" stroke="${d}" stroke-width="1"/>
                    <rect x="10.7" y="19" width="4.6" height="13" rx="2.2" fill="${color}" stroke="${d}" stroke-width="1"/>
                    <rect x="7.4" y="5.6" width="11.2" height="2.2" rx="1.1" fill="${color}" stroke="${d}" stroke-width=".7"/>
                    <ellipse cx="13" cy="17" rx="2.6" ry="3.2" fill="#cfd8e0" stroke="${d}" stroke-width=".5"/></svg>`;
            case "HELICOPTER":
                return `<svg viewBox="0 0 40 40" width="34" height="34">
                    <circle cx="20" cy="13" r="15.5" fill="${color}" opacity=".08"/>
                    <line x1="12" y1="26" x2="28" y2="26" stroke="${d}" stroke-width="1.5" stroke-linecap="round"/>
                    <line x1="14.5" y1="21" x2="14.5" y2="26.5" stroke="${d}" stroke-width="1.3"/>
                    <line x1="25.5" y1="21" x2="25.5" y2="26.5" stroke="${d}" stroke-width="1.3"/>
                    <path d="M18.6 22h2.8l1 15h-4.8z" fill="${color}" stroke="${d}" stroke-width="1"/>
                    <line x1="16" y1="36.5" x2="24" y2="36.5" stroke="${d}" stroke-width="2" stroke-linecap="round"/>
                    <path d="M20 6c-5 0-7.6 5-7.6 11 0 5 2.6 8 7.6 8s7.6-3 7.6-8C27.6 11 25 6 20 6z" fill="${color}" stroke="${d}" stroke-width="1.4"/>
                    <path d="M20 8c-3.4 0-5.4 2.9-5.5 6.4h11C25.4 10.9 23.4 8 20 8z" fill="#dbeafe"/>
                    <path d="M20 8c-3.4 0-5.4 2.9-5.5 6.4h5.5z" fill="#ffffff" opacity=".35"/>
                    <line x1="4.5" y1="13" x2="35.5" y2="13" stroke="${color}" stroke-width="2.6" stroke-linecap="round"/>
                    <line x1="9" y1="6" x2="31" y2="20" stroke="${color}" stroke-width="1.5" stroke-linecap="round" opacity=".4"/>
                    <line x1="9" y1="20" x2="31" y2="6" stroke="${color}" stroke-width="1.5" stroke-linecap="round" opacity=".4"/>
                    <circle cx="20" cy="13" r="1.8" fill="#fff" stroke="${d}" stroke-width=".7"/></svg>`;
            default: // CAR
                return `<svg viewBox="0 0 34 40" width="28" height="33">
                    <rect x="6.2" y="9.5" width="2.2" height="4.5" rx="1" fill="${g}"/>
                    <rect x="25.6" y="9.5" width="2.2" height="4.5" rx="1" fill="${g}"/>
                    <rect x="6.2" y="26" width="2.2" height="4.5" rx="1" fill="${g}"/>
                    <rect x="25.6" y="26" width="2.2" height="4.5" rx="1" fill="${g}"/>
                    <path d="M17 2.5c-5.2 0-7.7 3.3-8 8.2l-.6 16.8c-.2 5.3 2.8 8.3 8.6 8.3s8.8-3 8.6-8.3l-.6-16.8c-.3-4.9-2.8-8.2-8-8.2z" fill="${color}" stroke="${d}" stroke-width="1.5"/>
                    <path d="M17 4c-3.9 0-5.9 2.5-6.3 6.8h12.6C22.9 6.5 20.9 4 17 4z" fill="#ffffff" opacity=".22"/>
                    <path d="M11 11.4h12l-1.3 4.4h-9.4z" fill="#cfe3ff" stroke="${d}" stroke-width=".5"/>
                    <rect x="11.4" y="16.6" width="11.2" height="8" rx="2.2" fill="#ffffff" opacity=".16"/>
                    <path d="M12.3 25h9.4l1.2 3.6c.2 3.3-1.9 5-5.9 5s-6.1-1.7-5.9-5z" fill="#bcd4ef" stroke="${d}" stroke-width=".5"/>
                    <path d="M8.8 12.6l-2.4.6v2.2l2.4.6z" fill="${color}" stroke="${d}" stroke-width=".6"/>
                    <path d="M25.2 12.6l2.4.6v2.2l-2.4.6z" fill="${color}" stroke="${d}" stroke-width=".6"/></svg>`;
        }
    }

    function typeIcon(vehicleId, heading, alerting, showBadge) {
        const v = vehicles.get(vehicleId) || {};
        const type = v.type || "CAR";
        const color = alerting ? "#e24b4a" : (TYPE_COLOR[type] || "#2b7fff");
        const sel = vehicleId === selected ? " selected" : "";
        const badge = showBadge ? `<span class="veh-badge">${v.plateNo != null ? v.plateNo : "?"}</span>` : "";
        // Düşük yakıt, aracı boyamak yerine yanıp sönen sarı bir ışıkla gösterilir: tırın
        // gövde rengi zaten sarı, ve tipi görebilmek yakıt uyarısı kadar önemli. İhlal
        // uyarısı (kırmızı) varsa o öncelikli — iki animasyon üst üste okunmaz olurdu.
        const low = isLowFuel(vehicleId) && !alerting;
        return L.divIcon({
            html: `<div class="veh-mk${sel}">
                     <div class="veh-ico${alerting ? " alert" : ""}${low ? " lowfuel" : ""}" style="transform:rotate(${heading}deg)">${vehSvg(type, color)}</div>
                     ${badge}
                   </div>`,
            className: "", iconSize: [36, 36], iconAnchor: [18, 18]
        });
    }

    /** Depo eşiğin altında mı? Yakıt bildirmeyen araç (ör. helikopter) düşük sayılmaz. */
    function isLowFuel(vehicleId) {
        const p = pos.get(vehicleId);
        return !!p && p.fuelPct != null && p.fuelPct <= LOW_FUEL_PCT;
    }

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
        const msgs = messages.get(p.vehicleId);
        const lastMsg = msgs && msgs.length
            ? `<br><span style="color:#ffd27f">⚠ ${msgs[0].category}:</span> ${msgs[0].body}` : "";
        return `<b>${v ? v.plate : "#" + p.vehicleId}</b>` +
            `<br><small>${v ? v.model : ""}</small><br>Hız: ${p.speedKmh ?? "-"} km/s` +
            driverScoreLine(v) +
            (j && j.destination ? `<br>${journeyText(j)}` : "") + lastMsg;
    }

    /** Balondaki sürücü puanı satırı: aracı kullanan sürücü ve yolculuk ortalaması. */
    function driverScoreLine(v) {
        if (!v || v.driverId == null) return "";
        const d = driverScores.get(v.driverId);
        // Puanlanmış yolculuğu olmayan sürücü listede yok; uydurmak yerine bunu söyle.
        if (!d) return `<br>Sürücü puanı: <span style="color:#8aa0b4">henüz yok</span>`;
        const renk = d.score >= 8 ? "#3ddc84" : (d.score >= 6 ? "#e0b800" : "#e24b4a");
        return `<br>Sürücü puanı: <b style="color:${renk}">${d.score.toFixed(1)}/10</b>` +
            `<br><small>${d.name}</small>`;
    }

    /** Skorlar yenilenince açık balonlar da tazelensin — yoksa eski puanı gösterirler. */
    function refreshOpenPopups() {
        liveMarkers.forEach((m, id) => {
            if (m.isPopupOpen && m.isPopupOpen()) {
                const p = pos.get(id);
                if (p) m.setPopupContent(popup(p));
            }
        });
    }

    // ── Benzin istasyonları ─────────────────────────────────────────────────
    async function loadFuelStations() {
        try {
            const res = await fetch("/api/v1/fuel-stations", { headers: auth() });
            if (!res.ok) return;
            fuelStations = await res.json();
            fuelStations.forEach(f => {
                L.marker([f.lat, f.lon], { icon: fuelIcon(), zIndexOffset: 0, interactive: true })
                    .bindTooltip(`⛽ ${f.name}`).addTo(live);
            });
        } catch (_) { /* yoksay */ }
    }

    function fuelIcon() {
        return L.divIcon({
            html: `<div class="fuel-mk"><svg viewBox="0 0 24 24" width="16" height="16">
                     <rect x="5" y="3" width="9" height="18" rx="1.6" fill="#0f9d58" stroke="#06331d" stroke-width="1"/>
                     <rect x="6.6" y="5" width="5.8" height="4.4" rx=".8" fill="#eafaf0"/>
                     <path d="M14 8h2.2a1.6 1.6 0 0 1 1.6 1.6V15a1.4 1.4 0 0 0 1.4 1.4" fill="none" stroke="#06331d" stroke-width="1.3"/>
                   </svg></div>`,
            className: "", iconSize: [16, 16], iconAnchor: [9, 18]
        });
    }

    function haversineKm(aLat, aLon, bLat, bLon) {
        const R = 6371, rad = x => x * Math.PI / 180;
        const dLat = rad(bLat - aLat), dLon = rad(bLon - aLon);
        const s = Math.sin(dLat / 2) ** 2 + Math.cos(rad(aLat)) * Math.cos(rad(bLat)) * Math.sin(dLon / 2) ** 2;
        return 2 * R * Math.asin(Math.sqrt(s));
    }

    function nearestFuel(lat, lon) {
        let best = null, bd = Infinity;
        for (const f of fuelStations) {
            const dkm = haversineKm(lat, lon, f.lat, f.lon);
            if (dkm < bd) { bd = dkm; best = f; }
        }
        return best ? { f: best, km: bd } : null;
    }

    // ── Seçim ───────────────────────────────────────────────────────────────
    function select(vehicleId) {
        const changedSelection = vehicleId !== selected;
        selected = vehicleId;
        if (changedSelection && vehicleId == null && routeLayer) { live.removeLayer(routeLayer); routeLayer = null; }
        if (selected != null) ctrl.doubleClickZoom.disable(); else ctrl.doubleClickZoom.enable();

        document.querySelectorAll("#vehicleList .row").forEach(r =>
            r.classList.toggle("sel", Number(r.dataset.vid) === selected));
        refreshMarkers();

        const info = document.getElementById("selInfo");
        const v = selected != null ? vehicles.get(selected) : null;
        // Rota Oluştur butonu ve uyarı kutusu yalnızca bir araç seçiliyken.
        document.getElementById("routeBtn").style.display = v && !draw.on ? "block" : "none";
        document.getElementById("msgBox").style.display = v ? "block" : "none";
        document.getElementById("cmdBox").style.display = v ? "block" : "none";
        if (changedSelection) closeRoutePicker();
        if (changedSelection) {
            // Komut geçmişi araca özel: seçim değişince eski aracın anketini durdur, yoksa
            // panel bir aracın komutlarını gösterirken başkasınınkiyle güncellenir.
            if (cmdTimer) { clearInterval(cmdTimer); cmdTimer = null; }
            if (replay.tripId != null) stopReplay();
            if (v) {
                loadCommands(selected);
                // Komut PENDING -> SENT -> ANSWERED arasında saniyeler içinde geçiyor;
                // 3 sn'lik anket bunu canlı gösterecek kadar sık, sunucuyu yormayacak kadar seyrek.
                cmdTimer = setInterval(() => loadCommands(selected), 3000);
            } else {
                document.getElementById("cmdList").innerHTML = "";
            }
        }
        if (v) {
            const j = journey.get(selected);
            const p = pos.get(selected);
            const heli = j && j.flying ? '<span class="pill heli">HELİKOPTER</span>' : "";
            if (changedSelection) loadMessages(selected);
            else renderMessages(selected);
            let fuelLine = "";
            if (p && v.type !== "HELICOPTER") {
                const nf = nearestFuel(p.lat, p.lon);
                if (nf) fuelLine = `<div class="meta">⛽ En yakın benzin: ${nf.km.toFixed(1)} km · ${nf.f.brand}</div>`;
            }
            // Aracın güncel puanı: son yolculuklarının ortalaması, 10 üzerinden. Park
            // şartına bağlı DEĞİL -- şehirlerarası seferler saatler sürdüğü için park anını
            // yakalamak neredeyse imkânsızdı ve puan pratikte hiç görünmüyordu.
            let scoreLine = "";
            const sc = vehicleScore.get(selected);
            if (sc != null) {
                // 8+ iyi, 6-7 orta, altı kötü — renk, puanı okumadan da durumu anlatsın.
                const renk = sc >= 8 ? "#3ddc84" : (sc >= 6 ? "var(--fuel-low)" : "var(--alert)");
                scoreLine = `<div class="meta" style="color:${renk};font-weight:600">` +
                    `★ Güncel puan: ${sc.toFixed(1)}/10</div>`;
            } else {
                scoreLine = '<div class="meta">★ Henüz puanlanmış yolculuk yok</div>';
            }
            loadVehicleScore(selected);   // kendini kısıtlar
            // Depo seviyesi: aracın neden yanıp söndüğünü haritaya bakmadan da açıklar.
            let tankLine = "";
            if (p && p.fuelPct != null) {
                const low = p.fuelPct <= LOW_FUEL_PCT;
                tankLine = `<div class="meta"${low ? ' style="color:var(--fuel-low);font-weight:600"' : ""}>` +
                    `${low ? "⚠ " : ""}Depo: %${p.fuelPct}${low ? " — istasyona yöneliyor" : ""}</div>`;
            }
            info.innerHTML = `<b>${v.plate}</b>${heli}` +
                `<div class="meta" style="margin-top:4px">${v.model} · ${p ? p.speedKmh + " km/s" : "-"}${j ? " · " + journeyText(j) : ""}</div>` +
                scoreLine + tankLine + fuelLine;
            if (p) ctrl.panTo([p.lat, p.lon]);
            if (changedSelection) showPlannedRoute(selected);
            const row = document.querySelector(`#vehicleList .row[data-vid="${selected}"]`);
            if (row) row.scrollIntoView({ block: "nearest" });
        } else {
            info.textContent = "";
        }
    }

    // Seçili aracın GİDECEĞİ rota: akan kesikli çizgi + parıltı + hedef bayrağı.
    async function showPlannedRoute(vehicleId) {
        if (routeLayer) { live.removeLayer(routeLayer); routeLayer = null; }
        try {
            const res = await fetch(`/api/v1/control/${vehicleId}/route`, { headers: auth() });
            if (!res.ok) return;
            const pts = await res.json();
            if (!Array.isArray(pts) || pts.length < 2) return;
            const j = journey.get(vehicleId);
            const color = j && j.flying ? "#a855f7" : "#2b7fff";
            routeLayer = L.layerGroup().addTo(live);
            L.polyline(pts, { color, weight: 8, opacity: .22 }).addTo(routeLayer);                 // parıltı
            L.polyline(pts, { color, weight: 3, opacity: .95, dashArray: "1 9",
                              lineCap: "round", className: "route-flow" }).addTo(routeLayer);       // akan hat
            L.marker(pts[pts.length - 1], { icon: destIcon(color), zIndexOffset: 1500 })
                .bindTooltip(j && j.destination ? "🏁 " + j.destination : "🏁 Hedef").addTo(routeLayer);
            live.fitBounds(L.polyline(pts).getBounds().pad(0.25));
        } catch (_) { /* yoksay */ }
    }

    function destIcon(color) {
        return L.divIcon({
            html: `<div class="dest-pin" style="--c:${color}"></div>`,
            className: "", iconSize: [16, 16], iconAnchor: [8, 8]
        });
    }

    // ── Kontrol (gateway proxy) ─────────────────────────────────────────────
    async function onCtrlDblClick(e) {
        // Bölge çizerken çift tık bir köşe koyma jestidir, araç taşıma değil.
        if (draw.on) return;
        if (selected == null) return;
        const lat = +e.latlng.lat.toFixed(6), lon = +e.latlng.lng.toFixed(6);
        const plate = (vehicles.get(selected) || {}).plate || ("#" + selected);
        try {
            const res = await fetch(`/api/v1/control/${selected}/position`, {
                method: "POST", headers: { ...auth(), "Content-Type": "application/json" },
                body: JSON.stringify({ lat, lon })
            });
            if (!res.ok) { flash("Taşıma başarısız."); return; }
            const r = await res.json().catch(() => ({}));

            // Taşındığını yalnızca sunucu açıkça söylerse kabul et. `=== false` yerine
            // `!== true`: alanı hiç taşımayan bir yanıt (ör. eski bir simülatör imajı)
            // aksi halde başarı sayılır ve taşınmamış aracı taşınmış gibi gösterirdik.
            if (r.moved !== true) {
                if (r.reason === "OFF_ROAD") {
                    flash(`⛔ Bu noktaya gidilemiyor — yol yok (en yakın yol ${r.offRoadMeters} m uzakta). ${plate} yerinde kaldı.`);
                } else if (r.reason === "LOOP_VEHICLE") {
                    flash(`⛔ ${plate} yasak bölge turunda; bu araç taşınamaz.`);
                } else if (r.reason === "NO_ROUTE_FROM_HERE") {
                    flash(`⛔ ${plate} için bu noktadan hedefine yol rotası bulunamadı.`);
                } else if (r.reason === "ROUTING_UNAVAILABLE") {
                    flash("⛔ Rota motoru şu an yanıt vermiyor, araç taşınamadı.");
                } else {
                    flash(`⛔ ${plate} taşınamadı.`);
                }
                return;
            }

            // Taşındı: araç yoluna devam ediyor, hızını sunucudan al (0 varsayma).
            const p = {
                ...(pos.get(selected) || {}),
                vehicleId: selected,
                lat: r.lat != null ? r.lat : lat,
                lon: r.lon != null ? r.lon : lon,
                speedKmh: r.speedKmh != null ? Math.round(r.speedKmh) : (pos.get(selected) || {}).speedKmh
            };
            pos.set(selected, p);
            drawLive(p); drawCtrl(p); select(selected);
            // Hedefe yeni rota çizildi; select() yalnızca seçim değişince çiziyor.
            showPlannedRoute(selected);

            const hedef = r.destination ? ` → ${r.destination}` : "";
            if (r.flying) flash(`🚁 ${plate} taşındı (helikopter, her yere konabilir) — uçuşuna devam ediyor${hedef}.`);
            else flash(`✔ ${plate} taşındı — buradan yola devam ediyor${hedef}.`);
        } catch (_) { flash("Taşıma başarısız (bağlantı)."); }
    }

    // ── Rota Oluştur (hedef il seç) ─────────────────────────────────────────
    async function loadProvinces() {
        try {
            const res = await fetch("/api/v1/control/provinces", { headers: auth() });
            if (!res.ok) return;
            const sel = document.getElementById("provinceSelect");
            sel.innerHTML = "";
            (await res.json()).forEach(name => {
                const o = document.createElement("option");
                o.value = name; o.textContent = name;
                sel.appendChild(o);
            });
        } catch (_) { /* yoksay */ }
    }

    function openRoutePicker() {
        if (selected == null) return;
        document.getElementById("routeBtn").style.display = "none";
        document.getElementById("routePicker").style.display = "block";
    }
    function closeRoutePicker() {
        document.getElementById("routePicker").style.display = "none";
        document.getElementById("routeBtn").style.display = selected != null ? "block" : "none";
    }

    async function createRoute() {
        if (selected == null) return;
        const province = document.getElementById("provinceSelect").value;
        const plate = (vehicles.get(selected) || {}).plate || ("#" + selected);
        const go = document.getElementById("routeGo");
        go.disabled = true; go.textContent = "Oluşturuluyor…";
        try {
            const res = await fetch(`/api/v1/control/${selected}/destination`, {
                method: "POST", headers: { ...auth(), "Content-Type": "application/json" },
                body: JSON.stringify({ province })
            });
            if (!res.ok) { flash("Rota oluşturulamadı — bu araç için hedefe yol rotası bulunamadı."); return; }
            setTimeout(() => showPlannedRoute(selected), 400);   // yeni rotayı çiz
            flash(`🗺️ ${plate} → ${province} rotasına yönlendirildi.`);
            closeRoutePicker();
        } catch (_) { flash("Rota oluşturulamadı (bağlantı)."); }
        finally { go.disabled = false; go.textContent = "Rotayı Oluştur"; }
    }

    // ── Araca uyarı mesajı ──────────────────────────────────────────────────
    async function sendMessage() {
        if (selected == null) return;
        const category = document.getElementById("msgCat").value;
        const input = document.getElementById("msgText");
        const body = input.value.trim();
        if (!body) { flash("Önce bir uyarı mesajı yaz."); return; }
        try {
            const res = await fetch(`/api/v1/vehicles/${selected}/messages`, {
                method: "POST", headers: { ...auth(), "Content-Type": "application/json" },
                body: JSON.stringify({ category, body })
            });
            if (!res.ok) { flash("Uyarı gönderilemedi."); return; }
            input.value = "";   // WebSocket yayını toast + listeyi günceller
        } catch (_) { flash("Uyarı gönderilemedi (bağlantı)."); }
    }

    async function loadMessages(vehicleId) {
        try {
            const res = await fetch(`/api/v1/vehicles/${vehicleId}/messages`, { headers: auth() });
            if (!res.ok) return;
            messages.set(vehicleId, await res.json());
            renderMessages(vehicleId);
        } catch (_) { /* yoksay */ }
    }

    function renderMessages(vehicleId) {
        const el = document.getElementById("msgList");
        if (!el || vehicleId !== selected) return;
        const list = messages.get(vehicleId) || [];
        el.innerHTML = "";
        if (!list.length) {
            el.innerHTML = `<div class="msg-item" style="border-color:var(--line);color:var(--muted)">Bu araca henüz uyarı yok.</div>`;
            return;
        }
        list.forEach(m => {
            const div = document.createElement("div");
            div.className = "msg-item";
            const t = m.at ? new Date(m.at).toLocaleTimeString("tr-TR") : "";
            div.innerHTML = `<span class="mc">${m.category}</span> — ${m.body}<div class="mt">${t}</div>`;
            el.appendChild(div);
        });
    }

    function onIncomingMessage(msg) {
        const arr = messages.get(msg.vehicleId) || [];
        arr.unshift({ category: msg.category, body: msg.body, at: msg.at });
        messages.set(msg.vehicleId, arr);
        if (msg.vehicleId === selected) renderMessages(msg.vehicleId);
        const m = liveMarkers.get(msg.vehicleId);
        if (m) m.setPopupContent(popup(pos.get(msg.vehicleId) || { vehicleId: msg.vehicleId }));
        showToast(`<span class="tt">⚠ ${msg.plate} · ${msg.category}</span><br>${msg.body}`);
    }

    let toastTimer = null;
    function showToast(html) {
        const el = document.getElementById("toast");
        el.innerHTML = html;
        el.classList.add("show");
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => el.classList.remove("show"), 6000);
    }

    async function refreshDispatch() {
        if (!token) return;
        try {
            const res = await fetch("/api/v1/control/state", { headers: auth() });
            if (!res.ok) return;
            (await res.json()).forEach(s => {
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

    /**
     * Aracın güncel puanı: son yolculuklarının ortalaması.
     *
     * Tek bir seferin puanı değil ortalama, çünkü tek sefer aracın nasıl kullanıldığını
     * anlatmaz -- bir kötü gün ya da bir şanslı sefer tabloyu yanıltır. Son SCORE_WINDOW
     * yolculuk, "şu aralar nasıl gidiyor" sorusunun cevabı.
     *
     * Kendini kısıtlar: aynı araç için 10 saniyede birden fazla istek gitmez. Trip, son
     * hareketten 90 sn sonra kapandığı için yeni puanlar zaten bu hızda gelir.
     */
    const SCORE_WINDOW = 10;
    const scoreFetchAt = new Map();
    async function loadVehicleScore(vehicleId) {
        const now = Date.now();
        if (now - (scoreFetchAt.get(vehicleId) || 0) < 10000) return;
        scoreFetchAt.set(vehicleId, now);
        try {
            const res = await fetch(`/api/v1/vehicles/${vehicleId}/trips?limit=${SCORE_WINDOW}`,
                { headers: auth() });
            if (!res.ok) return;
            const trips = await res.json();
            const scores = (Array.isArray(trips) ? trips : [])
                .map(t => t.score).filter(s => s != null);
            if (scores.length === 0) return;
            const avg = scores.reduce((a, b) => a + b, 0) / scores.length;
            const prev = vehicleScore.get(vehicleId);
            vehicleScore.set(vehicleId, avg);
            // Yalnızca değiştiyse yeniden çiz: her istek panelin yeniden kurulmasına yol açmasın.
            if (selected === vehicleId && prev !== avg) select(vehicleId);
        } catch (_) { /* yoksay */ }
    }

    function journeyText(j) {
        if (!j) return "";
        if (j.parked) return (j.flying ? "🚁 " : "🅿 ") + "varışta · durdu";
        if (j.destination == null) return "rota bekleniyor…";
        return `→ ${j.destination} · ${j.remainingKm} km` + (j.etaMinutes >= 0 ? ` · ~${j.etaMinutes} dk` : "");
    }

    // ── Geofence ────────────────────────────────────────────────────────────
    async function loadGeofences() {
        try {
            const res = await fetch("/api/v1/geofences", { headers: auth() });
            if (!res.ok) return;
            // Tek bir katman grubunda tutuluyor: yeni bölge çizilince hepsi silinip yeniden
            // çiziliyor. Aksi halde her yenilemede eskisinin üstüne bir kopya daha binerdi.
            if (geofenceLayer) live.removeLayer(geofenceLayer);
            geofenceLayer = L.layerGroup().addTo(live);
            (await res.json()).forEach(g => {
                const ex = g.kind === "EXCLUSION";
                L.geoJSON(JSON.parse(g.geojson), {
                    style: { color: ex ? "#e24b4a" : "#5dcaa5", weight: 2, fillOpacity: 0.12, dashArray: ex ? null : "4" }
                }).bindTooltip(`${g.name} (${ex ? "yasak" : "izinli"})`).addTo(geofenceLayer);
            });
        } catch (_) { /* yoksay */ }
    }

    // ── Sürücü skorları ─────────────────────────────────────────────────────
    /**
     * Tüm sürücülerin puanı çekilir, panelde yalnızca ilk beşi gösterilir.
     *
     * Tamamı, çünkü harita balonu da bu veriyi kullanıyor: araca tıklayan operatör o aracı
     * kimin kullandığını ve nasıl sürdüğünü orada görüyor. Sürücü başına ayrı istek atmak,
     * aynı listeyi 105 kez parça parça sormak olurdu.
     */
    async function loadScores() {
        try {
            const res = await fetch("/api/v1/drivers/scores?days=30&limit=500", { headers: auth() });
            if (!res.ok) return;
            const all = await res.json();
            driverScores.clear();
            all.forEach(d => driverScores.set(d.driverId, { name: d.name, score: Number(d.score) }));
            refreshOpenPopups();

            const el = document.getElementById("scoreList");
            el.innerHTML = "";
            all.slice(0, 5).forEach((d, i) => {
                const s = Number(d.score);
                const cls = s >= 8 ? "good" : s >= 6 ? "mid" : "bad";
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
        const rule = RULES[v.ruleCode] || { tr: "", fine: 0 };
        alerts++;
        fineTotal += rule.fine;
        document.getElementById("statAlerts").textContent = alerts;
        const ft = document.getElementById("fineTotal");
        if (ft) ft.textContent = tl(fineTotal);

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
        row.innerHTML =
            `<div class="ar-top"><span class="code">${rule.tr || v.ruleCode}</span><span class="fine">${tl(rule.fine)}</span></div>` +
            `<div class="meta">${v.ruleCode} · ${veh ? veh.plate : "#" + v.vehicleId} · ${time}</div>`;
        row.addEventListener("click", () => select(v.vehicleId));
        el.prepend(row);
        while (el.children.length > 40) el.removeChild(el.lastChild);
    }

    // ── Durum satırı ────────────────────────────────────────────────────────
    function setStatus(text, ok) {
        const el = document.getElementById("statusLine");
        el.textContent = text; el.style.color = ok ? "#5dcaa5" : "#e24b4a";
    }
    let flashTimer = null;
    function flash(text) {
        const el = document.getElementById("statusLine");
        const prev = el.textContent, prevColor = el.style.color;
        el.textContent = text; el.style.color = "#f0997b";
        clearTimeout(flashTimer);
        flashTimer = setTimeout(() => { el.textContent = prev; el.style.color = prevColor; }, 3500);
    }
    // ── Cihaz komutları (Teltonika Codec 12) ────────────────────────────────
    /**
     * Komut listesi sunucudan geliyor, arayüzde sabit değil.
     *
     * Sebebi güvenlik: gateway zaten yalnızca bu listedeki komutları kabul ediyor. Listeyi
     * burada da yazsaydık iki yerde tutulan tek bir gerçek olurdu ve ayrıştıkları anda
     * operatör, sunucunun reddedeceği bir komutu seçebiliyor olurdu.
     */
    async function loadCommandCatalogue() {
        try {
            const res = await fetch("/api/v1/device-commands/catalogue", { headers: auth() });
            if (!res.ok) return;
            commandCatalogue = await res.json();
            const sel = document.getElementById("cmdSelect");
            sel.innerHTML = commandCatalogue.map(c =>
                `<option value="${c.command}">${c.destructive ? "⛔ " : "🛈 "}${c.label}</option>`).join("");
        } catch (_) { /* yoksay */ }
    }

    async function sendCommand() {
        if (selected == null) return;
        const command = document.getElementById("cmdSelect").value;
        const option = commandCatalogue.find(c => c.command === command);
        // Röleyi kesmek aracı yolda durdurur. Geri alınabilir, ama geri alınana kadar sürücü
        // yolda kalır — o yüzden tek tıkla olmuyor.
        if (option && option.destructive
            && !confirm(`"${option.label}" komutu cihaza gönderilecek. Emin misin?`)) {
            return;
        }
        try {
            const res = await fetch(`/api/v1/vehicles/${selected}/commands`, {
                method: "POST",
                headers: { ...auth(), "Content-Type": "application/json" },
                body: JSON.stringify({ command })
            });
            if (res.status === 404) { flash("Bu aracın cihazı yok"); return; }
            if (!res.ok) { flash("Komut reddedildi"); return; }
            flash("Komut kuyruğa alındı");
            loadCommands(selected);
        } catch (_) { flash("Komut gönderilemedi"); }
    }

    async function loadCommands(vehicleId) {
        if (vehicleId == null) return;
        try {
            const res = await fetch(`/api/v1/vehicles/${vehicleId}/commands?limit=8`, { headers: auth() });
            if (!res.ok) return;
            // Yanıt gecikirken operatör başka araca geçmiş olabilir; geç gelen cevabın
            // başka bir aracın panelini ezmesini engelliyor.
            if (vehicleId !== selected) return;
            renderCommands(await res.json());
        } catch (_) { /* yoksay */ }
    }

    const CMD_STATUS = {
        PENDING: "kuyrukta", SENT: "cihaza yazıldı", ANSWERED: "cevaplandı",
        TIMEOUT: "cihaz cevap vermedi", NO_SESSION: "cihaz bağlı değil", FAILED: "başarısız"
    };

    function renderCommands(list) {
        const box = document.getElementById("cmdList");
        if (!list.length) { box.innerHTML = ""; return; }
        box.innerHTML = list.map(c => {
            const when = c.answeredAt || c.createdAt;
            return `<div class="cmd-item st-${c.status}">
                <div class="cc">${esc(c.command)} <span style="font-weight:400;color:var(--muted)">· ${CMD_STATUS[c.status] || c.status}</span></div>
                ${c.response ? `<div class="cr">${esc(c.response)}</div>` : ""}
                <div class="cr">${when ? new Date(when).toLocaleTimeString("tr-TR") : ""}</div>
            </div>`;
        }).join("");
    }

    // ── Bakım ───────────────────────────────────────────────────────────────
    /**
     * Bakım listesi gerçek kilometre sayacına dayanıyor: cihaz kanalı odometreyi taşıyor,
     * processing onu vehicle.odometer_km'ye yazıyor. O sütun yazılmadan önce bu liste her
     * zaman boştu ve gecelik hatırlatma işi her gece sıfır sayıyordu.
     */
    async function loadMaintenance() {
        try {
            const res = await fetch("/api/v1/maintenance/due", { headers: auth() });
            if (!res.ok) return;
            const rows = await res.json();
            document.getElementById("maintCount").textContent = rows.length ? `(${rows.length})` : "(yok)";
            document.getElementById("maintList").innerHTML = rows.slice(0, 8).map(m => {
                const detail = m.remainingKm != null
                    ? (m.remainingKm <= 0 ? `${-m.remainingKm} km gecikmiş` : `${m.remainingKm} km kaldı`)
                    : (m.nextDueAt ? new Date(m.nextDueAt).toLocaleDateString("tr-TR") : "");
                return `<div class="mt-item ${m.overdue ? "overdue" : ""}">
                    <div>
                        <div>${esc(m.plate)} · ${esc(m.name)}</div>
                        <div class="mn">${detail}</div>
                    </div>
                    <button class="mt-done" data-plan="${m.planId}">Yapıldı</button>
                </div>`;
            }).join("");
            document.querySelectorAll("#maintList .mt-done").forEach(b =>
                b.addEventListener("click", () => markServiced(Number(b.dataset.plan))));
        } catch (_) { /* yoksay */ }
    }

    async function markServiced(planId) {
        try {
            const res = await fetch(`/api/v1/maintenance/${planId}/serviced`, {
                method: "POST", headers: auth()
            });
            if (!res.ok) { flash("Bakım kaydedilemedi"); return; }
            flash("Bakım kaydedildi");
            loadMaintenance();
        } catch (_) { flash("Bakım kaydedilemedi"); }
    }

    // ── Yolculuk oynatma ────────────────────────────────────────────────────
    /**
     * Biten bir seferi kendi zaman damgalarıyla oynatır.
     *
     * Kırıntılar 30 saniyede bir örneklendiği için her karede noktanın KENDİ saati
     * gösteriliyor: aracın beklediği yerde saat atlıyor, hızlandığı yerde noktalar
     * seyrekleşiyor. Sabit bir sayaç göstermek, aracın hiç sürmediği düzgün bir hızı
     * anlatırdı.
     */
    async function startReplay(vehicleId) {
        if (vehicleId == null) return;
        stopReplay();
        try {
            const tr = await fetch(`/api/v1/vehicles/${vehicleId}/trips?limit=1`, { headers: auth() });
            const trips = tr.ok ? await tr.json() : [];
            if (!trips.length) { flash("Bu aracın tamamlanmış seferi yok"); return; }

            const rt = await fetch(`/api/v1/trips/${trips[0].id}/route`, { headers: auth() });
            const points = rt.ok ? await rt.json() : [];
            if (points.length < 2) { flash("Seferin izi yok"); return; }

            replay.tripId = trips[0].id;
            replay.points = points;
            replay.idx = 0;
            replay.layer = L.layerGroup().addTo(live);
            L.polyline(points.map(p => [p.lat, p.lon]),
                { color: "#ffd27f", weight: 3, opacity: .55, dashArray: "5 4" }).addTo(replay.layer);
            replay.marker = L.marker([points[0].lat, points[0].lon], {
                icon: L.divIcon({ className: "", html: '<div class="replay-mk"></div>', iconSize: [14, 14] }),
                zIndexOffset: 1200
            }).addTo(replay.layer);

            const range = document.getElementById("replayRange");
            range.max = String(points.length - 1);
            range.value = "0";
            document.getElementById("replayBar").classList.add("on");
            live.fitBounds(L.polyline(points.map(p => [p.lat, p.lon])).getBounds(), { padding: [40, 40] });
            showReplayFrame(0);
            playReplay();
        } catch (_) { flash("Sefer yüklenemedi"); }
    }

    function showReplayFrame(i) {
        const p = replay.points[i];
        if (!p) return;
        replay.idx = i;
        replay.marker.setLatLng([p.lat, p.lon]);
        document.getElementById("replayRange").value = String(i);
        const clock = p.ts ? new Date(p.ts).toLocaleTimeString("tr-TR") : `#${p.seq}`;
        document.getElementById("replayLabel").textContent =
            `${clock} · ${p.speedKmh == null ? "-" : p.speedKmh} km/s · ${i + 1}/${replay.points.length}`;
    }

    function playReplay() {
        if (replay.timer) return;
        document.getElementById("replayPlay").textContent = "⏸";
        // Kırıntılar 30 sn arayla; 200 ms'de bir ilerlemek ~150x gerçek zaman demek, yani
        // bir saatlik sefer yaklaşık 24 saniyede izleniyor.
        replay.timer = setInterval(() => {
            if (replay.idx + 1 >= replay.points.length) { pauseReplay(); return; }
            showReplayFrame(replay.idx + 1);
        }, 200);
    }

    function pauseReplay() {
        if (replay.timer) { clearInterval(replay.timer); replay.timer = null; }
        document.getElementById("replayPlay").textContent = "▶";
    }

    function toggleReplayPlay() {
        if (replay.timer) pauseReplay();
        else if (replay.idx + 1 < replay.points.length) playReplay();
    }

    function stopReplay() {
        pauseReplay();
        if (replay.layer) { live.removeLayer(replay.layer); replay.layer = null; }
        replay.points = []; replay.marker = null; replay.idx = 0; replay.tripId = null;
        document.getElementById("replayBar").classList.remove("on");
    }

    // ── Bölge çizimi ────────────────────────────────────────────────────────
    /**
     * Operatör haritasına tıklayarak poligon kurar.
     *
     * Çizim elle yazıldı, bir Leaflet eklentisi eklenmedi: gereken şey "tıkla, köşe koy,
     * kapat" ve bunun için sayfaya üçüncü bir CDN bağımlılığı sokmak kazandırdığından
     * fazlasını maliyet olarak getirirdi.
     */
    function toggleDraw() {
        if (draw.on) { cancelDraw(); return; }
        draw.on = true;
        draw.points = [];
        draw.layer = L.layerGroup().addTo(ctrl);
        document.getElementById("mapCtrl").classList.add("drawing");
        document.getElementById("zonePicker").style.display = "block";
        document.getElementById("zoneBtn").style.display = "none";
        document.getElementById("routeBtn").style.display = "none";
        // Çizerken çift tık aracı taşımasın: aynı jestin iki anlamı olurdu.
        ctrl.doubleClickZoom.disable();
        updateZoneHint();
    }

    function onDrawClick(e) {
        if (!draw.on) return;
        draw.points.push([e.latlng.lat, e.latlng.lng]);
        redrawZone();
        updateZoneHint();
    }

    function redrawZone() {
        draw.layer.clearLayers();
        draw.points.forEach(p => L.circleMarker(p, {
            radius: 4, color: "#e24b4a", fillColor: "#e24b4a", fillOpacity: 1
        }).addTo(draw.layer));
        if (draw.points.length >= 2) {
            L.polygon(draw.points, { color: "#e24b4a", weight: 2, fillOpacity: .15 }).addTo(draw.layer);
        }
    }

    function updateZoneHint() {
        const n = draw.points.length;
        document.getElementById("zoneHint").textContent = n < 3
            ? `Haritaya tıklayarak köşeleri koy — en az 3 (${n})`
            : `${n} köşe · kaydetmeye hazır`;
    }

    async function saveZone() {
        if (draw.points.length < 3) { flash("En az 3 köşe gerekli"); return; }
        const name = document.getElementById("zoneName").value.trim() || "Yeni bölge";
        const kind = document.getElementById("zoneKind").value;
        try {
            const res = await fetch("/api/v1/geofences", {
                method: "POST",
                headers: { ...auth(), "Content-Type": "application/json" },
                body: JSON.stringify({ name, kind, points: draw.points })
            });
            if (!res.ok) { flash("Bölge kaydedilemedi"); return; }
            cancelDraw();
            document.getElementById("zoneName").value = "";
            await loadGeofences();
            // Kural motoru bölgeleri dakikada bir yeniliyor, yani ihlal üretmesi bir dakikayı
            // bulabilir. Bunu söylemek, operatörün "kaydettim ama bir şey olmuyor" demesinden iyi.
            showToast('<span class="tt">Bölge kaydedildi</span><br/>Kural motoru en geç 1 dakika içinde devreye alır.');
        } catch (_) { flash("Bölge kaydedilemedi"); }
    }

    function cancelDraw() {
        draw.on = false;
        draw.points = [];
        if (draw.layer) { ctrl.removeLayer(draw.layer); draw.layer = null; }
        document.getElementById("mapCtrl").classList.remove("drawing");
        document.getElementById("zonePicker").style.display = "none";
        document.getElementById("zoneBtn").style.display = "block";
        document.getElementById("routeBtn").style.display = selected != null ? "block" : "none";
        if (selected != null) ctrl.doubleClickZoom.disable(); else ctrl.doubleClickZoom.enable();
    }

    /** Sunucudan gelen metni HTML'e koymadan önce kaçır. */
    function esc(text) {
        return String(text == null ? "" : text).replace(/[&<>"]/g, c =>
            ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
    }

})();
