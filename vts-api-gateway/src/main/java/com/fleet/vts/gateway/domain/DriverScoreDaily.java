package com.fleet.vts.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "driver_score_daily")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
public class DriverScoreDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "driver_id")
    private Long driverId;

    @Column(name = "score_date")
    private LocalDate scoreDate;

    @Column(name = "distance_km")
    private BigDecimal distanceKm;

    @Column(name = "harsh_braking_count")
    private Integer harshBrakingCount;

    @Column(name = "speeding_count")
    private Integer speedingCount;

    @Column(name = "idling_seconds")
    private Integer idlingSeconds;

    @Column(name = "violation_count")
    private Integer violationCount;

    @Column(name = "score")
    private BigDecimal score;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
