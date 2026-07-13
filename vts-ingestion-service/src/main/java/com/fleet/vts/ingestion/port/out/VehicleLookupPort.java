package com.fleet.vts.ingestion.port.out;

import com.fleet.vts.ingestion.domain.VehicleRef;

import java.util.Optional;

/** Secondary (driven) port that resolves a device IMEI to its vehicle. */
public interface VehicleLookupPort {

    Optional<VehicleRef> findByImei(String imei);
}
