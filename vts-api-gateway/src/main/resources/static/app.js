/*
 * VTS — tek sayfa, iki harita (2/5/5 grid), tek servis (gateway).
 *
 *  - Sol harita  : canlı filo (OpenStreetMap)
 *  - Sağ harita  : operatör (CartoDB) — araç seç, çift tıkla taşı
 *  - TEK WebSocket (/topic/fleet/live) her iki haritayı da besler; eski konsolun
 *    1.5 sn'lik polling'i kalktı, konumlar push ile gelir.
 *  - Kontrol çağrıları gateway'in /api/v1/control/** proxy'sine gider; gateway
 *    vehicleId -> simülatör index çevirisini imei üzerinden yapar (plaka numarası
 *    ile veritabanı id'si AYNI DEĞİLDİR).
 */
(function () {
    "use strict";

    let token = null;
    let live = null, ctrl = null;             // iki Leaflet haritası
    const liveMarkers = new Map();            // vehicleId -> marker (sol)
    const ctrlMarkers = new Map();            // vehicleId -> marker (sağ)
    const pos = new Map();                    // vehicleId -> {lat,lon,speedKmh,heading}
    const vehicles = new Map();               // vehicleId -> {plate, plateNo, model}
    const byPlateNo = new Map();              // plakaNo -> vehicleId
    const manual = new Set();                 // elle sabitlenen vehicleId'ler
    const journey = new Map();                // vehicleId -> {destination, remainingKm, etaMinutes, parked}
    let selected = null;                      // seçili vehicleId
    let alerts = 0, fitted = false;

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
        setInterval(refreshDispatch, 3000);   // sevkiyat bilgisi (hafif, yavaş değişir)

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

        // Araç seçiliyken çift tık SADECE taşır (zoom kapalı); seçim yokken yakınlaştırır.
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
            vehicles.set(v.id, { plate: v.plate, plateNo: no, model: [v.make, v.model].filter(Boolean).join(" ") });
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
            reconnectDelay: 3000
        });
        stomp.onConnect = () => {
            setStatus("Bağlı · canlı", true);
            // Tek abonelik, iki harita: aynı delta akışı ikisini de besler.
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

    // Sol harita: yöne dönen ok
    function drawLive(p) {
        let m = liveMarkers.get(p.vehicleId);
        const icon = arrowIcon(p.heading || 0, false);
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

    // Sağ harita: numaralı daire (plaka no)
    function drawCtrl(p) {
        let m = ctrlMarkers.get(p.vehicleId);
        const icon = numIcon(p.vehicleId);
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

    function arrowIcon(heading, alerting) {
        const color = alerting ? "#e24b4a" : "#1d9e75";
        return L.divIcon({
            html: `<div class="veh-icon" style="transform:rotate(${heading}deg)">
                     <svg class="veh-arrow${alerting ? " alert" : ""}" width="24" height="24" viewBox="0 0 24 24">
                       <path d="M12 2 L19 21 L12 17 L5 21 Z" fill="${color}" stroke="#0b1016" stroke-width="1"/>
                     </svg></div>`,
            className: "", iconSize: [24, 24], iconAnchor: [12, 12]
        });
    }

    function numIcon(vehicleId) {
        const v = vehicles.get(vehicleId);
        const cls = "veh-num " + (manual.has(vehicleId) ? "manual" : "auto")
            + (vehicleId === selected ? " selected" : "");
        return L.divIcon({
            html: `<div class="${cls}">${v ? v.plateNo : "?"}</div>`,
            className: "", iconSize: [22, 22], iconAnchor: [11, 11]
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
        selected = vehicleId;
        // Seçim varken çift-tık zoom kapalı → çift tık sadece taşır.
        if (selected != null) ctrl.doubleClickZoom.disable();
        else ctrl.doubleClickZoom.enable();

        document.querySelectorAll("#vehicleList .row").forEach(r =>
            r.classList.toggle("sel", Number(r.dataset.vid) === selected));
        ctrlMarkers.forEach((m, id) => m.setIcon(numIcon(id)));

        const info = document.getElementById("selInfo");
        const btn = document.getElementById("releaseBtn");
        const v = selected != null ? vehicles.get(selected) : null;
        if (v) {
            const isManual = manual.has(selected);
            const j = journey.get(selected);
            info.innerHTML = `<b>${v.plate}</b>` +
                `<span class="pill ${isManual ? "manual" : "auto"}">${isManual ? "ELLE" : "OTOMATİK"}</span>` +
                (j ? `<div class="meta" style="margin-top:4px">${journeyText(j)}</div>` : "");
            btn.disabled = !isManual;
            const p = pos.get(selected);
            if (p) { ctrl.panTo([p.lat, p.lon]); live.panTo([p.lat, p.lon]); }
            const row = document.querySelector(`#vehicleList .row[data-vid="${selected}"]`);
            if (row) row.scrollIntoView({ block: "nearest" });
        } else {
            info.textContent = "";
            btn.disabled = true;
        }
    }

    // ── Kontrol (gateway proxy) ─────────────────────────────────────────────
    async function onCtrlDblClick(e) {
        if (selected == null) return;   // seçim yok -> Leaflet zaten yakınlaştırdı
        const lat = +e.latlng.lat.toFixed(6), lon = +e.latlng.lng.toFixed(6);
        try {
            const res = await fetch(`/api/v1/control/${selected}/position`, {
                method: "POST",
                headers: { ...auth(), "Content-Type": "application/json" },
                body: JSON.stringify({ lat, lon })
            });
            if (!res.ok) { flash("Taşıma başarısız."); return; }
            manual.add(selected);
            const p = { ...(pos.get(selected) || {}), vehicleId: selected, lat, lon, speedKmh: 0 };
            pos.set(selected, p);
            drawLive(p); drawCtrl(p);       // iyimser: WS'i beklemeden çiz
            select(selected);
            flash(`${vehicles.get(selected).plate} taşındı → sol harita güncelleniyor.`);
        } catch (_) { flash("Taşıma başarısız (bağlantı)."); }
    }

    async function release() {
        if (selected == null) return;
        await fetch(`/api/v1/control/${selected}/position`, { method: "DELETE", headers: auth() });
        manual.delete(selected);
        select(selected);
        flash("Araç otomatiğe döndü.");
    }

    // Sevkiyat durumu: hedef, kalan km, ETA, park/elle bayrakları.
    // Konumlar buradan GELMEZ — onlar WebSocket'ten push edilir; bu sadece yavaş
    // değişen sevkiyat bilgisi olduğu için 3 sn'de bir çekilir.
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
                    etaMinutes: s.etaMinutes, parked: s.parked
                });
            });
            ctrlMarkers.forEach((m, id) => m.setIcon(numIcon(id)));
            renderJourneyMeta();
            if (selected != null) select(selected);
        } catch (_) { /* yoksay */ }
    }

    // Araç listesindeki "hedef · kalan km" satırını tazele.
    function renderJourneyMeta() {
        document.querySelectorAll("#vehicleList .row").forEach(row => {
            const j = journey.get(Number(row.dataset.vid));
            const meta = row.querySelector(".meta");
            if (meta) meta.textContent = journeyText(j) || meta.dataset.model || "";
        });
    }

    function journeyText(j) {
        if (!j) return "";
        if (j.parked) return "🅿 varışta · park";
        if (j.destination == null) return "rota bekleniyor…";
        return `→ ${j.destination} · ${j.remainingKm} km`
            + (j.etaMinutes >= 0 ? ` · ~${j.etaMinutes} dk` : "");
    }

    // ── İhlaller ────────────────────────────────────────────────────────────
    function onViolation(v) {
        alerts++;
        document.getElementById("statAlerts").textContent = alerts;

        const m = liveMarkers.get(v.vehicleId);
        const p = pos.get(v.vehicleId);
        if (m && p) {
            m._alerting = true;
            m.setIcon(arrowIcon(p.heading || 0, true));
            setTimeout(() => { m._alerting = false; m.setIcon(arrowIcon((pos.get(v.vehicleId) || p).heading || 0, false)); }, 4000);
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
        flashTimer = setTimeout(() => { el.textContent = prev; el.style.color = prevColor; }, 2500);
    }
})();
