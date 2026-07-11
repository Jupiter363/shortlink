package com.nageoffer.shortlink.agent.riskpolicy.outbox;

import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskPolicySyncWorkerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 12, 12, 0);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-12T04:00:00Z"),
            ZoneId.of("Asia/Shanghai")
    );

    @Test
    void claimsAndProcessesAtMostOneOutbox() {
        JdbcRiskPolicySyncOutboxRepository repository = mock(JdbcRiskPolicySyncOutboxRepository.class);
        RiskPolicySyncService service = mock(RiskPolicySyncService.class);
        AgentProperties properties = new AgentProperties();
        RiskPolicySyncOutbox outbox = processingOutbox(1);
        when(repository.claimNext("worker-1", NOW, Duration.ofMinutes(5), 10))
                .thenReturn(Optional.of(outbox));
        RiskPolicySyncWorker worker = new RiskPolicySyncWorker(
                repository, service, properties, CLOCK, () -> "worker-1");

        assertThat(worker.runNext()).isTrue();

        verify(service).process(outbox, "worker-1", NOW);
        verify(repository).claimNext("worker-1", NOW, Duration.ofMinutes(5), 10);
    }

    @Test
    void redisFailureUsesExponentialBackoffAndRecordsFailure() {
        JdbcRiskPolicySyncOutboxRepository repository = mock(JdbcRiskPolicySyncOutboxRepository.class);
        RiskPolicySyncService service = mock(RiskPolicySyncService.class);
        AgentProperties properties = new AgentProperties();
        RiskPolicySyncOutbox outbox = processingOutbox(4);
        when(repository.claimNext(any(), eq(NOW), any(), eq(10)))
                .thenReturn(Optional.of(outbox));
        doThrow(new IllegalStateException("Redis unavailable"))
                .when(service).process(outbox, "worker-1", NOW);
        RiskPolicySyncWorker worker = new RiskPolicySyncWorker(
                repository, service, properties, CLOCK, () -> "worker-1");

        assertThat(worker.runNext()).isTrue();

        verify(service).recordFailure(
                outbox,
                "worker-1",
                10,
                NOW,
                NOW.plusSeconds(240),
                "IllegalStateException: Redis unavailable"
        );
    }

    @Test
    void retryDelayCapsAtConfiguredMaximum() {
        JdbcRiskPolicySyncOutboxRepository repository = mock(JdbcRiskPolicySyncOutboxRepository.class);
        RiskPolicySyncService service = mock(RiskPolicySyncService.class);
        AgentProperties properties = new AgentProperties();
        RiskPolicySyncWorker worker = new RiskPolicySyncWorker(
                repository, service, properties, CLOCK, () -> "worker-1");

        assertThat(worker.retryDelaySeconds(1, properties.getRisk().getPolicySync())).isEqualTo(30);
        assertThat(worker.retryDelaySeconds(2, properties.getRisk().getPolicySync())).isEqualTo(60);
        assertThat(worker.retryDelaySeconds(3, properties.getRisk().getPolicySync())).isEqualTo(120);
        assertThat(worker.retryDelaySeconds(4, properties.getRisk().getPolicySync())).isEqualTo(240);
        assertThat(worker.retryDelaySeconds(5, properties.getRisk().getPolicySync())).isEqualTo(480);
        assertThat(worker.retryDelaySeconds(6, properties.getRisk().getPolicySync())).isEqualTo(600);
        assertThat(worker.retryDelaySeconds(20, properties.getRisk().getPolicySync())).isEqualTo(600);
    }

    @Test
    void disabledWorkerDoesNotClaim() {
        JdbcRiskPolicySyncOutboxRepository repository = mock(JdbcRiskPolicySyncOutboxRepository.class);
        RiskPolicySyncService service = mock(RiskPolicySyncService.class);
        AgentProperties properties = new AgentProperties();
        properties.getRisk().getPolicySync().setEnabled(false);
        RiskPolicySyncWorker worker = new RiskPolicySyncWorker(
                repository, service, properties, CLOCK, () -> "worker-1");

        assertThat(worker.runNext()).isFalse();

        verify(repository, never()).claimNext(any(), any(), any(), anyInt());
    }

    private RiskPolicySyncOutbox processingOutbox(int attemptCount) {
        return new RiskPolicySyncOutbox(
                1L,
                "outbox-1",
                "risk:policy:short-link:rate-limit:nurl.ink:abc123",
                "policy-1",
                1L,
                RiskPolicySyncOperation.UPSERT,
                "{\"policyId\":\"policy-1\",\"policyVersion\":1}",
                "",
                RiskPolicySyncOutboxStatus.PROCESSING,
                attemptCount,
                null,
                "worker-1",
                NOW.plusMinutes(5),
                "",
                NOW.minusMinutes(1),
                NOW
        );
    }
}
