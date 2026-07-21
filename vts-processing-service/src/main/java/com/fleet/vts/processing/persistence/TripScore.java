package com.fleet.vts.processing.persistence;

/**
 * Scores a finished journey from 1 to 10.
 *
 * <p>Deliberately the same shape as the nightly driver score (see {@code ScheduledJobs}): the
 * same violation weights, the same per-100 km normalisation, the same floor on distance. A
 * second, differently-tuned notion of "good driving" would be worse than no trip score at all —
 * an operator would see a run praised at 9/10 that the daily scoreboard then marks the driver
 * down for, and neither number would be believed again.
 *
 * <p>What differs is only the scale and the window: one journey, out of ten.
 */
final class TripScore {

    /** Weights per violation kind: a harsh brake says more about a journey than a speeding tick. */
    private static final int SPEEDING_WEIGHT = 3;
    private static final int HARSH_WEIGHT = 5;
    private static final int IDLING_WEIGHT = 2;

    /**
     * Distance floor, in km, for the per-100 km rate.
     *
     * <p>Without it a short journey is scored by an almost-zero denominator: one harsh brake in
     * the first 2 km would read as 250 penalty points per 100 km and bottom out the score. The
     * floor says short runs are not evidence of much either way.
     */
    private static final double MIN_DISTANCE_KM = 25.0;

    private static final int BEST = 10;
    private static final int WORST = 1;

    private TripScore() {
    }

    /**
     * The journey's score. Ten when nothing went wrong; one is the floor, because a journey that
     * still ended where it was meant to is never a total loss.
     */
    static int of(double distanceKm, int speeding, int harsh, int idling) {
        double penaltyPoints = speeding * SPEEDING_WEIGHT
                + harsh * HARSH_WEIGHT
                + idling * IDLING_WEIGHT;
        double per100Km = penaltyPoints / Math.max(distanceKm, MIN_DISTANCE_KM) * 100.0;

        // 100-point scale first, so this stays visibly the daily formula, then down to ten.
        double outOfHundred = 100.0 - per100Km;
        int score = (int) Math.round(outOfHundred / 10.0);
        return Math.max(WORST, Math.min(BEST, score));
    }
}
