package com.fleet.vts.gateway.web;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bakım rozetinin "etkin" durumu: depolanan durum + vade birleşiminden türetilir. Kritik
 * kural, döngünün kendini yenilemesidir — sürücü DONE işaretleyip plan ileri kaydıktan sonra
 * araç yeniden vadesine ulaşınca rozet kendiliğinden PENDING'e döner. IN_PROGRESS ise sürücü
 * aktif çalıştığı için asla ezilmez.
 */
class MaintenanceEffectiveStatusTest {

    private static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(1);
    private static final OffsetDateTime FUTURE = OffsetDateTime.now().plusDays(30);

    @Test
    void nullRawIsTreatedAsPending() {
        assertThat(MaintenanceController.effectiveStatus(null, 100L, FUTURE, 10L)).isEqualTo("PENDING");
    }

    @Test
    void pendingStaysPending() {
        assertThat(MaintenanceController.effectiveStatus("PENDING", 100L, FUTURE, 10L)).isEqualTo("PENDING");
    }

    @Test
    void inProgressIsNeverOverriddenEvenWhenDue() {
        assertThat(MaintenanceController.effectiveStatus("IN_PROGRESS", 100L, PAST, 100L)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void doneStaysDoneWhileNotDue() {
        // Kaydırma sonrası vade ileride → yakın zamanda yapıldı: "Yapıldı" kalır.
        assertThat(MaintenanceController.effectiveStatus("DONE", 100L, FUTURE, 50L)).isEqualTo("DONE");
    }

    @Test
    void doneFlipsToPendingWhenDueAgainByKm() {
        assertThat(MaintenanceController.effectiveStatus("DONE", 100L, null, 100L)).isEqualTo("PENDING");
    }

    @Test
    void doneFlipsToPendingWhenDueAgainByDate() {
        assertThat(MaintenanceController.effectiveStatus("DONE", null, PAST, null)).isEqualTo("PENDING");
    }
}
