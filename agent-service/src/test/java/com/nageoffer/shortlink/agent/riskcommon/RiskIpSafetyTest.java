package com.nageoffer.shortlink.agent.riskcommon;

import com.nageoffer.shortlink.agent.riskcommon.safety.RiskIpSafety;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class RiskIpSafetyTest {

    @Test
    void detectsAndMasksValidIpv4AndIpv6Only() {
        assertThat(RiskIpSafety.containsRawIpLiteral("source 203.0.113.8")).isTrue();
        assertThat(RiskIpSafety.containsRawIpLiteral("source 2001:db8::44")).isTrue();
        assertThat(RiskIpSafety.sanitizeIpLiterals("source 203.0.113.8 and 2001:db8::44"))
                .isEqualTo("source 203.0.*.* and 2001:db8:*:*");

        assertThat(RiskIpSafety.containsRawIpLiteral("version 999.999.999.999")).isFalse();
        assertThat(RiskIpSafety.sanitizeIpLiterals("version 999.999.999.999"))
                .isEqualTo("version 999.999.999.999");
    }

    @Test
    void scansManyColonRichNonAddressesWithinLinearTime() {
        String value = ("1:2:3:4:5:6 ").repeat(10_000);

        assertTimeout(Duration.ofSeconds(2), () ->
                assertThat(RiskIpSafety.containsRawIpLiteral(value)).isFalse()
        );
    }
}
