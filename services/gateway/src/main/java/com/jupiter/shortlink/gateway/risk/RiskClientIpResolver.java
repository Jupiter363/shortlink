package com.jupiter.shortlink.gateway.risk;

import com.jupiter.shortlink.risk.TrustedProxyResolver;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.InetSocketAddress;
import java.util.List;

public class RiskClientIpResolver {

    private final TrustedProxyResolver resolver;

    public RiskClientIpResolver(List<String> trustedProxyCidrs) {
        this.resolver = new TrustedProxyResolver(trustedProxyCidrs);
    }

    public String resolve(ServerHttpRequest request) {
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            throw new IllegalArgumentException("Missing peer address");
        }
        return resolver.resolve(
                remoteAddress.getAddress().getHostAddress(),
                request.getHeaders().getFirst("X-Forwarded-For"));
    }
}
