package com.fleet.vts.ingestion.adapter.in.web;

import com.fleet.vts.ingestion.domain.FuelStation;
import com.fleet.vts.ingestion.port.out.FuelStationLookupPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Reference data the simulator needs but cannot reach: it holds no database connection by
 * design, being the fleet's source of truth for positions rather than a reader of them.
 *
 * <p>This lives on ingestion rather than the gateway because ingestion already owns a
 * database connection for its IMEI lookup, is the service the simulator already talks to,
 * and carries no Spring Security — so serving a list of public petrol stations here adds no
 * authenticated surface, whereas opening a permitAll path on the gateway would.
 */
@RestController
@RequestMapping("/api/v1/reference")
public class ReferenceDataController {

    private final FuelStationLookupPort fuelStations;

    public ReferenceDataController(FuelStationLookupPort fuelStations) {
        this.fuelStations = fuelStations;
    }

    /** Every fuel station, for a caller that will cache the list and pick the nearest itself. */
    @GetMapping("/fuel-stations")
    public List<FuelStation> fuelStations() {
        return fuelStations.findAll();
    }
}
