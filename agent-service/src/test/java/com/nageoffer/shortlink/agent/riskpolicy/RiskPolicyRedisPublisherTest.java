package com.nageoffer.shortlink.agent.riskpolicy;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicySource;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyRedisPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskPolicyRedisPublisherTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-10T03:00:00Z"),
            ZoneId.of("Asia/Shanghai")
    );
    private static final LocalDateTime EFFECTIVE_TIME = LocalDateTime.ofInstant(CLOCK.instant(), CLOCK.getZone());

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RiskPolicyRedisPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new RiskPolicyRedisPublisher(stringRedisTemplate, CLOCK);
    }

    @Test
    void publishesPolicyWithoutExpireTime() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        RiskPolicy policy = shortLinkPolicy(null);

        assertThat(publisher.publish(policy)).isTrue();

        verify(valueOperations).set(policy.policyKey(), policy.policyPayloadJson());
    }

    @Test
    void publishesPolicyWithPositiveTtlAndSkipsExpiredPolicy() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        RiskPolicy futurePolicy = shortLinkPolicy(LocalDateTime.of(2026, 7, 10, 11, 1));
        ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);

        assertThat(publisher.publish(futurePolicy)).isTrue();

        verify(valueOperations).set(
                eq(futurePolicy.policyKey()),
                eq(futurePolicy.policyPayloadJson()),
                durationCaptor.capture()
        );
        assertThat(durationCaptor.getValue()).isEqualTo(Duration.ofMinutes(1));
        clearInvocations(valueOperations);

        RiskPolicy expiredPolicy = shortLinkPolicy(LocalDateTime.of(2026, 7, 10, 10, 59));
        assertThat(publisher.publish(expiredPolicy)).isFalse();
        verify(valueOperations, never()).set(
                eq(expiredPolicy.policyKey()),
                eq(expiredPolicy.policyPayloadJson()),
                any(Duration.class)
        );
    }

    @Test
    void revokesPolicyByDeletingRedisKey() {
        RiskPolicy policy = shortLinkPolicy(null);

        publisher.revoke(policy);

        verify(stringRedisTemplate).delete(policy.policyKey());
        verifyNoInteractions(valueOperations);
    }

    private RiskPolicy shortLinkPolicy(LocalDateTime expireTime) {
        return RiskPolicy.shortLinkPolicy(
                "policy-001",
                "risk:policy:short-link:rate-limit:nurl.ink:abc123",
                "publish:policy-001",
                1L,
                RiskPolicyAction.LIMIT_RATE,
                "gid-001",
                "nurl.ink",
                "abc123",
                "{\"action\":\"LIMIT_RATE\",\"limit\":60,\"windowSeconds\":60}",
                RiskPolicySource.AGENT_AUTO,
                "trace-001",
                "event-001",
                EFFECTIVE_TIME,
                expireTime
        );
    }
}
