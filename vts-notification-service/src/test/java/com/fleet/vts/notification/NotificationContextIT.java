package com.fleet.vts.notification;

import com.fleet.vts.notification.sender.NotificationSenderRegistry;
import com.fleet.vts.notification.service.NotificationService;
import com.fleet.vts.testsupport.VtsInfra;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots notification against real Postgres, Kafka and Redis. The sender registry is a
 * Strategy lookup built from every {@code NotificationSender} bean, so an unregistered
 * channel shows up here rather than as a notification that is quietly never delivered.
 */
@SpringBootTest(properties = "management.tracing.sampling.probability=0.0")
class NotificationContextIT {

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        VtsInfra.all(registry);
    }

    @Autowired
    private NotificationService service;

    @Autowired
    private NotificationSenderRegistry senders;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Test
    void contextLoadsWithSendersAndListeners() {
        assertThat(service).isNotNull();
        assertThat(senders).isNotNull();
        assertThat(listenerRegistry.getListenerContainers()).isNotEmpty();
    }
}
