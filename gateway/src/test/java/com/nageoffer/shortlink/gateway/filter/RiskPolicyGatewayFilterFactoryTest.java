package com.nageoffer.shortlink.gateway.filter;

import com.nageoffer.shortlink.gateway.config.Config;
import com.nageoffer.shortlink.gateway.risk.RiskHashService;
import com.nageoffer.shortlink.gateway.risk.RiskPolicyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskPolicyGatewayFilterFactoryTest {

    private static final String HASH_SALT = "risk-test-salt";
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-10T03:00:00Z"),
            ZoneId.of("Asia/Shanghai")
    );

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private GatewayFilterChain chain;

    private RiskPolicyGatewayFilterFactory filter;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.get(anyString())).thenReturn(null);
        lenient().when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
        filter = new RiskPolicyGatewayFilterFactory(stringRedisTemplate, properties(), FIXED_CLOCK);
    }

    @Test
    void disablePolicyReturns404AndDoesNotCallDownstreamChain() {
        when(valueOperations.get("risk:policy:short-link:disable:nurl.ink:abc123"))
                .thenReturn("{\"action\":\"DISABLE_SHORT_LINK\"}");
        ServerWebExchange exchange = exchange("nurl.ink", "/abc123", "203.0.113.8");

        StepVerifier.create(filter.apply(new Config()).filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(chain, never()).filter(any());
    }

    @Test
    void blockIpPolicyReturns403() {
        String ipHash = new RiskHashService(HASH_SALT).sha256("203.0.113.8");
        when(valueOperations.get("risk:policy:ip:block:" + ipHash))
                .thenReturn("{\"action\":\"BLOCK_IP\"}");
        ServerWebExchange exchange = exchange("nurl.ink", "/abc123", "203.0.113.8");

        StepVerifier.create(filter.apply(new Config()).filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(chain, never()).filter(any());
    }

    @Test
    void rateLimitPolicyReturns429WhenCounterExceedsLimit() {
        when(valueOperations.get("risk:policy:short-link:rate-limit:nurl.ink:abc123"))
                .thenReturn("{\"action\":\"LIMIT_RATE\",\"limit\":1,\"windowSeconds\":60}");
        when(valueOperations.increment(startsWith("risk:rate:nurl.ink:abc123:"))).thenReturn(2L);
        ServerWebExchange exchange = exchange("nurl.ink", "/abc123", "203.0.113.8");

        StepVerifier.create(filter.apply(new Config()).filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(chain, never()).filter(any());
    }

    @Test
    void timeWindowPolicyReturns403OutsideAllowedWindow() {
        when(valueOperations.get("risk:policy:short-link:time-window:nurl.ink:abc123"))
                .thenReturn("{\"action\":\"LIMIT_TIME_WINDOW\",\"timezone\":\"Asia/Shanghai\",\"allowedWindows\":[\"09:00-10:00\"]}");
        ServerWebExchange exchange = exchange("nurl.ink", "/abc123", "203.0.113.8");

        StepVerifier.create(filter.apply(new Config()).filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(chain, never()).filter(any());
    }

    @Test
    void passesThroughWhenNoRiskPolicyMatches() {
        ServerWebExchange exchange = exchange("nurl.ink", "/abc123", "203.0.113.8");

        StepVerifier.create(filter.apply(new Config()).filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        verify(chain).filter(exchange);
    }

    private ServerWebExchange exchange(String host, String path, String ip) {
        MockServerHttpRequest request = MockServerHttpRequest.get(path)
                .header(HttpHeaders.HOST, host)
                .remoteAddress(new InetSocketAddress(ip, 55000))
                .build();
        return MockServerWebExchange.from(request);
    }

    private RiskPolicyProperties properties() {
        RiskPolicyProperties properties = new RiskPolicyProperties();
        properties.setHashSalt(HASH_SALT);
        properties.setTrustedProxyEnabled(false);
        properties.setNotFoundMode("status");
        properties.setClockZone("Asia/Shanghai");
        return properties;
    }
}
