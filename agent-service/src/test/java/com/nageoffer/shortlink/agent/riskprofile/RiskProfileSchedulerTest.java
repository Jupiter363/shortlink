package com.nageoffer.shortlink.agent.riskprofile;

import com.nageoffer.shortlink.agent.ShortLinkAgentApplication;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatch;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchCoordinator;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchStatus;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcRiskProfileBatchRepository;
import com.nageoffer.shortlink.agent.riskprofile.scheduler.RiskProfileScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskProfileSchedulerTest {

    @Test
    void schedulerRecoversHistoricalFailuresBeforeRunningTheCurrentWindow() {
        RiskProfileBatchCoordinator coordinator = mock(RiskProfileBatchCoordinator.class);
        JdbcRiskProfileBatchRepository repository = mock(JdbcRiskProfileBatchRepository.class);
        AgentProperties properties = new AgentProperties();
        Instant cycleTrigger = Instant.parse("2026-07-10T02:17:00Z");
        Instant firstExecution = Instant.parse("2026-07-10T02:20:00Z");
        Instant secondExecution = Instant.parse("2026-07-10T02:25:00Z");
        Instant currentExecution = Instant.parse("2026-07-10T04:01:00Z");
        Instant currentWindowEnd = Instant.parse("2026-07-10T02:00:00Z");
        Clock clock = new SequenceClock(
                ZoneId.of("Asia/Shanghai"),
                cycleTrigger,
                firstExecution,
                secondExecution,
                currentExecution
        );
        RiskProfileBatch first = failedBatch(
                "risk-profile:1783612800",
                LocalDateTime.of(2026, 7, 10, 4, 0)
        );
        RiskProfileBatch second = failedBatch(
                "risk-profile:1783620000",
                LocalDateTime.of(2026, 7, 10, 6, 0)
        );
        when(coordinator.alignWindowEnd(cycleTrigger)).thenReturn(currentWindowEnd);
        when(repository.findRecoverableBefore(
                LocalDateTime.of(2026, 7, 10, 10, 0),
                LocalDateTime.of(2026, 7, 10, 10, 17),
                3
        )).thenReturn(List.of(first, second));
        RiskProfileScheduler scheduler = new RiskProfileScheduler(
                coordinator,
                repository,
                properties,
                clock
        );

        scheduler.runRiskProfileBatch();

        org.mockito.InOrder order = inOrder(coordinator, repository);
        order.verify(coordinator).alignWindowEnd(cycleTrigger);
        order.verify(repository).findRecoverableBefore(
                LocalDateTime.of(2026, 7, 10, 10, 0),
                LocalDateTime.of(2026, 7, 10, 10, 17),
                3
        );
        order.verify(repository).recordRecoveryAttempt(
                first.batchId(),
                LocalDateTime.of(2026, 7, 10, 10, 20)
        );
        order.verify(coordinator).retryRecoverableBatch(first, firstExecution);
        order.verify(repository).recordRecoveryAttempt(
                second.batchId(),
                LocalDateTime.of(2026, 7, 10, 10, 25)
        );
        order.verify(coordinator).retryRecoverableBatch(second, secondExecution);
        order.verify(coordinator).runAlignedWindow(currentWindowEnd, currentExecution);
    }

    @Test
    void aFailedRecoveryDoesNotBlockOtherRecoveriesOrTheCurrentWindow() {
        RiskProfileBatchCoordinator coordinator = mock(RiskProfileBatchCoordinator.class);
        JdbcRiskProfileBatchRepository repository = mock(JdbcRiskProfileBatchRepository.class);
        AgentProperties properties = new AgentProperties();
        Instant now = Instant.parse("2026-07-10T02:17:00Z");
        Instant currentWindowEnd = Instant.parse("2026-07-10T02:00:00Z");
        Clock clock = Clock.fixed(now, ZoneId.of("Asia/Shanghai"));
        RiskProfileBatch first = failedBatch(
                "risk-profile:1783612800",
                LocalDateTime.of(2026, 7, 10, 4, 0)
        );
        RiskProfileBatch second = failedBatch(
                "risk-profile:1783620000",
                LocalDateTime.of(2026, 7, 10, 6, 0)
        );
        when(coordinator.alignWindowEnd(now)).thenReturn(currentWindowEnd);
        when(repository.findRecoverableBefore(
                LocalDateTime.of(2026, 7, 10, 10, 0),
                LocalDateTime.of(2026, 7, 10, 10, 17),
                3
        )).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("temporary recovery failure"))
                .when(coordinator)
                .retryRecoverableBatch(first, now);
        RiskProfileScheduler scheduler = new RiskProfileScheduler(
                coordinator,
                repository,
                properties,
                clock
        );

        scheduler.runRiskProfileBatch();

        verify(repository).recordRecoveryAttempt(
                first.batchId(),
                LocalDateTime.of(2026, 7, 10, 10, 17)
        );
        verify(repository).recordRecoveryAttempt(
                second.batchId(),
                LocalDateTime.of(2026, 7, 10, 10, 17)
        );
        verify(coordinator).retryRecoverableBatch(second, now);
        verify(coordinator).runAlignedWindow(currentWindowEnd, now);
    }

    @Test
    void zeroRecoveryLimitSkipsHistoricalRecoveryScan() {
        RiskProfileBatchCoordinator coordinator = mock(RiskProfileBatchCoordinator.class);
        JdbcRiskProfileBatchRepository repository = mock(JdbcRiskProfileBatchRepository.class);
        AgentProperties properties = new AgentProperties();
        properties.getRisk().getProfile().setFailedRecoveryLimit(0);
        Instant now = Instant.parse("2026-07-10T02:17:00Z");
        Instant currentWindowEnd = Instant.parse("2026-07-10T02:00:00Z");
        when(coordinator.alignWindowEnd(now)).thenReturn(currentWindowEnd);
        RiskProfileScheduler scheduler = new RiskProfileScheduler(
                coordinator,
                repository,
                properties,
                Clock.fixed(now, ZoneId.of("Asia/Shanghai"))
        );

        scheduler.runRiskProfileBatch();

        verify(repository, never()).findRecoverableBefore(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        );
        verify(coordinator).runAlignedWindow(currentWindowEnd, now);
    }

    @Test
    void schedulerUsesShanghaiEvenHourCron() throws NoSuchMethodException {
        Method method = RiskProfileScheduler.class.getDeclaredMethod("runRiskProfileBatch");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled.cron())
                .isEqualTo("${short-link.agent.risk.profile.schedule-cron:0 0 0/2 * * *}");
        assertThat(scheduled.zone()).isEqualTo("Asia/Shanghai");
    }

    @Test
    void applicationEnablesScheduling() {
        assertThat(ShortLinkAgentApplication.class.isAnnotationPresent(EnableScheduling.class)).isTrue();
    }

    private RiskProfileBatch failedBatch(String batchId, LocalDateTime windowEnd) {
        return new RiskProfileBatch(
                batchId,
                windowEnd.minusHours(2),
                windowEnd,
                RiskProfileBatchStatus.FAILED,
                "",
                null,
                1,
                0,
                1,
                0,
                List.of(),
                windowEnd,
                windowEnd.plusMinutes(1)
        );
    }

    private static final class SequenceClock extends Clock {

        private final ZoneId zone;

        private final List<Instant> instants;

        private int index;

        private SequenceClock(ZoneId zone, Instant... instants) {
            this.zone = zone;
            this.instants = List.of(instants);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new SequenceClock(newZone, instants.toArray(Instant[]::new));
        }

        @Override
        public Instant instant() {
            int currentIndex = Math.min(index, instants.size() - 1);
            index++;
            return instants.get(currentIndex);
        }
    }
}
