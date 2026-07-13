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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "trip_point")
@Getter
@Setter
@NoArgsConstructor
public class TripPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trip_id")
    private Long tripId;

    @Column(name = "seq")
    private Integer seq;

    @Column(name = "ts")
    private Instant ts;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "location")
    private Point location;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "speed_kmh")
    private Integer speedKmh;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "heading")
    private Integer heading;
}
