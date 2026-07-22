package com.fleet.vts.common.event;

import lombok.Builder;

import java.time.Instant;

/**
 * An instruction for one device, published on {@code vehicle.command}.
 *
 * <p>It is broadcast rather than routed: the TCP session for a given IMEI lives on exactly one
 * ingestion instance, and nothing outside that instance knows which. Every instance therefore
 * reads every command and discards the ones whose device it is not holding — the same fan-out
 * a WebSocket cluster uses, and the reason this goes through Kafka instead of an HTTP call to
 * "the" ingestion service, which is not a thing that exists once there is more than one.
 *
 * <p>{@code commandId} is the row in {@code device_command}; the gateway writes the record and
 * ingestion moves it through its states, so the operator can see what happened to an
 * instruction that no session was there to receive.
 */
@Builder
public record DeviceCommandEvent(
        Long commandId,
        Long tenantId,
        Long vehicleId,
        String imei,
        String command,
        Instant issuedAt,
        String issuedBy,
        String correlationId
) {
}
