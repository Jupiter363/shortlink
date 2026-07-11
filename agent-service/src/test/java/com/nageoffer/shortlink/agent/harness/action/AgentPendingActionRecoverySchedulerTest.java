package com.nageoffer.shortlink.agent.harness.action;

import com.nageoffer.shortlink.agent.harness.action.repository.JdbcAgentPendingActionRepository;
import com.nageoffer.shortlink.agent.harness.action.scheduler.AgentPendingActionRecoveryScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(OutputCaptureExtension.class)
class AgentPendingActionRecoverySchedulerTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Instant INSTANT = Instant.parse("2026-07-11T01:30:00Z");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 11, 9, 30);

    @Test
    void maintenanceUsesShanghaiTimeForBothRepositorySteps() {
        JdbcAgentPendingActionRepository repository = mock(JdbcAgentPendingActionRepository.class);
        AgentPendingActionRecoveryScheduler scheduler = new AgentPendingActionRecoveryScheduler(
                repository,
                Clock.fixed(INSTANT, SHANGHAI)
        );

        scheduler.maintain();

        verify(repository).expireDue(NOW);
        verify(repository).recoverExpiredExecutions(NOW);
    }

    @Test
    void expirationFailureIsSanitizedAndDoesNotBlockLeaseRecovery(CapturedOutput output) {
        JdbcAgentPendingActionRepository repository = mock(JdbcAgentPendingActionRepository.class);
        doThrow(new IllegalStateException(
                "action-identifier token-secret select * from t_agent_pending_action"
        )).when(repository).expireDue(NOW);
        AgentPendingActionRecoveryScheduler scheduler = new AgentPendingActionRecoveryScheduler(
                repository,
                Clock.fixed(INSTANT, SHANGHAI)
        );

        assertThatCode(scheduler::maintain).doesNotThrowAnyException();

        verify(repository).recoverExpiredExecutions(NOW);
        assertThat(output.getAll())
                .contains(
                        "Pending action expiration maintenance failed",
                        "IllegalStateException"
                )
                .doesNotContain(
                        "action-identifier",
                        "token-secret",
                        "select *",
                        "t_agent_pending_action"
                );
    }

    @Test
    void leaseRecoveryFailureIsSanitizedAndDoesNotEscape(CapturedOutput output) {
        JdbcAgentPendingActionRepository repository = mock(JdbcAgentPendingActionRepository.class);
        doThrow(new IllegalStateException(
                "action-identifier token-secret update t_agent_pending_action"
        )).when(repository).recoverExpiredExecutions(NOW);
        AgentPendingActionRecoveryScheduler scheduler = new AgentPendingActionRecoveryScheduler(
                repository,
                Clock.fixed(INSTANT, SHANGHAI)
        );

        assertThatCode(scheduler::maintain).doesNotThrowAnyException();

        verify(repository).expireDue(NOW);
        assertThat(output.getAll())
                .contains(
                        "Pending action execution lease maintenance failed",
                        "IllegalStateException"
                )
                .doesNotContain(
                        "action-identifier",
                        "token-secret",
                        "update t_agent_pending_action"
                );
    }

    @Test
    void schedulerIsAComponentWithTheConfiguredFixedDelay() throws NoSuchMethodException {
        Method maintain = AgentPendingActionRecoveryScheduler.class.getDeclaredMethod("maintain");
        Scheduled scheduled = maintain.getAnnotation(Scheduled.class);

        assertThat(AgentPendingActionRecoveryScheduler.class.isAnnotationPresent(Component.class))
                .isTrue();
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${short-link.agent.action.recovery-interval-millis:60000}");
    }
}
