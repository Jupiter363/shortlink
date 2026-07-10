package com.nageoffer.shortlink.agent.riskprofile;

import com.nageoffer.shortlink.agent.riskanalysis.job.RiskAnalysisJobPublicationService;
import com.nageoffer.shortlink.agent.riskanalysis.job.RiskAnalysisJobScope;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskWatchStatus;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatch;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchCoordinator;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchFailure;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchLeaseLostException;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchStatus;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcRiskProfileBatchRepository;
import com.nageoffer.shortlink.agent.riskprofile.service.RiskProfileBatchResult;
import com.nageoffer.shortlink.agent.riskprofile.service.RiskProfileBatchService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskProfileBatchCoordinatorTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static final Instant TRIGGER_TIME = Instant.parse("2026-07-10T02:17:35Z");

    private static final Instant WINDOW_END = Instant.parse("2026-07-10T02:00:00Z");

    private static final Clock CLOCK = Clock.fixed(TRIGGER_TIME, SHANGHAI);

    @Test
    void alignsAnyTriggerTimeToThePreviousEvenHourBoundary() {
        RiskProfileBatchCoordinator coordinator = coordinator(
                mock(RiskProfileBatchService.class),
                mock(JdbcRiskProfileBatchRepository.class),
                mock(RiskAnalysisJobPublicationService.class)
        );

        assertThat(coordinator.alignWindowEnd(TRIGGER_TIME)).isEqualTo(WINDOW_END);
        assertThat(coordinator.alignWindowEnd(Instant.parse("2026-07-10T01:59:59Z")))
                .isEqualTo(Instant.parse("2026-07-10T00:00:00Z"));
        assertThat(coordinator.alignWindowEnd(WINDOW_END)).isEqualTo(WINDOW_END);
    }

    @Test
    void doesNotRunProfilesOrCreateJobsWhenBatchLeaseIsNotAcquired() {
        RiskProfileBatchService batchService = mock(RiskProfileBatchService.class);
        JdbcRiskProfileBatchRepository batchRepository = mock(JdbcRiskProfileBatchRepository.class);
        RiskAnalysisJobPublicationService publicationService = mock(RiskAnalysisJobPublicationService.class);
        RiskProfileBatchCoordinator coordinator = coordinator(batchService, batchRepository, publicationService);
        String batchId = batchId(WINDOW_END);
        RiskProfileBatch existing = batch(
                batchId,
                RiskProfileBatchStatus.RUNNING,
                "owner-other",
                0,
                0,
                0,
                0,
                List.of()
        );
        when(batchRepository.tryAcquire(
                eq(batchId),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq("owner-a"),
                any(LocalDateTime.class),
                eq(Duration.ofMinutes(30))
        )).thenReturn(false);
        when(batchRepository.findByBatchId(batchId)).thenReturn(Optional.of(existing));

        RiskProfileBatch result = coordinator.runOnce(TRIGGER_TIME);

        assertThat(result).isEqualTo(existing);
        verify(batchService, never()).runOnce(any(), any());
        verify(publicationService, never()).publishIfOwned(any(), any());
    }

    @Test
    void completesBatchAndCreatesOneAnalysisJobForEachAbnormalGroup() {
        RiskProfileBatchService batchService = mock(RiskProfileBatchService.class);
        JdbcRiskProfileBatchRepository batchRepository = mock(JdbcRiskProfileBatchRepository.class);
        RiskAnalysisJobPublicationService publicationService = mock(RiskAnalysisJobPublicationService.class);
        RiskProfileBatchCoordinator coordinator = coordinator(batchService, batchRepository, publicationService);
        String batchId = batchId(WINDOW_END);
        ShortLinkRiskProfile first = profile(batchId, "gid-a", "a", 92);
        ShortLinkRiskProfile second = profile(batchId, "gid-b", "b", 68);
        RiskProfileBatchResult batchResult = new RiskProfileBatchResult(
                batchId,
                WINDOW_END,
                3,
                2,
                List.of(new RiskProfileBatchFailure(
                        "gid-c/nurl.ink/c",
                        "PROFILE_GENERATION_FAILED",
                        "stats request failed"
                )),
                Map.of(
                        "gid-a", List.of(first),
                        "gid-b", List.of(second)
                )
        );
        when(batchRepository.tryAcquire(
                eq(batchId),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq("owner-a"),
                any(LocalDateTime.class),
                eq(Duration.ofMinutes(30))
        )).thenReturn(true);
        when(batchService.runOnce(WINDOW_END, "owner-a")).thenReturn(batchResult);
        when(publicationService.publishIfOwned(any(RiskProfileBatch.class), any()))
                .thenAnswer(invocation -> terminalView(invocation.getArgument(0)));

        RiskProfileBatch result = coordinator.runOnce(TRIGGER_TIME);

        ArgumentCaptor<RiskProfileBatch> completedCaptor = ArgumentCaptor.forClass(RiskProfileBatch.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<RiskAnalysisJobScope>> scopesCaptor = ArgumentCaptor.forClass(Set.class);
        verify(publicationService).publishIfOwned(completedCaptor.capture(), scopesCaptor.capture());
        assertThat(completedCaptor.getValue()).satisfies(completed -> {
            assertThat(completed.status()).isEqualTo(RiskProfileBatchStatus.PARTIAL_SUCCESS);
            assertThat(completed.ownerToken()).isEqualTo("owner-a");
            assertThat(completed.scannedCount()).isEqualTo(3);
            assertThat(completed.generatedCount()).isEqualTo(2);
            assertThat(completed.failedCount()).isEqualTo(1);
            assertThat(completed.analysisJobCount()).isEqualTo(2);
        });
        assertThat(scopesCaptor.getValue()).containsExactlyInAnyOrder(
                new RiskAnalysisJobScope("gid-a", "security-risk-graph", "v1"),
                new RiskAnalysisJobScope("gid-b", "security-risk-graph", "v1")
        );
        assertThat(result.status()).isEqualTo(RiskProfileBatchStatus.PARTIAL_SUCCESS);
        assertThat(result.ownerToken()).isEmpty();
        assertThat(result.leaseUntil()).isNull();
        assertThat(result.analysisJobCount()).isEqualTo(2);
    }

    @Test
    void preservesCompletedProfileStatisticsWhenAnalysisJobCreationFailsPartway() {
        RiskProfileBatchService batchService = mock(RiskProfileBatchService.class);
        JdbcRiskProfileBatchRepository batchRepository = mock(JdbcRiskProfileBatchRepository.class);
        RiskAnalysisJobPublicationService publicationService = mock(RiskAnalysisJobPublicationService.class);
        RiskProfileBatchCoordinator coordinator = coordinator(batchService, batchRepository, publicationService);
        String batchId = batchId(WINDOW_END);
        RiskProfileBatchFailure profileFailure = new RiskProfileBatchFailure(
                "gid-c/nurl.ink/c",
                "PROFILE_GENERATION_FAILED",
                "stats request failed"
        );
        RiskProfileBatchResult batchResult = new RiskProfileBatchResult(
                batchId,
                WINDOW_END,
                3,
                2,
                List.of(profileFailure),
                Map.of(
                        "gid-a", List.of(profile(batchId, "gid-a", "a", 92)),
                        "gid-b", List.of(profile(batchId, "gid-b", "b", 68))
                )
        );
        when(batchRepository.tryAcquire(
                eq(batchId),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq("owner-a"),
                any(LocalDateTime.class),
                eq(Duration.ofMinutes(30))
        )).thenReturn(true);
        when(batchService.runOnce(WINDOW_END, "owner-a")).thenReturn(batchResult);
        when(publicationService.publishIfOwned(any(RiskProfileBatch.class), any()))
                .thenThrow(new IllegalStateException("job database unavailable"))
                .thenAnswer(invocation -> terminalView(invocation.getArgument(0)));

        RiskProfileBatch result = coordinator.runOnce(TRIGGER_TIME);

        ArgumentCaptor<RiskProfileBatch> completedCaptor = ArgumentCaptor.forClass(RiskProfileBatch.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<RiskAnalysisJobScope>> scopesCaptor = ArgumentCaptor.forClass(Set.class);
        verify(publicationService, org.mockito.Mockito.times(2))
                .publishIfOwned(completedCaptor.capture(), scopesCaptor.capture());
        assertThat(completedCaptor.getAllValues().get(1)).satisfies(failed -> {
            assertThat(failed.status()).isEqualTo(RiskProfileBatchStatus.FAILED);
            assertThat(failed.scannedCount()).isEqualTo(3);
            assertThat(failed.generatedCount()).isEqualTo(2);
            assertThat(failed.failedCount()).isEqualTo(2);
            assertThat(failed.analysisJobCount()).isZero();
            assertThat(failed.failures().get(0).errorCode())
                    .isEqualTo("BATCH_COORDINATION_FAILED");
            assertThat(failed.failures()).contains(profileFailure);
            assertThat(failed.failures())
                    .anySatisfy(failure -> {
                        assertThat(failure.errorCode()).isEqualTo("BATCH_COORDINATION_FAILED");
                        assertThat(failure.message()).contains("job database unavailable");
                    });
        });
        assertThat(scopesCaptor.getAllValues().get(1)).isEmpty();
        assertThat(result.status()).isEqualTo(RiskProfileBatchStatus.FAILED);
        assertThat(result.scannedCount()).isEqualTo(3);
        assertThat(result.generatedCount()).isEqualTo(2);
        assertThat(result.failedCount()).isEqualTo(2);
        assertThat(result.analysisJobCount()).isZero();
    }

    @Test
    void leaseLossDuringJobPublicationDoesNotCompleteTheBatchAsTheStaleOwner() {
        RiskProfileBatchService batchService = mock(RiskProfileBatchService.class);
        JdbcRiskProfileBatchRepository batchRepository = mock(JdbcRiskProfileBatchRepository.class);
        RiskAnalysisJobPublicationService publicationService = mock(RiskAnalysisJobPublicationService.class);
        RiskProfileBatchCoordinator coordinator = coordinator(batchService, batchRepository, publicationService);
        String batchId = batchId(WINDOW_END);
        RiskProfileBatchResult batchResult = new RiskProfileBatchResult(
                batchId,
                WINDOW_END,
                1,
                1,
                List.of(),
                Map.of("gid-a", List.of(profile(batchId, "gid-a", "a", 92)))
        );
        when(batchRepository.tryAcquire(
                eq(batchId),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq("owner-a"),
                any(LocalDateTime.class),
                eq(Duration.ofMinutes(30))
        )).thenReturn(true);
        when(batchService.runOnce(WINDOW_END, "owner-a")).thenReturn(batchResult);
        when(publicationService.publishIfOwned(any(RiskProfileBatch.class), any()))
                .thenThrow(new RiskProfileBatchLeaseLostException(batchId));

        assertThatThrownBy(() -> coordinator.runOnce(TRIGGER_TIME))
                .isInstanceOf(RiskProfileBatchLeaseLostException.class)
                .hasMessageContaining(batchId);
        verify(publicationService).publishIfOwned(any(RiskProfileBatch.class), any());
    }

    @Test
    void retriesAHistoricalFailedBatchWithItsOriginalWindowAndACurrentLease() {
        RiskProfileBatchService batchService = mock(RiskProfileBatchService.class);
        JdbcRiskProfileBatchRepository batchRepository = mock(JdbcRiskProfileBatchRepository.class);
        RiskAnalysisJobPublicationService publicationService = mock(RiskAnalysisJobPublicationService.class);
        RiskProfileBatchCoordinator coordinator = coordinator(batchService, batchRepository, publicationService);
        Instant historicalWindowEnd = Instant.parse("2026-07-09T22:00:00Z");
        String historicalBatchId = batchId(historicalWindowEnd);
        RiskProfileBatch failedBatch = new RiskProfileBatch(
                historicalBatchId,
                LocalDateTime.ofInstant(historicalWindowEnd.minus(Duration.ofHours(2)), SHANGHAI),
                LocalDateTime.ofInstant(historicalWindowEnd, SHANGHAI),
                RiskProfileBatchStatus.FAILED,
                "",
                null,
                1,
                0,
                1,
                0,
                List.of(new RiskProfileBatchFailure(
                        historicalBatchId,
                        "BATCH_COORDINATION_FAILED",
                        "temporary failure"
                )),
                LocalDateTime.ofInstant(historicalWindowEnd, SHANGHAI),
                LocalDateTime.ofInstant(historicalWindowEnd.plusSeconds(30), SHANGHAI)
        );
        when(batchRepository.tryAcquire(
                eq(historicalBatchId),
                eq(failedBatch.windowStart()),
                eq(failedBatch.windowEnd()),
                eq("owner-a"),
                eq(LocalDateTime.ofInstant(TRIGGER_TIME, SHANGHAI)),
                eq(Duration.ofMinutes(30))
        )).thenReturn(true);
        RiskProfileBatchResult batchResult = new RiskProfileBatchResult(
                historicalBatchId,
                historicalWindowEnd,
                0,
                0,
                List.of(),
                Map.of()
        );
        when(batchService.runOnce(historicalWindowEnd, "owner-a")).thenReturn(batchResult);
        when(publicationService.publishIfOwned(any(RiskProfileBatch.class), eq(Set.of())))
                .thenAnswer(invocation -> terminalView(invocation.getArgument(0)));

        RiskProfileBatch recovered = coordinator.retryRecoverableBatch(failedBatch, TRIGGER_TIME);

        assertThat(recovered.status()).isEqualTo(RiskProfileBatchStatus.SUCCEEDED);
        assertThat(recovered.windowEnd()).isEqualTo(failedBatch.windowEnd());
        verify(batchService).runOnce(historicalWindowEnd, "owner-a");
    }

    @Test
    void retriesAnExpiredRunningBatchWithItsOriginalWindow() {
        RiskProfileBatchService batchService = mock(RiskProfileBatchService.class);
        JdbcRiskProfileBatchRepository batchRepository = mock(JdbcRiskProfileBatchRepository.class);
        RiskAnalysisJobPublicationService publicationService = mock(RiskAnalysisJobPublicationService.class);
        RiskProfileBatchCoordinator coordinator = coordinator(batchService, batchRepository, publicationService);
        Instant historicalWindowEnd = Instant.parse("2026-07-09T22:00:00Z");
        String historicalBatchId = batchId(historicalWindowEnd);
        RiskProfileBatch expiredRunning = new RiskProfileBatch(
                historicalBatchId,
                LocalDateTime.ofInstant(historicalWindowEnd.minus(Duration.ofHours(2)), SHANGHAI),
                LocalDateTime.ofInstant(historicalWindowEnd, SHANGHAI),
                RiskProfileBatchStatus.RUNNING,
                "stale-owner",
                LocalDateTime.ofInstant(TRIGGER_TIME.minusSeconds(1), SHANGHAI),
                1,
                0,
                0,
                0,
                List.of(),
                LocalDateTime.ofInstant(historicalWindowEnd, SHANGHAI),
                null
        );
        when(batchRepository.tryAcquire(
                eq(historicalBatchId),
                eq(expiredRunning.windowStart()),
                eq(expiredRunning.windowEnd()),
                eq("owner-a"),
                eq(LocalDateTime.ofInstant(TRIGGER_TIME, SHANGHAI)),
                eq(Duration.ofMinutes(30))
        )).thenReturn(true);
        RiskProfileBatchResult batchResult = new RiskProfileBatchResult(
                historicalBatchId,
                historicalWindowEnd,
                0,
                0,
                List.of(),
                Map.of()
        );
        when(batchService.runOnce(historicalWindowEnd, "owner-a")).thenReturn(batchResult);
        when(publicationService.publishIfOwned(any(RiskProfileBatch.class), eq(Set.of())))
                .thenAnswer(invocation -> terminalView(invocation.getArgument(0)));

        RiskProfileBatch recovered = coordinator.retryRecoverableBatch(expiredRunning, TRIGGER_TIME);

        assertThat(recovered.status()).isEqualTo(RiskProfileBatchStatus.SUCCEEDED);
        verify(batchService).runOnce(historicalWindowEnd, "owner-a");
    }

    @Test
    void rejectsHistoricalBatchWhoseWindowIsNotExactlyTwoHours() {
        RiskProfileBatchCoordinator coordinator = coordinator(
                mock(RiskProfileBatchService.class),
                mock(JdbcRiskProfileBatchRepository.class),
                mock(RiskAnalysisJobPublicationService.class)
        );
        Instant historicalWindowEnd = Instant.parse("2026-07-09T22:00:00Z");
        RiskProfileBatch malformed = new RiskProfileBatch(
                batchId(historicalWindowEnd),
                LocalDateTime.ofInstant(historicalWindowEnd.minus(Duration.ofHours(3)), SHANGHAI),
                LocalDateTime.ofInstant(historicalWindowEnd, SHANGHAI),
                RiskProfileBatchStatus.FAILED,
                "",
                null,
                0,
                0,
                1,
                0,
                List.of(),
                LocalDateTime.ofInstant(historicalWindowEnd, SHANGHAI),
                LocalDateTime.ofInstant(historicalWindowEnd.plusSeconds(1), SHANGHAI)
        );

        assertThatThrownBy(() -> coordinator.retryRecoverableBatch(malformed, TRIGGER_TIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly two hours");
    }

    private RiskProfileBatchCoordinator coordinator(
            RiskProfileBatchService batchService,
            JdbcRiskProfileBatchRepository batchRepository,
            RiskAnalysisJobPublicationService publicationService
    ) {
        return new RiskProfileBatchCoordinator(
                batchService,
                batchRepository,
                publicationService,
                CLOCK,
                Duration.ofMinutes(30),
                () -> "owner-a"
        );
    }

    private RiskProfileBatch terminalView(RiskProfileBatch batch) {
        return new RiskProfileBatch(
                batch.batchId(),
                batch.windowStart(),
                batch.windowEnd(),
                batch.status(),
                "",
                null,
                batch.scannedCount(),
                batch.generatedCount(),
                batch.failedCount(),
                batch.analysisJobCount(),
                batch.failures(),
                batch.startTime(),
                batch.finishTime()
        );
    }

    private RiskProfileBatch batch(
            String batchId,
            RiskProfileBatchStatus status,
            String ownerToken,
            int scannedCount,
            int generatedCount,
            int failedCount,
            int analysisJobCount,
            List<RiskProfileBatchFailure> failures
    ) {
        return new RiskProfileBatch(
                batchId,
                LocalDateTime.ofInstant(WINDOW_END.minus(Duration.ofHours(2)), SHANGHAI),
                LocalDateTime.ofInstant(WINDOW_END, SHANGHAI),
                status,
                ownerToken,
                TRIGGER_TIME.atZone(SHANGHAI).toLocalDateTime().plusMinutes(30),
                scannedCount,
                generatedCount,
                failedCount,
                analysisJobCount,
                failures,
                TRIGGER_TIME.atZone(SHANGHAI).toLocalDateTime(),
                null
        );
    }

    private ShortLinkRiskProfile profile(String batchId, String gid, String shortUri, int riskScore) {
        return new ShortLinkRiskProfile(
                gid,
                "nurl.ink",
                shortUri,
                "nurl.ink/" + shortUri,
                LocalDateTime.ofInstant(WINDOW_END.minus(Duration.ofHours(2)), SHANGHAI),
                LocalDateTime.ofInstant(WINDOW_END, SHANGHAI),
                new ShortLinkRiskMetrics(
                        600,
                        50,
                        900,
                        300,
                        2100,
                        1200,
                        8.0,
                        0.82,
                        0.78,
                        0.50,
                        0.65,
                        0.60,
                        12.0,
                        0.74,
                        0.88
                ),
                riskScore,
                riskScore,
                RiskLevel.fromScore(riskScore),
                Set.of(RiskReasonCode.TRAFFIC_SPIKE),
                RiskWatchStatus.NONE,
                List.of(),
                "",
                batchId
        );
    }

    private String batchId(Instant windowEnd) {
        return "risk-profile:" + windowEnd.getEpochSecond();
    }
}
