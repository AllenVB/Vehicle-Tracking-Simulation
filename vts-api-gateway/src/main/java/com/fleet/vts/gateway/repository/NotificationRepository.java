package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
}
