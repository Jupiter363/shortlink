package com.nageoffer.shortlink.gateway.risk;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;

import java.net.InetSocketAddress;

public class RiskClientIpResolver {

    private final boolean trustedProxyEnabled;

    public RiskClientIpResolver(boolean trustedProxyEnabled) {
        this.trustedProxyEnabled = trustedProxyEnabled;
    }

    public String resolve(ServerHttpRequest request) {
        if (trustedProxyEnabled) {
            String forwardedFor = firstForwardedFor(request.getHeaders().getFirst("X-Forwarded-For"));
            if (StringUtils.hasText(forwardedFor)) {
                return forwardedFor;
            }
            String realIp = request.getHeaders().getFirst("X-Real-IP");
            if (StringUtils.hasText(realIp)) {
                return realIp.trim();
            }
        }
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return "unknown";
        }
        return remoteAddress.getAddress().getHostAddress();
    }

    private String firstForwardedFor(String forwardedFor) {
        if (!StringUtils.hasText(forwardedFor)) {
            return "";
        }
        String[] parts = forwardedFor.split(",");
        for (String part : parts) {
            if (StringUtils.hasText(part)) {
                return part.trim();
            }
        }
        return "";
    }
}
