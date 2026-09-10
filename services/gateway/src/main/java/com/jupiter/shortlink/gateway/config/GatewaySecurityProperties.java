package com.jupiter.shortlink.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "shortlink.gateway")
public class GatewaySecurityProperties {
    private Duration sessionTimeout = Duration.ofMillis(250);
    private List<String> trustedProxyCidrs = List.of(), allowedHosts = List.of();
    private String internalToken = "", adminUri = "http://127.0.0.1:8002";

    public Duration getSessionTimeout() {
        return sessionTimeout;
    }

    public void setSessionTimeout(Duration value) {
        sessionTimeout = value;
    }

    public List<String> getTrustedProxyCidrs() {
        return trustedProxyCidrs;
    }

    public void setTrustedProxyCidrs(List<String> value) {
        trustedProxyCidrs = List.copyOf(value);
    }

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(List<String> value) {
        allowedHosts = List.copyOf(value);
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String value) {
        internalToken = value;
    }

    public String getAdminUri() {
        return adminUri;
    }

    public void setAdminUri(String value) {
        adminUri = value;
    }
}
