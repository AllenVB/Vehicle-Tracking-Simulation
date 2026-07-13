package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.NotificationDeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationDeliveryAttemptRepository extends JpaRepository<NotificationDeliveryAttempt, Long> {
}
