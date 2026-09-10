package com.jupiter.shortlink.gateway.risk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetSocketAddress;
import java.util.List;

class RiskClientIpResolverTest {

    @Test
    void usesForwardedHeadersOnlyForConfiguredPeerCidrs() {
        MockServerHttpRequest request =
                MockServerHttpRequest.get("/abc123")
                        .remoteAddress(new InetSocketAddress("10.0.0.9", 55000))
                        .header("X-Forwarded-For", "203.0.113.8, 10.0.0.1")
                        .header("X-Real-IP", "203.0.113.9")
                        .build();

        assertThat(new RiskClientIpResolver(List.of("10.0.0.0/24")).resolve(request))
                .isEqualTo("203.0.113.8");
        assertThat(new RiskClientIpResolver(List.of()).resolve(request)).isEqualTo("10.0.0.9");
    }
}
