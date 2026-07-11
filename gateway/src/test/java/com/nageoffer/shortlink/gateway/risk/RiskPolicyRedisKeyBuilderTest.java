package com.nageoffer.shortlink.gateway.risk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RiskPolicyRedisKeyBuilderTest {

    private final RiskPolicyRedisKeyBuilder builder = new RiskPolicyRedisKeyBuilder();

    @Test
    void buildsPolicyAndRateKeys() {
        assertThat(builder.disableShortLinkKey("nurl.ink", "abc123"))
                .isEqualTo("risk:policy:short-link:disable:nurl.ink:abc123");
        assertThat(builder.rateLimitShortLinkKey("nurl.ink", "abc123"))
                .isEqualTo("risk:policy:short-link:rate-limit:nurl.ink:abc123");
        assertThat(builder.timeWindowShortLinkKey("nurl.ink", "abc123"))
                .isEqualTo("risk:policy:short-link:time-window:nurl.ink:abc123");
        assertThat(builder.blockIpKey("hash001"))
                .isEqualTo("risk:policy:ip:block:hash001");
        assertThat(builder.rateCounterKey("nurl.ink", "abc123", "hash001"))
                .isEqualTo("risk:rate:nurl.ink:abc123:hash001");
    }

    @Test
    void buildsShortLinkScopedBlockIpKey() {
        assertThat(builder.blockShortLinkIpKey("nurl.ink", "abc123", "hash-1"))
                .isEqualTo("risk:policy:short-link:block-ip:nurl.ink:abc123:hash-1");
    }
}
