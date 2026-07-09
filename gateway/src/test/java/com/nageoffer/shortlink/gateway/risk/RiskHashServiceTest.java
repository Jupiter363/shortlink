package com.nageoffer.shortlink.gateway.risk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskHashServiceTest {

    @Test
    void hashesWithSaltAndRejectsBlankSalt() {
        RiskHashService hashService = new RiskHashService("risk-test-salt");

        String hash = hashService.sha256("203.0.113.8");

        assertThat(hash).hasSize(64);
        assertThat(hash).doesNotContain("203.0.113.8");
        assertThat(hashService.sha256("203.0.113.8")).isEqualTo(hash);
        assertThatThrownBy(() -> new RiskHashService(" ").sha256("203.0.113.8"))
                .isInstanceOf(IllegalStateException.class);
    }
}
