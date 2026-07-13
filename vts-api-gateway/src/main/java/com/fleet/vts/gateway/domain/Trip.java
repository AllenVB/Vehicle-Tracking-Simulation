package com.fleet.vts.gateway.domain;

import com.fleet.vts.common.enums.TripStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "trip")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Column(name = "driver_id")
    private Long driverId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "start_location")
    private Point startLocation;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "end_location")
    private Point endLocation;

    @Column(name = "distance_km")
    private BigDecimal distanceKm;

    @Column(name = "avg_speed_kmh")
    private BigDecimal avgSpeedKmh;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "max_speed_kmh")
    private Integer maxSpeedKmh;

    @Column(name = "violation_count")
    private Integer violationCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private TripStatus status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
