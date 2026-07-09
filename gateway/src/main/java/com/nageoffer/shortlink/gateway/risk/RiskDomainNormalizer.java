package com.nageoffer.shortlink.gateway.risk;

import org.springframework.util.StringUtils;

public class RiskDomainNormalizer {

    public String normalize(String host) {
        if (!StringUtils.hasText(host)) {
            return "";
        }
        String normalized = host.trim().toLowerCase();
        int portSeparator = normalized.lastIndexOf(':');
        if (portSeparator < 0) {
            return normalized;
        }
        String port = normalized.substring(portSeparator + 1);
        if ("80".equals(port) || "443".equals(port)) {
            return normalized.substring(0, portSeparator);
        }
        return normalized;
    }
}
