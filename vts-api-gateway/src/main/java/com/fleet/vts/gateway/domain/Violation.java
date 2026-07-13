package com.fleet.vts.gateway.domain;

import com.fleet.vts.common.enums.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

/**
 * Read model over the {@code violation} TimescaleDB hypertable. The physical
 * primary key is composite {@code (id, occurred_at)}; here we map only {@code id}
 * (no {@code @GeneratedValue}) plus a plain {@code occurredAt} field, since this
 * entity is used for reads only (writes are handled elsewhere).
 */
@Entity
@Table(name = "violation")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
public class Violation {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Column(name = "driver_id")
    private Long driverId;

    @Column(name = "device_id")
    private Long deviceId;

    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "rule_code")
    private String ruleCode;

    @Column(name = "type")
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity")
    private Severity severity;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Column(name = "value")
    private BigDecimal value;

    @Column(name = "threshold")
    private BigDecimal threshold;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "location")
    private Point location;

    @Column(name = "trip_id")
    private Long tripId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail")
    private String detail;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
