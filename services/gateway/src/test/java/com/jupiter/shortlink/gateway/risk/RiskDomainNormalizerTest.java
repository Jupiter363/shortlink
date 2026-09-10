package com.jupiter.shortlink.gateway.risk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RiskDomainNormalizerTest {

    private final RiskDomainNormalizer normalizer = new RiskDomainNormalizer();

    @Test
    void removesDefaultPortsAndKeepsNonDefaultPorts() {
        assertThat(normalizer.normalize("nurl.ink", "https")).isEqualTo("nurl.ink");
        assertThat(normalizer.normalize("nurl.ink:80", "http")).isEqualTo("nurl.ink");
        assertThat(normalizer.normalize("nurl.ink:443", "https")).isEqualTo("nurl.ink");
        assertThat(normalizer.normalize("nurl.ink:80", "https")).isEqualTo("nurl.ink:80");
        assertThat(normalizer.normalize("127.0.0.1:8000", "http")).isEqualTo("127.0.0.1:8000");
        assertThat(normalizer.normalize("LOCALHOST:5174", "http")).isEqualTo("localhost:5174");
    }
}
