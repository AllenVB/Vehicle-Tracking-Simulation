package com.fleet.vts.simulator;

import com.fleet.vts.simulator.model.BehaviorProfile;
import com.fleet.vts.simulator.model.GeoPoint;
import com.fleet.vts.simulator.model.Journey;
import com.fleet.vts.simulator.model.Route;
import com.fleet.vts.simulator.model.VehicleState;
import com.fleet.vts.simulator.sim.GeoUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure logic tests for geo helpers, route movement and anomaly shaping. */
class SimulationLogicTest {

    private static Route squareRoute() {
        return new Route(List.of(
                new GeoPoint(41.00, 29.00),
                new GeoPoint(41.00, 29.01),
                new GeoPoint(41.01, 29.01),
                new GeoPoint(41.01, 29.00)));
    }

    @Test
    void haversineAndBearingAreSane() {
        double km = GeoUtils.haversineKm(new GeoPoint(41.0, 29.0), new GeoPoint(41.0, 29.1));
        assertEquals(8.4, km, 0.3); // ~0.1 deg lon at lat 41

        double bearing = GeoUtils.bearingDeg(new GeoPoint(41.0, 29.0), new GeoPoint(41.0, 29.1));
        assertEquals(90.0, bearing, 1.0); // due east
    }

    @Test
    void vehicleMovesAlongRouteAndAccumulatesOdometer() {
        VehicleState v = new VehicleState("000000000000001", BehaviorProfile.NORMAL, squareRoute(), 1L, 60);
        long startOdo = v.odometerKm();
        for (int i = 0; i < 40; i++) {
            v.tick(5.0);
            assertTrue(v.speedKmh() >= 0 && v.speedKmh() <= 120);
            assertTrue(v.battery() >= 0 && v.battery() <= 100);
            assertTrue(v.lat() >= 40.999 && v.lat() <= 41.011, "lat in box: " + v.lat());
            assertTrue(v.lon() >= 28.999 && v.lon() <= 29.011, "lon in box: " + v.lon());
        }
        assertTrue(v.odometerKm() > startOdo, "odometer should accumulate");
    }

    @Test
    void highBaseSpeedVehicleStaysFast() {
        VehicleState v = new VehicleState("000000000000010", BehaviorProfile.NORMAL, squareRoute(), 10L, 105);
        for (int i = 0; i < 30; i++) {
            v.tick(5.0);
            assertTrue(v.speedKmh() > 80, "high-base vehicle should exceed 80: " + v.speedKmh());
            assertTrue(v.speedKmh() <= 120, "speed capped at 120: " + v.speedKmh());
        }
    }

    @Test
    void lowBatteryVehicleDropsBelowThreshold() {
        VehicleState v = new VehicleState("000000000000050", BehaviorProfile.LOW_BATTERY, squareRoute(), 50L);
        boolean dipped = false;
        for (int i = 0; i < 300 && !dipped; i++) {
            v.tick(5.0);
            dipped = v.battery() < 20;
        }
        assertTrue(dipped, "low-battery vehicle should fall under 20%");
    }

    @Test
    void openRouteDistanceIsTheRoadDistanceNotTheRoundTrip() {
        // A journey route must NOT count a closing segment back to the start, otherwise
        // "remaining km" is doubled and the vehicle never arrives.
        List<GeoPoint> line = List.of(new GeoPoint(41.0, 29.0), new GeoPoint(41.0, 29.1));
        double open = new Route(line, false).totalKm();
        double closed = new Route(line, true).totalKm();
        assertEquals(8.4, open, 0.3);
        assertEquals(2 * open, closed, 0.1);
    }

    @Test
    void journeyVehicleArrivesParksThenAsksForANewDestination() {
        VehicleState v = new VehicleState("000000000000001", BehaviorProfile.NORMAL, 41.0, 29.0, 1L, 90);
        assertTrue(v.needsJourney(), "a fresh vehicle wants a destination");

        Route road = new Route(List.of(new GeoPoint(41.0, 29.0), new GeoPoint(41.0, 29.1)), false);
        v.startJourney(new Journey("Testköy", 41.0, 29.1, road), 0.0);
        assertFalse(v.needsJourney());
        assertTrue(v.remainingKm() > 8, "remaining km comes from the real route");

        for (int i = 0; i < 5000 && !v.isParked(); i++) {
            v.tick(1.0);
        }
        assertTrue(v.isParked(), "vehicle arrives and parks");
        assertEquals(0, v.speedKmh(), "parked vehicles report 0 km/h — this is what closes the trip");
        assertEquals(0.0, v.remainingKm(), 0.01);

        // The dwell must outlast the 5-minute trip-stop window, then a new leg is requested.
        for (int i = 0; i < 2000 && !v.needsJourney(); i++) {
            v.tick(1.0);
        }
        assertTrue(v.needsJourney(), "asks for a new destination once the dwell is over");
    }

    @Test
    void profileBucketsMatchIntendedRatios() {
        assertEquals(BehaviorProfile.SPEEDER, BehaviorProfile.forIndex(10));
        assertEquals(BehaviorProfile.HARSH_BRAKING, BehaviorProfile.forIndex(20));
        assertEquals(BehaviorProfile.GEOFENCE, BehaviorProfile.forIndex(33));
        assertEquals(BehaviorProfile.LOW_BATTERY, BehaviorProfile.forIndex(50));
        assertEquals(BehaviorProfile.NORMAL, BehaviorProfile.forIndex(7));
    }
}
