/*
 * VTS canlı filo haritası istemcisi.
 *
 *  - REST /api/v1/auth/login  -> JWT (araç listesi ve rota çağrıları için).
 *  - REST /api/v1/live/positions -> harita açılışında ilk anlık görüntü.
 *  - STOMP /topic/fleet/live  -> saniyede 1 delta; sadece değişen araçlar.
 *  - STOMP /topic/violations  -> canlı ihlal akışı.
 *  - STOMP /app/viewport      -> haritanın görünen sınırlarını (bbox) gateway'e bildir.
 */
(function () {
    "use strict";

    const API = "";                 // aynı origin (gateway 8080)
    let token = null;
    let map = null;
    let stomp = null;
    const markers = new Map();      // vehicleId -> L.marker
    const vehicles = new Map();     // vehicleId -> plate
    let routeLayer = null;          // seçili aracın geçmiş rotası
    let alertCount = 0;
    let fittedOnce = false;

    // ---- Giriş -------------------------------------------------------------
    const loginBtn = document.getElementById("loginBtn");
    loginBtn.addEventListener("click", doLogin);
    document.getElementById("password").addEventListener("keydown", e => {
        if (e.key === "Enter") doLogin();
    });

    async function doLogin() {
        const username = document.getElementById("username").value.trim();
        const password = document.getElementById("password").value;
        const err = document.getElementById("loginErr");
        err.textContent = "";
        loginBtn.disabled = true;
        try {
            const res = await fetch(API + "/api/v1/auth/login", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ username, password })
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

    function authHeaders() {
        return { "Authorization": "Bearer " + token };
    }

    // ---- Başlat ------------------------------------------------------------
    function start() {
        initMap();
        loadVehicles();
        loadSnapshot();
        connectWs();
    }

    function initMap() {
        map = L.map("map", { zoomControl: true, attributionControl: false }).setView([39.0, 35.0], 6); // Türkiye geneli
        L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
            maxZoom: 19
        }).addTo(map);
        map.on("moveend", sendViewport);
    }

    // ---- Araç listesi (kenar panel) ---------------------------------------
    async function loadVehicles() {
        try {
            const res = await fetch(API + "/api/v1/vehicles", { headers: authHeaders() });
            if (!res.ok) return;
            const list = await res.json();
            const el = document.getElementById("vehicleList");
            el.innerHTML = "";
            list.forEach(v => {
                vehicles.set(v.id, v.plate);
                const row = document.createElement("div");
                row.className = "row";
                row.innerHTML = `<span class="plate">${v.plate || ("#" + v.id)}</span>` +
                    `<span class="meta">${[v.make, v.model].filter(Boolean).join(" ")}</span>`;
                row.addEventListener("click", () => selectVehicle(v.id));
                el.appendChild(row);
            });
            document.getElementById("statTotal").textContent = list.length;
        } catch (_) { /* yoksay */ }
    }

    // ---- İlk anlık görüntü -------------------------------------------------
    async function loadSnapshot() {
        try {
            const res = await fetch(API + "/api/v1/live/positions", { headers: authHeaders() });
            if (!res.ok) return;
            applyPositions(await res.json());
        } catch (_) { /* yoksay */ }
    }

    // ---- WebSocket ---------------------------------------------------------
    function connectWs() {
        stomp = new StompJs.Client({
            webSocketFactory: () => new SockJS(API + "/ws"),
            reconnectDelay: 3000
        });
        stomp.onConnect = () => {
            setStatus("Bağlı · canlı", true);
            stomp.subscribe("/topic/fleet/live", msg => {
                const body = JSON.parse(msg.body);
                applyPositions(body.vehicles || []);
            });
            stomp.subscribe("/topic/violations", msg => onViolation(JSON.parse(msg.body)));
            sendViewport();
        };
        stomp.onWebSocketClose = () => setStatus("Bağlantı koptu · yeniden deneniyor…", false);
        stomp.activate();
    }

    function sendViewport() {
        if (!stomp || !stomp.connected || !map) return;
        const b = map.getBounds();
        stomp.publish({
            destination: "/app/viewport",
            body: JSON.stringify({
                minLat: b.getSouth(), minLon: b.getWest(),
                maxLat: b.getNorth(), maxLon: b.getEast()
            })
        });
    }

    // ---- Konum güncelleme --------------------------------------------------
    function applyPositions(positions) {
        positions.forEach(p => {
            if (p.lat == null || p.lon == null) return;
            const heading = p.heading || 0;
            let m = markers.get(p.vehicleId);
            if (!m) {
                m = L.marker([p.lat, p.lon], { icon: arrowIcon(heading) }).addTo(map);
                m.bindPopup(popupHtml(p));
                markers.set(p.vehicleId, m);
            } else {
                m.setLatLng([p.lat, p.lon]);
                m.setIcon(arrowIcon(heading));
                m.setPopupContent(popupHtml(p));
            }
            m._pos = p;
        });
        document.getElementById("statLive").textContent = markers.size;
        if (!fittedOnce && markers.size > 0) {
            fittedOnce = true;
            fitToMarkers();
        }
    }

    function fitToMarkers() {
        const pts = [...markers.values()].map(m => m.getLatLng());
        if (pts.length) map.fitBounds(L.latLngBounds(pts).pad(0.2));
    }

    function arrowIcon(heading, alert) {
        const cls = "veh-arrow" + (alert ? " alert" : "");
        const html =
            `<div class="veh-icon" style="transform:rotate(${heading}deg)">` +
            `<svg class="${cls}" viewBox="0 0 24 24">` +
            `<path d="M12 2 L19 21 L12 17 L5 21 Z" fill="${alert ? '#e24b4a' : '#1d9e75'}" ` +
            `stroke="#0b1016" stroke-width="1"/></svg></div>`;
        return L.divIcon({ html, className: "", iconSize: [26, 26], iconAnchor: [13, 13] });
    }

    function popupHtml(p) {
        const plate = vehicles.get(p.vehicleId) || ("#" + p.vehicleId);
        return `<b>${plate}</b><br>Hız: ${p.speedKmh ?? "-"} km/s<br>Yön: ${p.heading ?? "-"}°`;
    }

    // ---- İhlaller ----------------------------------------------------------
    function onViolation(v) {
        alertCount++;
        document.getElementById("statAlerts").textContent = alertCount;

        // İlgili marker'ı kısa süre kırmızıya boya.
        const m = markers.get(v.vehicleId);
        if (m && m._pos) {
            m.setIcon(arrowIcon(m._pos.heading || 0, true));
            setTimeout(() => m.setIcon(arrowIcon(m._pos.heading || 0, false)), 4000);
        }
        // Konumda sönümlenen bir uyarı halkası.
        if (v.lat != null && v.lon != null) {
            const ring = L.circle([v.lat, v.lon], { radius: 220, color: "#e24b4a", weight: 2, fillOpacity: .15 }).addTo(map);
            setTimeout(() => map.removeLayer(ring), 8000);
        }
        // Kenar panele ekle.
        const el = document.getElementById("alertList");
        const plate = vehicles.get(v.vehicleId) || ("#" + v.vehicleId);
        const row = document.createElement("div");
        row.className = "row alertRow";
        const time = v.occurredAt ? new Date(v.occurredAt).toLocaleTimeString("tr-TR") : "";
        row.innerHTML = `<span><span class="code">${v.ruleCode || "İHLAL"}</span>` +
            `<div class="meta">${plate} · ${time}</div></span>` +
            `<span class="sev">${v.severity || ""}</span>`;
        row.addEventListener("click", () => {
            if (v.lat != null && v.lon != null) map.setView([v.lat, v.lon], 14);
        });
        el.prepend(row);
        while (el.children.length > 50) el.removeChild(el.lastChild);
    }

    // ---- Araç seçimi + geçmiş rota ----------------------------------------
    async function selectVehicle(vehicleId) {
        const m = markers.get(vehicleId);
        if (m) map.setView(m.getLatLng(), Math.max(map.getZoom(), 13));

        try {
            const tRes = await fetch(API + `/api/v1/vehicles/${vehicleId}/trips?limit=1`, { headers: authHeaders() });
            if (!tRes.ok) return;
            const trips = await tRes.json();
            if (!trips.length) { flashStatus("Bu araç için kayıtlı rota yok."); return; }

            const rRes = await fetch(API + `/api/v1/trips/${trips[0].id}/route`, { headers: authHeaders() });
            if (!rRes.ok) return;
            const pts = (await rRes.json())
                .filter(pt => pt.lat != null && pt.lon != null)
                .map(pt => [pt.lat, pt.lon]);
            if (routeLayer) map.removeLayer(routeLayer);
            if (pts.length) {
                routeLayer = L.polyline(pts, { color: "#378add", weight: 4, opacity: .85 }).addTo(map);
                map.fitBounds(routeLayer.getBounds().pad(0.2));
            } else {
                flashStatus("Rota noktası bulunamadı.");
            }
        } catch (_) { /* yoksay */ }
    }

    // ---- Durum satırı ------------------------------------------------------
    function setStatus(text, live) {
        const el = document.getElementById("statusLine");
        el.textContent = text;
        el.style.color = live ? "#5dcaa5" : "#e24b4a";
    }
    let flashTimer = null;
    function flashStatus(text) {
        const el = document.getElementById("statusLine");
        const prev = el.textContent, prevColor = el.style.color;
        el.textContent = text; el.style.color = "#f0997b";
        clearTimeout(flashTimer);
        flashTimer = setTimeout(() => { el.textContent = prev; el.style.color = prevColor; }, 2500);
    }
})();
