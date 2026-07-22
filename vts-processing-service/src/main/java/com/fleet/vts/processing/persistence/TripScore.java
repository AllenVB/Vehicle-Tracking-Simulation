package com.fleet.vts.processing.persistence;

/**
 * Scores a finished journey from 1 to 10. This is now the fleet's only driving score — the
 * nightly 0–100 driver score it used to mirror was removed, so nothing can disagree with it.
 *
 * <p>The rate is set so that <b>one speeding violation per 100 km costs half a point</b>. The
 * previous tuning made violations invisible: a speeding tick per 100 km cost 0.3 of a point,
 * which rounded straight back to 10/10, and 43 of 66 measured journeys scored a perfect ten.
 * A scoreboard where nearly everyone is flawless tells an operator nothing.
 *
 * <p>Kinds are weighted relative to each other, with speeding as the reference: mass and
 * driving style make a harsh brake say more about a journey than a moment over the limit.
 *
 * <p>The formula used to count three kinds and ignore four. That was inherited from the nightly
 * score this replaced, and it left a hole the operator could see: a vehicle that drove into a
 * restricted zone — the one violation the system rates CRITICAL — still scored 10/10, which by
 * this score's own definition means "nothing went wrong". The four missing kinds are counted
 * now, and the zone breach is weighted heaviest of all: it is the only one of them that is a
 * decision rather than a condition.
 */
final class TripScore {

    /** What one speeding violation per 100 km costs, out of ten. */
    private static final double PENALTY_PER_VIOLATION = 0.5;

    /** Severity relative to a speeding violation, which is the reference at 1.0. */
    private static final double SPEEDING_WEIGHT = 1.0;
    private static final double HARSH_WEIGHT = 5.0 / 3.0;
    private static final double IDLING_WEIGHT = 2.0 / 3.0;

    /**
     * Entering a restricted zone. Four times a speeding tick, and the heaviest thing here.
     *
     * <p>Every other kind describes how a vehicle was driven; this one describes where it was
     * taken, and the zone was marked forbidden by someone who meant it. It is also rare — a
     * fleet raises a handful a day — so a heavy weight does not drag every score down, it makes
     * the few journeys that earned it unmistakable.
     */
    private static final double GEOFENCE_WEIGHT = 4.0;

    /**
     * Sustained speeding: 80% of a five-minute window over the limit.
     *
     * <p>Heavier than a single {@code SPEED_LIMIT} tick because it is not a moment — it is five
     * minutes of choosing to stay there, and heavier than a hard brake, which is one reaction.
     * Not as heavy as a zone breach, which is a boundary rather than a margin.
     *
     * <p>2.5 rather than 2.0 for a reason worth stating: the score is floored to an integer, so
     * two weights within ~20% of each other land on the same number at every realistic
     * violation count. A distinction the output cannot express is not a distinction — either
     * the weights separate or they should be equal.
     */
    private static final double SUSTAINED_WEIGHT = 2.5;

    /**
     * Running the tank or the battery down. Lightest of all, and deliberately so: it is a
     * planning failure, not a dangerous one, and it belongs to whoever dispatched the vehicle
     * at least as much as to whoever is driving it.
     */
    private static final double SUPPLY_WEIGHT = 1.0 / 3.0;

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
     *
     * <p>Rounded DOWN, not to nearest. Rounding a 9.5 up to 10 would erase the very violation
     * this is meant to show — the point of the score is that a flawed journey does not read as
     * perfect. So ten means exactly one thing: nothing went wrong.
     */
    static int of(double distanceKm, int speeding, int harsh, int idling,
                  int geofence, int sustained, int supply) {
        double weightedViolations = speeding * SPEEDING_WEIGHT
                + harsh * HARSH_WEIGHT
                + idling * IDLING_WEIGHT
                + geofence * GEOFENCE_WEIGHT
                + sustained * SUSTAINED_WEIGHT
                + supply * SUPPLY_WEIGHT;
        double per100Km = weightedViolations / Math.max(distanceKm, MIN_DISTANCE_KM) * 100.0;

        double score = BEST - per100Km * PENALTY_PER_VIOLATION;
        return (int) Math.max(WORST, Math.min(BEST, Math.floor(score)));
    }
}
