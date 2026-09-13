package com.jupiter.shortlink.analytics.worker;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.config.ConfigException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

class WorkerControllerKafkaConfigTest {
    @Test
    void previousApiDeadlineConflictedWithKafkaDefaultRequestTimeoutBeforeConnecting() {
        var properties = WorkerController.sourceAdminProperties("127.0.0.1:1");
        properties.remove(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG);
        var failure = assertThrows(KafkaException.class, () -> AdminClient.create(properties));
        assertInstanceOf(ConfigException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("default.api.timeout.ms"));
        assertTrue(failure.getCause().getMessage().contains("request.timeout.ms"));
    }

    @Test
    void boundedRecoverySourceClientCanBeConstructedWithRealKafkaLibrary() {
        var properties = WorkerController.sourceAdminProperties("127.0.0.1:1");
        assertEquals(5000, properties.get(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG));
        assertEquals(10000, properties.get(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG));
        assertEquals(1, properties.get(AdminClientConfig.RETRIES_CONFIG));
        assertEquals(100, properties.get(AdminClientConfig.RETRY_BACKOFF_MS_CONFIG));
        assertDoesNotThrow(() -> {
            var admin = AdminClient.create(properties);
            admin.close(Duration.ZERO);
        });
    }
}
