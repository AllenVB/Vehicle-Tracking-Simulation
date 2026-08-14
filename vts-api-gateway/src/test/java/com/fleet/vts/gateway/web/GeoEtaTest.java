package com.fleet.vts.gateway.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Kuş uçuşu mesafe ve ETA hesapları. ETA'nın kritik ayrıntısı: araç durgunken (veya hız
 * yokken) makul bir şehir-içi ortalaması varsayılır, yoksa her yeniden hesap 0 ile saatler
 * arasında zıplardı; ve ETA asla 0 dönmez (en az 1 dk).
 */
class GeoEtaTest {

    @Test
    void haversineIsZeroForTheSamePoint() {
        assertThat(GeoEta.haversineKm(41.02, 29.00, 41.02, 29.00)).isZero();
    }

    @Test
    void haversineMatchesOneDegreeOfLatitude() {
        // 1° enlem ≈ 111.19 km — bilinen referans.
        assertThat(GeoEta.haversineKm(0, 0, 1, 0)).isCloseTo(111.19, within(0.5));
    }

    @Test
    void etaAssumesCityAverageWhenStationary() {
        // Hız yok → 30 km/s varsayılır; 30 km → 60 dk.
        assertThat(GeoEta.etaMinutes(30, null)).isEqualTo(60);
        // Hız ölçülebilir ama ~0 (≤5) → yine 30 km/s varsayımı.
        assertThat(GeoEta.etaMinutes(30, 3.0)).isEqualTo(60);
    }

    @Test
    void etaUsesActualSpeedWhenMoving() {
        assertThat(GeoEta.etaMinutes(30, 60.0)).isEqualTo(30);
        assertThat(GeoEta.etaMinutes(60, 120.0)).isEqualTo(30);
    }

    @Test
    void etaIsNeverZero() {
        assertThat(GeoEta.etaMinutes(0.05, 120.0)).isEqualTo(1);   // çok yakın → yine en az 1 dk
    }
}
