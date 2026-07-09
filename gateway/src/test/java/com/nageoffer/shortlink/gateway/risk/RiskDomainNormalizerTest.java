package com.nageoffer.shortlink.gateway.risk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RiskDomainNormalizerTest {

    private final RiskDomainNormalizer normalizer = new RiskDomainNormalizer();

    @Test
    void removesDefaultPortsAndKeepsNonDefaultPorts() {
        assertThat(normalizer.normalize("nurl.ink")).isEqualTo("nurl.ink");
        assertThat(normalizer.normalize("nurl.ink:80")).isEqualTo("nurl.ink");
        assertThat(normalizer.normalize("nurl.ink:443")).isEqualTo("nurl.ink");
        assertThat(normalizer.normalize("127.0.0.1:8000")).isEqualTo("127.0.0.1:8000");
        assertThat(normalizer.normalize("LOCALHOST:5174")).isEqualTo("localhost:5174");
    }
}
