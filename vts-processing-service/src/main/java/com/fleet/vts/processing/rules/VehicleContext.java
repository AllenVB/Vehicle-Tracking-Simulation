package com.fleet.vts.processing.rules;

import java.util.Map;

/**
 * Per-vehicle context handed to each rule: the tenant, the driver attributed at
 * the time of the reading, and the effective rules keyed by code.
 */
public record VehicleContext(
        Long vehicleId,
        Long tenantId,
        Long driverId,
        Long deviceId,
        Map<String, RuleView> rules) {

    public RuleView rule(String code) {
        return rules.get(code);
    }
}
