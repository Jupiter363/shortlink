package com.jupiter.shortlink.admin.config;

import com.jupiter.shortlink.admin.common.biz.user.AdminIngressProperties;
import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class AdminIngressConfigurationTest {
    @Test void actualLettuceOptionsBoundCommandsAndDisconnectedQueuesWithoutDisablingRecovery() {
        var properties = new AdminIngressProperties();
        var builder = LettuceClientConfiguration.builder();
        new UserConfiguration().boundedAdminRedis(properties).customize(builder);
        var client = builder.build();
        assertEquals(Duration.ofMillis(150), client.getCommandTimeout());
        var options = client.getClientOptions().orElseThrow();
        assertEquals(256, options.getRequestQueueSize());
        assertEquals(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS, options.getDisconnectedBehavior());
        assertTrue(options.isAutoReconnect());
        assertEquals(Duration.ofSeconds(1), options.getSocketOptions().getConnectTimeout());
    }

    @Test void invalidTimeoutsOrQueueSizesCannotRemoveTheBound() {
        var properties = new AdminIngressProperties();
        assertThrows(IllegalArgumentException.class, () -> properties.setSessionTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> properties.setRedisConnectTimeout(Duration.ofSeconds(3)));
        assertThrows(IllegalArgumentException.class, () -> properties.setRedisRequestQueueSize(0));
        assertThrows(IllegalArgumentException.class, () -> properties.setRedisRequestQueueSize(Integer.MAX_VALUE));
        assertTrue(properties.getAllowedHosts().isEmpty());
        assertTrue(properties.getTrustedProxyCidrs().isEmpty());
    }
}
