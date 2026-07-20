-- LOW_FUEL becomes one number for the whole fleet: 25%.
--
-- The map now warns at 25% and sends the vehicle to the nearest station at the same point.
-- Leaving the violation thresholds per-type (car 15, truck 20, motorcycle 10) would have made
-- "low fuel" mean two different things at once: a car could be driving to a pump with its
-- warning light on while the rule still considered its tank fine, and the recorded violation
-- would land ten points later for no reason an operator could see.
--
-- The per-type split was justified while this was only a violation — a truck's 20% is still
-- hundreds of km, a motorcycle's 10% a normal reserve. It stops being justified once the
-- number also decides when a vehicle abandons its route to refuel, because that decision is
-- about reaching a pump in time, which the tank percentage alone does not tell you.

UPDATE rule SET threshold_value = 25 WHERE code = 'LOW_FUEL';

DELETE FROM rule_assignment ra
USING rule r
WHERE ra.rule_id = r.id
  AND ra.scope_type = 'VEHICLE_TYPE'
  AND r.code = 'LOW_FUEL';
