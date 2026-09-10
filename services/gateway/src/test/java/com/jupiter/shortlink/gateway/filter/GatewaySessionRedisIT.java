package com.jupiter.shortlink.gateway.filter;

import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.gateway.config.*;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

class GatewaySessionRedisIT {
    @Test
    void realRedisSessionExpiryAndIdentityBoundary() {
        String port = System.getenv("SHORTLINK_REDIS_TEST_PORT");
        assertNotNull(port, "Explicit isolated Redis port required");
        LettuceConnectionFactory connection =
                new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(port));
        connection.afterPropertiesSet();
        ReactiveStringRedisTemplate redis = new ReactiveStringRedisTemplate(connection);
        String username = "it-" + UUID.randomUUID(),
                token = UUID.randomUUID().toString(),
                key = "login_" + username;
        GatewaySecurityProperties config = new GatewaySecurityProperties();
        config.setInternalToken("server-secret");
        config.setAllowedHosts(List.of("admin.example"));
        config.setTrustedProxyCidrs(List.of("127.0.0.0/8"));
        config.setSessionTimeout(Duration.ofSeconds(1));
        var filter = new TokenValidateGatewayFilterFactory(redis, config).apply(new Config());
        try {
            redis.opsForHash()
                    .put(
                            key,
                            token,
                            "{\"tenantId\":42,\"username\":\""
                                    + username
                                    + "\",\"authVersion\":3,\"expiresAt\":"
                                    + (System.currentTimeMillis() + 30000)
                                    + "}")
                    .block();
            redis.expire(key, Duration.ofMinutes(30)).block();
            var exchange =
                    MockServerWebExchange.from(
                            MockServerHttpRequest.get(
                                            "http://admin.example/api/short-link/admin/v1/group")
                                    .header("Host", "admin.example")
                                    .header("username", username)
                                    .header("token", token)
                                    .header("x-shortlink-tenant-id", "999")
                                    .remoteAddress(new InetSocketAddress("127.0.0.1", 12345))
                                    .build());
            AtomicReference<String> actual = new AtomicReference<>();
            filter.filter(
                            exchange,
                            trusted -> {
                                actual.set(
                                        trusted.getRequest()
                                                .getHeaders()
                                                .getFirst("x-shortlink-tenant-id"));
                                assertNull(trusted.getRequest().getHeaders().getFirst("username"));
                                return Mono.empty();
                            })
                    .block();
            assertEquals("42", actual.get());
            redis.opsForHash()
                    .put(
                            key,
                            token,
                            "{\"tenantId\":42,\"username\":\""
                                    + username
                                    + "\",\"authVersion\":3,\"expiresAt\":1}")
                    .block();
            var expired =
                    MockServerWebExchange.from(
                            MockServerHttpRequest.get(exchange.getRequest().getURI().toString())
                                    .headers(exchange.getRequest().getHeaders())
                                    .remoteAddress(new InetSocketAddress("127.0.0.1", 12345))
                                    .build());
            filter.filter(
                            expired,
                            trusted -> {
                                fail(
                                        "Expired per-session entry cannot pass even while key TTL"
                                            + " is positive");
                                return Mono.empty();
                            })
                    .block();
            assertEquals(HttpStatus.UNAUTHORIZED, expired.getResponse().getStatusCode());
            assertTrue(redis.getExpire(key).block().toMillis() > 0);
        } finally {
            redis.delete(key).block();
            connection.destroy();
        }
    }
}
