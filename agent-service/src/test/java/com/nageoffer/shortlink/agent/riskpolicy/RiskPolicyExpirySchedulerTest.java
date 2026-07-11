package com.nageoffer.shortlink.agent.riskpolicy;

import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicyExpiryScheduler;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicyExpiryService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskPolicyExpirySchedulerTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-12T04:00:00Z"),
            ZoneId.of("Asia/Shanghai")
    );
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 12, 12, 0);

    @Test
    void schedulerProcessesOneBoundedBatch() {
        RiskPolicyExpiryService service = mock(RiskPolicyExpiryService.class);
        AgentProperties properties = new AgentProperties();
        RiskPolicyExpiryScheduler scheduler = new RiskPolicyExpiryScheduler(
                service, properties, CLOCK);

        scheduler.run();

        verify(service).expireBatch(NOW, 100);
    }

    @Test
    void disabledSchedulerDoesNotQueryExpiryService() {
        RiskPolicyExpiryService service = mock(RiskPolicyExpiryService.class);
        AgentProperties properties = new AgentProperties();
        properties.getRisk().getPolicySync().setEnabled(false);
        RiskPolicyExpiryScheduler scheduler = new RiskPolicyExpiryScheduler(
                service, properties, CLOCK);

        scheduler.run();

        verify(service, never()).expireBatch(NOW, 100);
    }

    @Test
    void schedulerContainsOnePolicyFailure() {
        RiskPolicyExpiryService service = mock(RiskPolicyExpiryService.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(service).expireBatch(NOW, 100);
        RiskPolicyExpiryScheduler scheduler = new RiskPolicyExpiryScheduler(
                service, new AgentProperties(), CLOCK);

        assertThatCode(scheduler::run).doesNotThrowAnyException();
    }
}
