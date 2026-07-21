package com.fleet.vts.processing.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripScoreTest {

    @Test
    void cleanJourneyScoresTen() {
        assertEquals(10, TripScore.of(300, 0, 0, 0));
    }

    @Test
    void scoreNeverLeavesTheOneToTenRange() {
        // Felaket bir yolculuk bile 1'in altına inmez: hedefine varmış bir sefer tam kayıp değil.
        assertEquals(1, TripScore.of(50, 100, 100, 100));
        // Ve hiçbir girdi 10'un üstüne çıkaramaz.
        assertEquals(10, TripScore.of(10_000, 0, 0, 0));
    }

    @Test
    void harshBrakingCostsMoreThanSpeeding() {
        // Aynı sayıda ihlal, farklı ağırlık: sert fren yolculuk hakkında daha çok şey söyler.
        int speeding = TripScore.of(100, 5, 0, 0);
        int harsh = TripScore.of(100, 0, 5, 0);
        assertTrue(harsh < speeding,
                "harsh braking should cost more than speeding: harsh=" + harsh + " speeding=" + speeding);
    }

    @Test
    void penaltyIsRelativeToDistanceDriven() {
        // Aynı ihlal sayısı, uzun yolda daha az cezalandırılır -- yoksa çok süren araç
        // otomatik olarak en kötü sürücü olur.
        int shortRun = TripScore.of(50, 4, 0, 0);
        int longRun = TripScore.of(500, 4, 0, 0);
        assertTrue(longRun > shortRun,
                "same violations over more distance should score better: long=" + longRun + " short=" + shortRun);
    }

    @Test
    void veryShortJourneyIsNotJudgedHarshly() {
        // 2 km'de tek bir sert fren: mesafe tabanı olmasaydı 5 ceza / 2 km = 100 km'de 250
        // puan ederdi ve skor 1'e çakılırdı. Taban mesafeyi 25 km sayar, yani 20 puan -> 8/10.
        assertEquals(8, TripScore.of(2, 0, 1, 0),
                "the distance floor should keep a single event on a short run at 8/10");
    }
}
