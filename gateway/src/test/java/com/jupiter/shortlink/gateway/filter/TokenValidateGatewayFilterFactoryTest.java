package com.jupiter.shortlink.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.gateway.config.Config;
import com.jupiter.shortlink.gateway.config.GatewaySecurityProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

class TokenValidateGatewayFilterFactoryTest {
    private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ReactiveHashOperations<String, String, String> hashes =
            mock(ReactiveHashOperations.class);

    private GatewaySecurityProperties properties;
    private TokenValidateGatewayFilterFactory factory;
    private static final String TOKEN = "a".repeat(64);

    @BeforeEach
    void setUp() {
        properties = new GatewaySecurityProperties();
        properties.setAllowedHosts(List.of("admin.example.test"));
        properties.setTrustedProxyCidrs(List.of("10.0.0.0/24"));
        properties.setInternalToken("test-service-secret");
        properties.setSessionTimeout(Duration.ofMillis(20));
        when(redis.<String, String>opsForHash()).thenReturn(hashes);
        factory =
                new TokenValidateGatewayFilterFactory(
                        redis, properties, Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC));
    }

    private MockServerHttpRequest.BaseBuilder<?> request(String method, String path) {
        return MockServerHttpRequest.method(
                        org.springframework.http.HttpMethod.valueOf(method),
                        "https://admin.example.test" + path)
                .remoteAddress(new InetSocketAddress("10.0.0.5", 53000))
                .header("Host", "admin.example.test");
    }

    @Test
    void authenticatedIdentityComesOnlyFromMinimalSession() {
        when(hashes.get("login_alice", TOKEN))
                .thenReturn(
                        Mono.just(
                                "{\"tenantId\":42,\"username\":\"alice\",\"authVersion\":3,\"expiresAt\":2000}"));
        var exchange =
                MockServerWebExchange.from(
                        request("GET", "/api/short-link/admin/v1/group")
                                .header("username", "alice")
                                .header("token", TOKEN)
                                .header("userId", "victim")
                                .header("x-shortlink-tenant-id", "999")
                                .header("X-Agent-Username", "victim")
                                .header("X-Internal-Token", "forged")
                                .build());
        AtomicReference<org.springframework.http.HttpHeaders> captured = new AtomicReference<>();
        StepVerifier.create(
                        factory.apply(new Config())
                                .filter(
                                        exchange,
                                        value -> {
                                            captured.set(value.getRequest().getHeaders());
                                            return Mono.empty();
                                        }))
                .verifyComplete();
        assertThat(captured.get().getFirst("x-shortlink-tenant-id")).isEqualTo("42");
        assertThat(captured.get().getFirst("x-shortlink-auth-version")).isEqualTo("3");
        assertThat(captured.get().getFirst("X-Internal-Token")).isEqualTo("test-service-secret");
        assertThat(captured.get().containsKey("userId")).isFalse();
        assertThat(captured.get().containsKey("X-Agent-Username")).isFalse();
        assertThat(captured.get().containsKey("token")).isFalse();
    }

    @Test
    void publicEndpointIsExactAndMethodBound() {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        for (String path :
                List.of(
                        "/api/short-link/admin/v1/user/login-extra",
                        "/api/short-link/admin/v1/user/login/child")) {
            var exchange = MockServerWebExchange.from(request("POST", path).build());
            StepVerifier.create(factory.apply(new Config()).filter(exchange, chain))
                    .verifyComplete();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        var getLogin =
                MockServerWebExchange.from(
                        request("GET", "/api/short-link/admin/v1/user/login").build());
        StepVerifier.create(factory.apply(new Config()).filter(getLogin, chain)).verifyComplete();
        assertThat(getLogin.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(chain, hashes);
    }

    @Test
    void loginAlsoDiscardsForgedIdentity() {
        var exchange =
                MockServerWebExchange.from(
                        request("POST", "/api/short-link/admin/v1/user/login")
                                .header("x-shortlink-tenant-id", "999")
                                .header("username", "victim")
                                .build());
        StepVerifier.create(
                        factory.apply(new Config())
                                .filter(
                                        exchange,
                                        value -> {
                                            assertThat(
                                                            value.getRequest()
                                                                    .getHeaders()
                                                                    .containsKey(
                                                                            "x-shortlink-tenant-id"))
                                                    .isFalse();
                                            assertThat(
                                                            value.getRequest()
                                                                    .getHeaders()
                                                                    .containsKey("username"))
                                                    .isFalse();
                                            return Mono.empty();
                                        }))
                .verifyComplete();
        verifyNoInteractions(hashes);
    }

    @Test
    void expiredMalformedAndWrongUserSessionsFailClosed() {
        for (String body :
                List.of(
                        "{}",
                        "{\"tenantId\":42,\"username\":\"alice\",\"authVersion\":1,\"expiresAt\":1000}",
                        "{\"tenantId\":42,\"username\":\"bob\",\"authVersion\":1,\"expiresAt\":2000}",
                        "{\"password\":\"secret\"}")) {
            when(hashes.get("login_alice", TOKEN)).thenReturn(Mono.just(body));
            var exchange =
                    MockServerWebExchange.from(
                            request("GET", "/api/short-link/admin/v1/group")
                                    .header("username", "alice")
                                    .header("token", TOKEN)
                                    .build());
            StepVerifier.create(
                            factory.apply(new Config())
                                    .filter(
                                            exchange,
                                            value ->
                                                    Mono.error(
                                                            new AssertionError(
                                                                    "forwarded invalid session"))))
                    .verifyComplete();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void redisTimeoutIs503AndNeverForwards() {
        when(hashes.get("login_alice", TOKEN)).thenReturn(Mono.never());
        var exchange =
                MockServerWebExchange.from(
                        request("GET", "/api/short-link/admin/v1/group")
                                .header("username", "alice")
                                .header("token", TOKEN)
                                .build());
        StepVerifier.create(
                        factory.apply(new Config())
                                .filter(
                                        exchange,
                                        value -> Mono.error(new AssertionError("forwarded"))))
                .verifyComplete();
        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void internalPathsAndPublicShortcodesNeverReachManagement() {
        for (String path :
                List.of(
                        "/internal/short-link-command/v1/risk/ready",
                        "/AbCd",
                        "/api/short-link/admin/v1/../internal")) {
            var exchange = MockServerWebExchange.from(request("GET", path).build());
            StepVerifier.create(
                            factory.apply(new Config())
                                    .filter(
                                            exchange,
                                            value -> Mono.error(new AssertionError("forwarded"))))
                    .verifyComplete();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void directUntrustedPeerCannotSpoofProxyHeaders() {
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get(
                                        "https://admin.example.test/api/short-link/admin/v1/user/login")
                                .header("Host", "admin.example.test")
                                .header("X-Forwarded-For", "10.0.0.2")
                                .remoteAddress(new InetSocketAddress("203.0.113.9", 52000))
                                .build());
        StepVerifier.create(
                        factory.apply(new Config())
                                .filter(
                                        exchange,
                                        value -> Mono.error(new AssertionError("forwarded"))))
                .verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
