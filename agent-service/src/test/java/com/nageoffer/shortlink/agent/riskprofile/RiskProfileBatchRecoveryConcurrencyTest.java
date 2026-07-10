package com.nageoffer.shortlink.agent.riskprofile;

import com.nageoffer.shortlink.agent.riskanalysis.job.RiskAnalysisJobPublicationService;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatch;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchFailure;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchCoordinator;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchStatus;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcRiskProfileBatchRepository;
import com.nageoffer.shortlink.agent.riskprofile.service.RiskProfileBatchResult;
import com.nageoffer.shortlink.agent.riskprofile.service.RiskProfileBatchService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskProfileBatchRecoveryConcurrencyTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void twoCoordinatorsCompetingForTheSameRecoverableBatchOnlyRunProfilesOnce() throws Exception {
        CountDownLatch acquireAttempts = new CountDownLatch(2);
        CoordinatedAcquireRepository repository = repository(acquireAttempts);
        Instant recoveryTime = Instant.parse("2026-07-10T02:17:00Z");
        Instant historicalWindowEnd = Instant.parse("2026-07-09T22:00:00Z");
        String batchId = "risk-profile:" + historicalWindowEnd.getEpochSecond();
        LocalDateTime windowStart = LocalDateTime.ofInstant(
                historicalWindowEnd.minus(Duration.ofHours(2)),
                SHANGHAI
        );
        LocalDateTime windowEnd = LocalDateTime.ofInstant(historicalWindowEnd, SHANGHAI);
        LocalDateTime initialStart = LocalDateTime.ofInstant(
                recoveryTime.minus(Duration.ofMinutes(20)),
                SHANGHAI
        );
        assertThat(repository.tryAcquire(
                batchId,
                windowStart,
                windowEnd,
                "initial-owner",
                initialStart,
                Duration.ofMinutes(5)
        )).isTrue();
        RiskProfileBatch failed = new RiskProfileBatch(
                batchId,
                windowStart,
                windowEnd,
                RiskProfileBatchStatus.FAILED,
                "initial-owner",
                initialStart.plusMinutes(5),
                1,
                0,
                1,
                0,
                List.of(new RiskProfileBatchFailure(
                        batchId,
                        "BATCH_COORDINATION_FAILED",
                        "temporary failure"
                )),
                initialStart,
                initialStart.plusMinutes(1)
        );
        assertThat(repository.complete(failed)).isTrue();
        repository.enableCoordination();

        RiskProfileBatchService batchService = mock(RiskProfileBatchService.class);
        when(batchService.runOnce(eq(historicalWindowEnd), anyString()))
                .thenReturn(new RiskProfileBatchResult(
                        batchId,
                        historicalWindowEnd,
                        0,
                        0,
                        List.of(),
                        Map.of()
                ));
        RiskAnalysisJobPublicationService publicationService = mock(RiskAnalysisJobPublicationService.class);
        when(publicationService.publishIfOwned(any(RiskProfileBatch.class), eq(java.util.Set.of())))
                .thenAnswer(invocation -> {
                    RiskProfileBatch completed = invocation.getArgument(0);
                    assertThat(repository.complete(completed)).isTrue();
                    return repository.findByBatchId(completed.batchId()).orElseThrow();
                });
        Clock clock = Clock.fixed(recoveryTime, SHANGHAI);
        RiskProfileBatchCoordinator firstCoordinator = new RiskProfileBatchCoordinator(
                batchService,
                repository,
                publicationService,
                clock,
                Duration.ofMinutes(30),
                () -> "owner-a"
        );
        RiskProfileBatchCoordinator secondCoordinator = new RiskProfileBatchCoordinator(
                batchService,
                repository,
                publicationService,
                clock,
                Duration.ofMinutes(30),
                () -> "owner-b"
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<RiskProfileBatch> firstFuture = executor.submit(() -> {
            ready.countDown();
            await(start);
            return firstCoordinator.retryRecoverableBatch(failed, recoveryTime);
        });
        Future<RiskProfileBatch> secondFuture = executor.submit(() -> {
            ready.countDown();
            await(start);
            return secondCoordinator.retryRecoverableBatch(failed, recoveryTime);
        });
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        try {
            RiskProfileBatch firstResult = firstFuture.get(5, TimeUnit.SECONDS);
            RiskProfileBatch secondResult = secondFuture.get(5, TimeUnit.SECONDS);

            assertThat(firstResult.batchId()).isEqualTo(batchId);
            assertThat(secondResult.batchId()).isEqualTo(batchId);
            assertThat(repository.findByBatchId(batchId))
                    .isPresent()
                    .get()
                    .extracting(RiskProfileBatch::status)
                    .isEqualTo(RiskProfileBatchStatus.SUCCEEDED);
            verify(batchService, times(1)).runOnce(eq(historicalWindowEnd), anyString());
            verify(publicationService, times(1))
                    .publishIfOwned(any(RiskProfileBatch.class), eq(java.util.Set.of()));
        } finally {
            executor.shutdownNow();
        }
    }

    private CoordinatedAcquireRepository repository(CountDownLatch acquireAttempts) {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:risk_profile_batch_recovery_concurrency;"
                        + "MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql")).execute(dataSource);
        return new CoordinatedAcquireRepository(new JdbcTemplate(dataSource), acquireAttempts);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent recovery");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent recovery was interrupted", ex);
        }
    }

    private static final class CoordinatedAcquireRepository extends JdbcRiskProfileBatchRepository {

        private final CountDownLatch acquireAttempts;

        private volatile boolean coordinationEnabled;

        private CoordinatedAcquireRepository(
                JdbcTemplate jdbcTemplate,
                CountDownLatch acquireAttempts
        ) {
            super(jdbcTemplate);
            this.acquireAttempts = acquireAttempts;
        }

        private void enableCoordination() {
            coordinationEnabled = true;
        }

        @Override
        public boolean tryAcquire(
                String batchId,
                LocalDateTime windowStart,
                LocalDateTime windowEnd,
                String ownerToken,
                LocalDateTime now,
                Duration leaseDuration
        ) {
            boolean acquired = super.tryAcquire(
                    batchId,
                    windowStart,
                    windowEnd,
                    ownerToken,
                    now,
                    leaseDuration
            );
            if (!coordinationEnabled) {
                return acquired;
            }
            acquireAttempts.countDown();
            if (acquired) {
                await(acquireAttempts);
            }
            return acquired;
        }
    }
}
