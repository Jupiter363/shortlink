package com.jupiter.shortlink.gateway.risk;

import com.jupiter.shortlink.risk.HostNormalizer;

public class RiskDomainNormalizer {

    public String normalize(String host, String scheme) {
        return new HostNormalizer().normalize(host, scheme);
    }
}
