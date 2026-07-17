package com.fleet.vts.gateway.web.dto;

import java.util.List;

/**
 * A vehicle category and the types under it, as the fleet taxonomy exposes them.
 *
 * <p>{@code types} can be empty — SEA is registered with nothing under it yet, and the API
 * says so rather than hiding the category until a boat exists.
 */
public record VehicleCategoryDto(
        String code,
        String label,
        List<VehicleTypeDto> types) {

    /** One concrete type, with the vehicle count currently registered against it. */
    public record VehicleTypeDto(String code, String label, long vehicleCount) {
    }
}
