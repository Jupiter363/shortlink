package com.nageoffer.shortlink.agent.riskpolicy;

import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncScheduler;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncWorker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RiskPolicySyncSchedulerTest {

    @Test
    void enabledSchedulerRunsOneWorkerIteration() {
        RiskPolicySyncWorker worker = mock(RiskPolicySyncWorker.class);
        AgentProperties properties = new AgentProperties();
        RiskPolicySyncScheduler scheduler = new RiskPolicySyncScheduler(worker, properties);

        scheduler.run();

        verify(worker).runNext();
    }

    @Test
    void disabledSchedulerDoesNotRunWorker() {
        RiskPolicySyncWorker worker = mock(RiskPolicySyncWorker.class);
        AgentProperties properties = new AgentProperties();
        properties.getRisk().getPolicySync().setEnabled(false);
        RiskPolicySyncScheduler scheduler = new RiskPolicySyncScheduler(worker, properties);

        scheduler.run();

        verify(worker, never()).runNext();
    }

    @Test
    void schedulerContainsUnexpectedWorkerFailure() {
        RiskPolicySyncWorker worker = mock(RiskPolicySyncWorker.class);
        doThrow(new IllegalStateException("database unavailable")).when(worker).runNext();
        RiskPolicySyncScheduler scheduler = new RiskPolicySyncScheduler(
                worker, new AgentProperties());

        assertThatCode(scheduler::run).doesNotThrowAnyException();
    }
}
