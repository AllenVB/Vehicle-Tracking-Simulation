package com.fleet.vts.gateway.web;

/**
 * Basit mesafe ve varış tahmini (ETA) hesapları. Görev/paylaşım linkinde "yaklaşık N dk"
 * göstermek için yeterli; rota/trafik bilmez, kuş uçuşu mesafeyi kullanır.
 */
public final class GeoEta {

    private GeoEta() {
    }

    /** İki nokta arası kuş uçuşu mesafe (km), haversine. */
    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Mesafe + anlık hızdan yaklaşık varış dakikası. Araç durgunsa (veya hız yoksa) makul bir
     * şehir içi ortalaması varsayılır; aksi halde her yeniden hesap saçma "0 dk / saatler" arası
     * zıplardı.
     */
    public static int etaMinutes(double distanceKm, Double speedKmh) {
        double v = (speedKmh != null && speedKmh > 5) ? speedKmh : 30.0;   // durgun → 30 km/s varsay
        return (int) Math.max(1, Math.ceil(distanceKm / v * 60));
    }
}
