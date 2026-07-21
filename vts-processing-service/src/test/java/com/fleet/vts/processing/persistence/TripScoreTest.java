package com.fleet.vts.processing.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripScoreTest {

    @Test
    void flawlessJourneyScoresTen() {
        assertEquals(10, TripScore.of(300, 0, 0, 0));
    }

    @Test
    void oneSpeedingViolationPer100KmCostsHalfAPoint() {
        // İstenen oran bu: 100 km'de bir ihlal = 0.5 puan. Aşağı yuvarlandığı için 9.5 -> 9,
        // yani ihlal GÖRÜNÜR. Yukarı yuvarlansaydı 10'a döner ve ihlal kaybolurdu.
        assertEquals(9, TripScore.of(100, 1, 0, 0));
        // 100 km'de 5 ihlal -> 10 - 2.5 = 7.5 -> 7
        assertEquals(7, TripScore.of(100, 5, 0, 0));
        // 100 km'de 10 ihlal -> 10 - 5 = 5
        assertEquals(5, TripScore.of(100, 10, 0, 0));
    }

    @Test
    void tenMeansNothingWentWrong() {
        // Tek bir ihlal bile tam puanı bozar; 10 tek bir şey demektir.
        assertEquals(10, TripScore.of(1000, 0, 0, 0));
        assertTrue(TripScore.of(1000, 1, 0, 0) < 10,
                "a single violation must cost the perfect score");
    }

    @Test
    void scoreNeverLeavesTheOneToTenRange() {
        assertEquals(1, TripScore.of(50, 100, 100, 100));
        assertEquals(10, TripScore.of(10_000, 0, 0, 0));
    }

    @Test
    void harshBrakingCostsMoreThanSpeeding() {
        // Ağırlıklar korundu: sert fren, aynı sayıda hız ihlalinden ağır.
        int speeding = TripScore.of(100, 6, 0, 0);
        int harsh = TripScore.of(100, 0, 6, 0);
        assertTrue(harsh < speeding,
                "harsh braking should cost more: harsh=" + harsh + " speeding=" + speeding);
    }

    @Test
    void penaltyIsRelativeToDistanceDriven() {
        // Aynı ihlal sayısı uzun yolda daha az cezalandırılır -- yoksa çok süren araç
        // otomatik olarak en kötü sürücü olur.
        int shortRun = TripScore.of(100, 6, 0, 0);
        int longRun = TripScore.of(600, 6, 0, 0);
        assertTrue(longRun > shortRun,
                "same violations over more distance should score better: long=" + longRun
                        + " short=" + shortRun);
    }

    @Test
    void veryShortJourneyIsNotJudgedHarshly() {
        // 2 km'de tek bir sert fren: mesafe tabanı olmadan 100 km'de 83 ihlal oranına denk
        // gelir ve skoru 1'e çakardı. Taban mesafeyi 25 km sayar -> 10 - 3.33 = 6.67 -> 6.
        assertEquals(6, TripScore.of(2, 0, 1, 0),
                "the distance floor should keep a single event on a short run at 6/10");
    }
}
