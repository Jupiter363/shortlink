package com.nageoffer.shortlink.agent.riskanalysis;

import com.nageoffer.shortlink.agent.riskanalysis.job.JdbcRiskAnalysisJobRepository;
import com.nageoffer.shortlink.agent.riskanalysis.job.RiskAnalysisJob;
import com.nageoffer.shortlink.agent.riskanalysis.job.RiskAnalysisJobPublicationService;
import com.nageoffer.shortlink.agent.riskanalysis.job.RiskAnalysisJobScope;
import com.nageoffer.shortlink.agent.riskanalysis.job.RiskAnalysisJobService;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatch;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchLeaseLostException;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchStatus;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcRiskProfileBatchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

class RiskAnalysisJobPublicationServiceTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static final Instant NOW_INSTANT = Instant.parse("2026-07-10T02:00:00Z");

    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, SHANGHAI);

    @Test
    void reusesExistingJobsAndPublishesTheExactDesiredScopeSet() {
        TestContext context = context("risk_analysis_job_publication_add_scope");
        context.acquireBatch();
        context.jobService().createIfAbsentForScope(
                "risk-profile:batch-001",
                new RiskAnalysisJobScope("gid-a", "security-risk-graph", "v1"),
                "owner-a"
        );

        RiskProfileBatch published = context.publicationService().publishIfOwned(
                completion(RiskProfileBatchStatus.SUCCEEDED),
                Set.of(
                        new RiskAnalysisJobScope("gid-a", "security-risk-graph", "v1"),
                        new RiskAnalysisJobScope("gid-b", "security-risk-graph", "v1")
                )
        );

        assertThat(published.status()).isEqualTo(RiskProfileBatchStatus.SUCCEEDED);
        assertThat(published.analysisJobCount()).isEqualTo(2);
        assertThat(published.ownerToken()).isEmpty();
        assertThat(context.jdbcTemplate().queryForList(
                """
                select gid, graph_name, graph_version
                from t_agent_risk_analysis_job
                where batch_id = ?
                order by gid
                """,
                "risk-profile:batch-001"
        )).containsExactly(
                java.util.Map.of(
                        "gid", "gid-a",
                        "graph_name", "security-risk-graph",
                        "graph_version", "v1"
                ),
                java.util.Map.of(
                        "gid", "gid-b",
                        "graph_name", "security-risk-graph",
                        "graph_version", "v1"
                )
        );
    }

    @Test
    void removesStaleUnstartedJobsBeforePublishingTheDesiredScopeSet() {
        TestContext context = context("risk_analysis_job_publication_replace_scope");
        context.acquireBatch();
        context.jobService().createIfAbsentForScope(
                "risk-profile:batch-001",
                new RiskAnalysisJobScope("gid-a", "security-risk-graph", "v1"),
                "owner-a"
        );
        context.jobService().createIfAbsentForScope(
                "risk-profile:batch-001",
                new RiskAnalysisJobScope("gid-b", "security-risk-graph", "v1"),
                "owner-a"
        );

        context.publicationService().publishIfOwned(
                completion(RiskProfileBatchStatus.SUCCEEDED),
                Set.of(
                        new RiskAnalysisJobScope("gid-b", "security-risk-graph", "v1"),
                        new RiskAnalysisJobScope("gid-c", "security-risk-graph", "v1")
                )
        );

        assertThat(context.jdbcTemplate().queryForList(
                """
                select gid
                from t_agent_risk_analysis_job
                where batch_id = ?
                order by gid
                """,
                String.class,
                "risk-profile:batch-001"
        )).containsExactly("gid-b", "gid-c");
    }

    @Test
    void replacesPendingJobsFromAnOlderGraphVersion() {
        TestContext context = context("risk_analysis_job_publication_graph_version");
        context.acquireBatch();
        context.jobService().createIfAbsentForScope(
                "risk-profile:batch-001",
                new RiskAnalysisJobScope("gid-a", "security-risk-graph", "v1"),
                "owner-a"
        );

        context.publicationService().publishIfOwned(
                completion(RiskProfileBatchStatus.SUCCEEDED),
                Set.of(new RiskAnalysisJobScope("gid-a", "security-risk-graph", "v2"))
        );

        assertThat(context.jdbcTemplate().queryForList(
                """
                select graph_version
                from t_agent_risk_analysis_job
                where batch_id = ?
                """,
                String.class,
                "risk-profile:batch-001"
        )).containsExactly("v2");
    }

    @Test
    void refusesToPublishWhenAStaleJobHasAlreadyStarted() {
        TestContext context = context("risk_analysis_job_publication_started_stale");
        context.acquireBatch();
        RiskAnalysisJob started = context.jobService().createIfAbsentForScope(
                "risk-profile:batch-001",
                new RiskAnalysisJobScope("gid-a", "security-risk-graph", "v1"),
                "owner-a"
        );
        context.jdbcTemplate().update("""
                        update t_agent_risk_analysis_job
                        set status = ?,
                            attempt_count = 1
                        where job_id = ?
                        """,
                "RUNNING",
                started.jobId()
        );

        assertThatThrownBy(() -> context.publicationService().publishIfOwned(
                completion(RiskProfileBatchStatus.SUCCEEDED),
                Set.of(new RiskAnalysisJobScope("gid-b", "security-risk-graph", "v1"))
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(started.jobId());
        assertThat(context.batchRepository().findByBatchId("risk-profile:batch-001"))
                .isPresent()
                .get()
                .satisfies(batch -> {
                    assertThat(batch.status()).isEqualTo(RiskProfileBatchStatus.RUNNING);
                    assertThat(batch.ownerToken()).isEqualTo("owner-a");
                });
        assertThat(context.jdbcTemplate().queryForObject(
                "select count(*) from t_agent_risk_analysis_job",
                Long.class
        )).isEqualTo(1L);
    }

    @Test
    void rollsBackAllJobsAndBatchCompletionWhenAnyJobCreationFails() {
        TestContext context = context("risk_analysis_job_publication_rollback");
        context.acquireBatch();
        RiskAnalysisJobService failingJobService = spy(context.jobService());
        AtomicInteger invocationCount = new AtomicInteger();
        doAnswer(invocation -> {
            if (invocationCount.incrementAndGet() == 2) {
                throw new IllegalStateException("second job creation failed");
            }
            return invocation.callRealMethod();
        }).when(failingJobService).createIfAbsentForScope(anyString(), any(RiskAnalysisJobScope.class), anyString());
        RiskAnalysisJobPublicationService publicationService = new RiskAnalysisJobPublicationService(
                context.batchRepository(),
                context.jobRepository(),
                failingJobService,
                new DataSourceTransactionManager(context.dataSource()),
                context.clock()
        );

        assertThatThrownBy(() -> publicationService.publishIfOwned(
                completion(RiskProfileBatchStatus.SUCCEEDED),
                Set.of(
                        new RiskAnalysisJobScope("gid-a", "security-risk-graph", "v1"),
                        new RiskAnalysisJobScope("gid-b", "security-risk-graph", "v1")
                )
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("second job creation failed");
        assertThat(context.jdbcTemplate().queryForObject(
                "select count(*) from t_agent_risk_analysis_job",
                Long.class
        )).isZero();
        assertThat(context.batchRepository().findByBatchId("risk-profile:batch-001"))
                .isPresent()
                .get()
                .satisfies(batch -> {
                    assertThat(batch.status()).isEqualTo(RiskProfileBatchStatus.RUNNING);
                    assertThat(batch.analysisJobCount()).isZero();
                    assertThat(batch.ownerToken()).isEqualTo("owner-a");
                });
    }

    @Test
    void rejectsPublicationAfterTheBatchOwnerLeaseExpires() {
        TestContext context = context("risk_analysis_job_publication_expired_owner");
        context.acquireBatch();
        context.jdbcTemplate().update("""
                        update t_agent_risk_profile_batch
                        set lease_until = ?
                        where batch_id = ?
                        """,
                NOW.minusSeconds(1),
                "risk-profile:batch-001"
        );

        assertThatThrownBy(() -> context.publicationService().publishIfOwned(
                completion(RiskProfileBatchStatus.SUCCEEDED),
                Set.of(new RiskAnalysisJobScope("gid-a", "security-risk-graph", "v1"))
        ))
                .isInstanceOf(RiskProfileBatchLeaseLostException.class)
                .hasMessageContaining("risk-profile:batch-001");
        assertThat(context.jdbcTemplate().queryForObject(
                "select count(*) from t_agent_risk_analysis_job",
                Long.class
        )).isZero();
    }

    @Test
    void rechecksTheLeaseAfterWaitingForTheBatchRowLock() {
        TestContext context = context("risk_analysis_job_publication_lock_wait");
        context.acquireBatch();
        MutableClock clock = new MutableClock(NOW_INSTANT, SHANGHAI);
        JdbcRiskProfileBatchRepository lockingRepository = spy(context.batchRepository());
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            clock.advance(Duration.ofMinutes(11));
            return result;
        }).when(lockingRepository).lockOwnedRunningBatch(
                anyString(),
                anyString(),
                any(LocalDateTime.class)
        );
        RiskAnalysisJobPublicationService publicationService = new RiskAnalysisJobPublicationService(
                lockingRepository,
                context.jobRepository(),
                new RiskAnalysisJobService(context.jobRepository(), clock),
                new DataSourceTransactionManager(context.dataSource()),
                clock
        );

        assertThatThrownBy(() -> publicationService.publishIfOwned(
                completion(RiskProfileBatchStatus.SUCCEEDED),
                Set.of()
        ))
                .isInstanceOf(RiskProfileBatchLeaseLostException.class)
                .hasMessageContaining("risk-profile:batch-001");
        assertThat(context.batchRepository().findByBatchId("risk-profile:batch-001"))
                .isPresent()
                .get()
                .extracting(RiskProfileBatch::status)
                .isEqualTo(RiskProfileBatchStatus.RUNNING);
    }

    @Test
    void refusesToPublishJobsForAFailedBatch() {
        TestContext context = context("risk_analysis_job_publication_failed_batch");
        context.acquireBatch();

        assertThatThrownBy(() -> context.publicationService().publishIfOwned(
                completion(RiskProfileBatchStatus.FAILED),
                Set.of(new RiskAnalysisJobScope("gid-a", "security-risk-graph", "v1"))
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FAILED");
        assertThat(context.jdbcTemplate().queryForObject(
                "select count(*) from t_agent_risk_analysis_job",
                Long.class
        )).isZero();
        assertThat(context.batchRepository().findByBatchId("risk-profile:batch-001"))
                .isPresent()
                .get()
                .extracting(RiskProfileBatch::status)
                .isEqualTo(RiskProfileBatchStatus.RUNNING);
    }

    private RiskProfileBatch completion(RiskProfileBatchStatus status) {
        return new RiskProfileBatch(
                "risk-profile:batch-001",
                NOW.minusHours(2),
                NOW,
                status,
                "owner-a",
                NOW.plusMinutes(10),
                2,
                2,
                0,
                99,
                List.of(),
                NOW,
                NOW.plusMinutes(1)
        );
    }

    private TestContext context(String databaseName) {
        DataSource dataSource = h2DataSource(databaseName);
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql")).execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        JdbcRiskProfileBatchRepository batchRepository = new JdbcRiskProfileBatchRepository(jdbcTemplate);
        JdbcRiskAnalysisJobRepository jobRepository = new JdbcRiskAnalysisJobRepository(jdbcTemplate);
        Clock clock = Clock.fixed(NOW_INSTANT, SHANGHAI);
        RiskAnalysisJobService jobService = new RiskAnalysisJobService(jobRepository, clock);
        RiskAnalysisJobPublicationService publicationService = new RiskAnalysisJobPublicationService(
                batchRepository,
                jobRepository,
                jobService,
                new DataSourceTransactionManager(dataSource),
                clock
        );
        return new TestContext(
                dataSource,
                jdbcTemplate,
                batchRepository,
                jobRepository,
                jobService,
                publicationService,
                clock
        );
    }

    private DataSource h2DataSource(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + name + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private record TestContext(
            DataSource dataSource,
            JdbcTemplate jdbcTemplate,
            JdbcRiskProfileBatchRepository batchRepository,
            JdbcRiskAnalysisJobRepository jobRepository,
            RiskAnalysisJobService jobService,
            RiskAnalysisJobPublicationService publicationService,
            Clock clock
    ) {

        private void acquireBatch() {
            assertThat(batchRepository.tryAcquire(
                    "risk-profile:batch-001",
                    NOW.minusHours(2),
                    NOW,
                    "owner-a",
                    NOW,
                    Duration.ofMinutes(10)
            )).isTrue();
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
