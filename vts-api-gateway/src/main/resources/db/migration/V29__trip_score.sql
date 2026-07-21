-- Score every finished journey out of ten.
--
-- The fleet already has a driver score, but it is a DAILY figure computed overnight from all
-- of a driver's trips at once. That answers "who drives well"; it cannot answer "how did this
-- run go", which is the question an operator watching a vehicle park actually has.
--
-- Kept as a column on trip rather than a table of its own: there is exactly one score per
-- trip, it is written once when the trip closes, and it is read on the same row as the
-- distance and speeds it was derived from.
--
-- Nullable because a trip is inserted CLOSED with its score in the same statement, but rows
-- predating this migration have none and inventing one for them would be a lie about journeys
-- nobody scored.

ALTER TABLE trip ADD COLUMN score SMALLINT
    CHECK (score IS NULL OR score BETWEEN 1 AND 10);

COMMENT ON COLUMN trip.score IS
    'Journey quality 1..10, computed when the trip closes from its violations per 100 km. '
    'NULL for trips recorded before scoring existed.';
