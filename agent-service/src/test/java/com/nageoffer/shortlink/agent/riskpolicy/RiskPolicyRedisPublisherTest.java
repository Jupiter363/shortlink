package com.nageoffer.shortlink.agent.riskpolicy;

import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyDeleteResult;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyRedisPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskPolicyRedisPublisherTest {

    private static final String POLICY_KEY = "risk:policy:short-link:rate-limit:nurl.ink:abc123";
    private static final String REDIS_VALUE = "{\"policyId\":\"policy-001\",\"policyVersion\":1}";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-10T03:00:00Z"),
            ZoneId.of("Asia/Shanghai")
    );

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
    void publishesValueWithoutExpireTime() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        assertThat(publisher.publish(POLICY_KEY, REDIS_VALUE, null)).isTrue();

        verify(valueOperations).set(POLICY_KEY, REDIS_VALUE);
    }

    @Test
    void publishesValueWithPositiveTtlAndSkipsExpiredValue() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);

        assertThat(publisher.publish(
                POLICY_KEY,
                REDIS_VALUE,
                LocalDateTime.of(2026, 7, 10, 11, 1)
        )).isTrue();

        verify(valueOperations).set(eq(POLICY_KEY), eq(REDIS_VALUE), durationCaptor.capture());
        assertThat(durationCaptor.getValue()).isEqualTo(Duration.ofMinutes(1));
        clearInvocations(valueOperations);

        assertThat(publisher.publish(
                POLICY_KEY,
                REDIS_VALUE,
                LocalDateTime.of(2026, 7, 10, 10, 59)
        )).isFalse();
        verify(valueOperations, never()).set(eq(POLICY_KEY), eq(REDIS_VALUE), any(Duration.class));
    }

    @Test
    void compareAndDeleteMapsAllLuaResultsWithoutBlindDelete() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of(POLICY_KEY)),
                eq(REDIS_VALUE)
        )).thenReturn(1L, 0L, -1L);

        assertThat(publisher.compareAndDelete(POLICY_KEY, REDIS_VALUE))
                .isEqualTo(RiskPolicyDeleteResult.DELETED);
        assertThat(publisher.compareAndDelete(POLICY_KEY, REDIS_VALUE))
                .isEqualTo(RiskPolicyDeleteResult.ALREADY_ABSENT);
        assertThat(publisher.compareAndDelete(POLICY_KEY, REDIS_VALUE))
                .isEqualTo(RiskPolicyDeleteResult.VALUE_MISMATCH);

        verify(stringRedisTemplate, never()).delete(POLICY_KEY);
    }

    @Test
    void rejectsUnexpectedLuaResult() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of(POLICY_KEY)),
                eq(REDIS_VALUE)
        )).thenReturn(2L);

        assertThatThrownBy(() -> publisher.compareAndDelete(POLICY_KEY, REDIS_VALUE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unexpected risk policy delete result");
    }

    @Test
    void failsFastWhenLuaResourceIsMissing() {
        assertThatThrownBy(() -> new RiskPolicyRedisPublisher(
                stringRedisTemplate,
                CLOCK,
                new ClassPathResource("lua/missing_risk_policy_script.lua")
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Risk policy compare-and-delete script is required");
    }
}
