package com.jupiter.shortlink.agent.riskpolicy;

import static org.assertj.core.api.Assertions.*;

import com.jupiter.shortlink.agent.riskpolicy.service.RiskPolicyRedisPublisher;

import org.junit.jupiter.api.Test;

class RiskPolicyRedisPublisherTest {
    @Test
    void publicationIsOwnedByCommandAndCannotReachRedis() {
        var guard = new RiskPolicyRedisPublisher(new Object());
        assertThatThrownBy(() -> guard.publish(null)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> guard.revoke(null)).isInstanceOf(SecurityException.class);
        assertThat(RiskPolicyRedisPublisher.class.getAnnotations())
                .noneMatch(a -> a.annotationType().getName().startsWith("org.springframework"));
    }
}
