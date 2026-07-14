/*
 * VTS operatör konsolu (simülatör, port 8085).
 *
 *  - GET  /api/positions              -> tüm araçların anlık konumu (1.5 sn polling).
 *  - POST /api/control/{id}/position  -> aracı elle bir konuma sabitle.
 *  - DELETE /api/control/{id}/position-> aracı otomatik simülasyona geri döndür.
 *
 * Simülatör konumların tek kaynağı olduğundan, buradan yapılan taşıma gerçek
 * telemetri hattı üzerinden ana canlı haritaya (gateway, 8080) yansır.
 */
(function () {
    "use strict";

    let map = null;
    const markers = new Map();     // id -> L.marker
    const state = new Map();       // id -> {lat, lon, manual, region, speedKmh}
    let selectedId = null;

    initMap();
    poll();
    setInterval(poll, 1500);

    const vidInput = document.getElementById("vid");
    vidInput.addEventListener("input", () => selectVehicle(parseInt(vidInput.value, 10)));
    document.getElementById("releaseBtn").addEventListener("click", releaseSelected);

    function initMap() {
        map = L.map("map", { zoomControl: true, attributionControl: false }).setView([39.0, 35.0], 6);
        // Farklı harita: CartoDB Voyager (ana haritadaki OpenStreetMap'ten görsel olarak ayrı).
        L.tileLayer("https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png", {
            maxZoom: 19, subdomains: "abcd"
        }).addTo(map);
        // Taşıma çift-tık ile. Araç seçiliyse zoom'u kapatırız ki çift-tık SADECE
        // aracı taşısın; araç seçili değilse çift-tık normal şekilde yakınlaştırır.
        map.on("dblclick", onMapDblClick);
    }

    async function poll() {
        try {
            const res = await fetch("/api/positions");
            if (!res.ok) return;
            const list = await res.json();
            list.forEach(p => {
                state.set(p.id, p);
                drawMarker(p);
            });
            setStatus(`${list.length} araç · ${[...state.values()].filter(v => v.manual).length} elle kontrol`);
        } catch (e) {
            setStatus("Simülatöre bağlanılamadı.");
        }
    }

    function drawMarker(p) {
        if (p.lat == null || p.lon == null) return;
        let m = markers.get(p.id);
        const icon = numIcon(p.id, p.manual, p.id === selectedId);
        if (!m) {
            m = L.marker([p.lat, p.lon], { icon }).addTo(map);
            m.on("click", () => { vidInput.value = p.id; selectVehicle(p.id); });
            markers.set(p.id, m);
        } else {
            m.setLatLng([p.lat, p.lon]);
            m.setIcon(icon);
        }
    }

    function numIcon(id, manual, selected) {
        const cls = "veh-num " + (manual ? "manual" : "auto") + (selected ? " selected" : "");
        return L.divIcon({
            html: `<div class="${cls}">${id}</div>`,
            className: "", iconSize: [24, 24], iconAnchor: [12, 12]
        });
    }

    function selectVehicle(id) {
        selectedId = Number.isInteger(id) ? id : null;
        // Seçim halkasını yenile.
        markers.forEach((m, mid) => {
            const p = state.get(mid);
            if (p) m.setIcon(numIcon(mid, p.manual, mid === selectedId));
        });
        const info = document.getElementById("selInfo");
        const relBtn = document.getElementById("releaseBtn");
        // Araç seçiliyken çift-tık zoom'u KAPALI (çift-tık sadece taşır);
        // seçim yokken AÇIK (çift-tık haritayı yakınlaştırır).
        if (selectedId != null) {
            map.doubleClickZoom.disable();
        } else {
            map.doubleClickZoom.enable();
        }
        const p = selectedId != null ? state.get(selectedId) : null;
        if (p) {
            const pill = p.manual ? '<span class="pill manual">ELLE</span>' : '<span class="pill auto">OTOMATİK</span>';
            info.innerHTML = `<span class="sel">Araç ${p.id}${pill}</span><br><small>${p.region || ""} · ${p.speedKmh} km/s</small>`;
            relBtn.disabled = !p.manual;
            map.panTo([p.lat, p.lon]);
        } else {
            info.innerHTML = selectedId != null
                ? `<span class="sel">Araç ${selectedId} <small>bulunamadı</small></span>`
                : "";
            relBtn.disabled = true;
        }
    }

    async function onMapDblClick(e) {
        if (selectedId == null) { return; } // seçim yok -> Leaflet zaten yakınlaştırdı
        const lat = +e.latlng.lat.toFixed(6), lon = +e.latlng.lng.toFixed(6);
        try {
            const res = await fetch(`/api/control/${selectedId}/position`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ lat, lon })
            });
            if (res.status === 404) { setStatus(`Araç ${selectedId} yok (1–100).`); return; }
            if (!res.ok) { setStatus("Taşıma başarısız."); return; }
            // İyimser güncelleme: bir sonraki polling'i beklemeden taşı.
            const p = state.get(selectedId) || { id: selectedId };
            Object.assign(p, { lat, lon, manual: true, speedKmh: 0 });
            state.set(selectedId, p);
            drawMarker(p);
            selectVehicle(selectedId);
            setStatus(`Araç ${selectedId} taşındı → ana harita güncelleniyor.`);
        } catch (_) {
            setStatus("Taşıma başarısız (bağlantı).");
        }
    }

    async function releaseSelected() {
        if (selectedId == null) return;
        try {
            await fetch(`/api/control/${selectedId}/position`, { method: "DELETE" });
            const p = state.get(selectedId);
            if (p) { p.manual = false; }
            selectVehicle(selectedId);
            setStatus(`Araç ${selectedId} otomatiğe döndü.`);
        } catch (_) { /* yoksay */ }
    }

    function setStatus(text) {
        document.getElementById("status").textContent = text;
    }
})();
