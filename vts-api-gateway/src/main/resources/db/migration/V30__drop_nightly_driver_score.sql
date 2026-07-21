-- Remove the nightly driver score. The journey score on `trip` is now the only driving score.
--
-- Two scores measuring the same thing on different scales is worse than one: an operator saw a
-- run praised at 9/10 while the overnight scoreboard marked the same driving 62/100, and there
-- was no way to tell which number to believe. A driver's standing is now simply the average of
-- their journeys — derived on read from trip.score, so it cannot drift from what the operator
-- was shown when each of those journeys ended.
--
-- This also drops a whole moving part: two scheduled jobs (one nightly, one every two minutes)
-- that recomputed and rewrote these tables. Nothing recomputes now; the numbers come from the
-- trips themselves.
--
-- Destructive on purpose. The daily rows were derived data — every score in them was computed
-- from `trip` and `violation`, both of which are still here, so nothing original is lost.

DROP TABLE IF EXISTS driver_score_period;
DROP TABLE IF EXISTS driver_score_daily;
