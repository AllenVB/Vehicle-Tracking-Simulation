package com.fleet.vts.ingestion.port.out;

import com.fleet.vts.ingestion.domain.FuelStation;

import java.util.List;

/** Reads the fuel-station reference list. */
public interface FuelStationLookupPort {

    List<FuelStation> findAll();
}
