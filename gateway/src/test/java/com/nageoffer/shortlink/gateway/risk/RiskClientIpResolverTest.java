package com.nageoffer.shortlink.gateway.risk;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class RiskClientIpResolverTest {

    @Test
    void usesForwardedHeadersOnlyWhenTrustedProxyEnabled() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/abc123")
                .remoteAddress(new InetSocketAddress("10.0.0.9", 55000))
                .header("X-Forwarded-For", "203.0.113.8, 10.0.0.1")
                .header("X-Real-IP", "203.0.113.9")
                .build();

        assertThat(new RiskClientIpResolver(true).resolve(request)).isEqualTo("203.0.113.8");
        assertThat(new RiskClientIpResolver(false).resolve(request)).isEqualTo("10.0.0.9");
    }
}
