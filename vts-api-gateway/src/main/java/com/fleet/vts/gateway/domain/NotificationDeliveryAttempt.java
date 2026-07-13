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

@Entity
@Table(name = "notification_delivery_attempt")
@Getter
@Setter
@NoArgsConstructor
public class NotificationDeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notification_id")
    private Long notificationId;

    @Column(name = "channel")
    private String channel;

    @Column(name = "attempt_no")
    private Integer attemptNo;

    @Column(name = "status")
    private String status;

    @Column(name = "error")
    private String error;

    @Column(name = "attempted_at")
    private Instant attemptedAt;
}
