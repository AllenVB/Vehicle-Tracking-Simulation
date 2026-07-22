package com.fleet.vts.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

/**
 * One instruction sent to a device, and what came back.
 *
 * <p>The entity predates the feature: it mapped the placeholder shape V3 declared
 * ({@code command_type}, a JSON {@code payload}, an {@code app_user} reference) for a channel
 * that did not exist yet. V32 reshaped the table around what Codec 12 actually carries — an
 * ASCII command and an ASCII answer — and this follows it.
 *
 * <p>Nothing reads through this mapping today; the command endpoints use JDBC, because they
 * work in sets and need {@code RETURNING id}. It is kept because {@code ddl-auto=validate}
 * makes every entity a standing assertion about the schema — which is exactly how the rename
 * above was caught, before a running system ever saw it.
 */
@Entity
@Table(name = "device_command")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
public class DeviceCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Column(name = "device_id")
    private Long deviceId;

    /** The device is addressed by IMEI, so the record keeps it alongside the vehicle. */
    @Column(name = "imei")
    private String imei;

    @Column(name = "command")
    private String command;

    @Column(name = "response")
    private String response;

    @Column(name = "status")
    private String status;

    /** The operator's username. Text, so the record survives the account being removed. */
    @Column(name = "issued_by")
    private String issuedBy;

    @Column(name = "issued_at")
    private Instant issuedAt;

    /** When it reached the socket — the line between "we tried" and "the device has it". */
    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "executed_at")
    private Instant executedAt;
}
