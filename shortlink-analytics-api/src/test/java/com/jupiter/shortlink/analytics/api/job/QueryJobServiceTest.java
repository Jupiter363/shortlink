package com.jupiter.shortlink.analytics.api.job;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.analytics.api.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

class QueryJobServiceTest {
    JdbcTemplate db;
    QueryJobService service;
    AuthorizationClient auth;
    JobClickHouseStream ch;
    AtomicReference<String> ownership = new AtomicReference<>("v1"),
            epoch = new AtomicReference<>("epoch1");
    final ObjectMapper json = new ObjectMapper();
    final long start = 300_000, end = 600_000;

    @BeforeEach
    void setup() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE analytics_query_gate(singleton TINYINT PRIMARY KEY)");
        db.update("INSERT INTO analytics_query_gate VALUES(1)");
        db.execute(
                "CREATE TABLE analytics_epoch(singleton TINYINT PRIMARY KEY,recovery_epoch"
                        + " VARCHAR(64),mode VARCHAR(24),command_ack BOOLEAN)");
        db.update("INSERT INTO analytics_epoch VALUES(1,'epoch1','ACTIVE',TRUE)");
        db.execute(
                "CREATE TABLE analytics_manifest(window_start BIGINT PRIMARY KEY,recovery_epoch"
                    + " VARCHAR(64),build_id VARCHAR(64),manifest_revision BIGINT,source_cut"
                    + " TEXT,source_observed_at BIGINT,metric_version VARCHAR(64),dataset_version"
                    + " VARCHAR(64),finalized BOOLEAN,replica_ids VARCHAR(2000),coverage_proof"
                    + " TEXT)");
        db.update(
                "INSERT INTO analytics_manifest"
                    + " VALUES(?,'epoch1','build-old',1,'{}',?,'click-v1','detail-v1',TRUE,'[\"http://localhost:8123\"]','{\"n\":1,\"digest\":\"7\"}')",
                start,
                end);
        db.execute(
                "CREATE TABLE analytics_query_job(job_id VARCHAR(64) PRIMARY KEY,tenant_id"
                    + " VARCHAR(128),subject_id VARCHAR(128),request_id VARCHAR(96),request_hash"
                    + " CHAR(64),request_json TEXT,recovery_epoch VARCHAR(64),ownership_version"
                    + " VARCHAR(256),manifest_json TEXT,manifest_hash CHAR(64),state"
                    + " VARCHAR(24),lease_owner VARCHAR(64),lease_token BIGINT DEFAULT"
                    + " 0,lease_until BIGINT DEFAULT 0,attempts INT DEFAULT 0,next_attempt_at"
                    + " BIGINT DEFAULT 0,row_count BIGINT DEFAULT 0,byte_count BIGINT DEFAULT"
                    + " 0,page_count INT DEFAULT 0,error_code VARCHAR(128),created_at"
                    + " BIGINT,updated_at BIGINT,expires_at"
                    + " BIGINT,UNIQUE(tenant_id,subject_id,request_id))");
        db.execute(
                "CREATE TABLE analytics_query_page(job_id VARCHAR(64),lease_token BIGINT,page_index"
                        + " INT,payload_json TEXT,PRIMARY KEY(job_id,lease_token,page_index))");
        auth = mock(AuthorizationClient.class);
        ch = mock(JobClickHouseStream.class);
        when(auth.authorize(any()))
                .thenAnswer(
                        i -> {
                            QueryRequest q = i.getArgument(0);
                            return new AuthorizationClient.Scope(
                                    q.tenantId(), List.of(4000000001L), ownership.get());
                        });
        when(auth.activeEpoch()).thenAnswer(i -> epoch.get());
        when(ch.verify(any(), anyLong())).thenReturn("http://localhost:8123");
        service =
                new QueryJobService(
                        db,
                        json,
                        auth,
                        new ApiSettings(
                                "test-token-long-enough-for-analytics",
                                "http://localhost:1",
                                "http://localhost:2",
                                "http://localhost:8123",
                                "default",
                                "",
                                "test"),
                        ch,
                        new DataSourceTransactionManager(source));
    }

    QueryRequest request(String tenant) {
        return new QueryRequest(
                tenant,
                "alice",
                7,
                "g1",
                List.of(4000000001L),
                start,
                end,
                null,
                "REQUESTED",
                null,
                null,
                500,
                "METRICS");
    }

    QueryJobService.Status submit(String key) {
        return service.submit(new QueryJobService.Submit(key, request("1")));
    }

    QueryJobService.Identity identity(int page) {
        return new QueryJobService.Identity("1", "alice", 8, page, 500);
    }

    @SuppressWarnings("unchecked")
    void emit(int count) {
        doAnswer(
                        i -> {
                            Consumer<Map<String, Object>> consumer = i.getArgument(4);
                            for (int n = 0; n < count; n++)
                                consumer.accept(
                                        Map.of(
                                                "day",
                                                "1970-01-01",
                                                "linkId",
                                                4000000001L,
                                                "pv",
                                                4294967296L,
                                                "row",
                                                n));
                            return null;
                        })
                .when(ch)
                .query(anyString(), anyString(), anyInt(), anyLong(), any());
    }

    @Test
    void idempotentAdmissionAndTenantCapacityNeverExecuteClickHouse() {
        var first = submit("r1");
        assertThat(submit("r1").jobId()).isEqualTo(first.jobId());
        submit("r2");
        assertThatThrownBy(() -> submit("r3"))
                .isInstanceOf(QueryFailure.class)
                .hasMessageContaining("capacity");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM analytics_query_job", Integer.class))
                .isEqualTo(2);
        verifyNoInteractions(ch);
        var changed =
                new QueryRequest(
                        "1",
                        "alice",
                        7,
                        "g1",
                        List.of(4000000001L),
                        start,
                        end - 1,
                        null,
                        "REQUESTED",
                        null,
                        null,
                        500,
                        "METRICS");
        assertThatThrownBy(() -> service.submit(new QueryJobService.Submit("r1", changed)))
                .hasMessageContaining("another query");
    }

    @Test
    void missingCanonicalWindowFailsAdmissionWithoutInventingZeros() {
        db.update("DELETE FROM analytics_manifest");
        assertThatThrownBy(() -> submit("r1")).hasMessageContaining("canonical window");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM analytics_query_job", Integer.class))
                .isZero();
    }

    @Test
    void fixedBuildSurvivesPublicationThenPagesReauthorizeAndKeepLongCounters() {
        emit(501);
        var job = submit("r1");
        db.update("UPDATE analytics_manifest SET build_id='build-new',manifest_revision=2");
        var lease = service.claim("worker1");
        assertThat(lease.plan().windows().get(0).build()).isEqualTo("build-old");
        assertThat(QueryJobService.sql(lease)).contains("build-old").doesNotContain("build-new");
        assertThatThrownBy(() -> service.page(job.jobId(), identity(0)))
                .hasMessageContaining("no published");
        service.execute(lease);
        assertThat(service.status(job.jobId(), identity(0)).state()).isEqualTo("SUCCEEDED");
        var first = service.page(job.jobId(), identity(0));
        var second = service.page(job.jobId(), identity(1));
        assertThat((List<?>) first.get("items")).hasSize(500);
        assertThat((List<?>) second.get("items")).hasSize(1);
        var row = (Map<?, ?>) ((List<?>) second.get("items")).get(0);
        assertThat(((Number) row.get("pv")).longValue()).isEqualTo(4294967296L);
        ownership.set("v2");
        assertThatThrownBy(() -> service.page(job.jobId(), identity(1)))
                .hasMessageContaining("scope changed");
    }

    @Test
    void expiredWorkerCannotCommitAfterTakeoverAndRestartUsesSameFrozenPlan() {
        emit(1);
        var job = submit("r1");
        var old = service.claim("old");
        db.update("UPDATE analytics_query_job SET lease_until=0 WHERE job_id=?", job.jobId());
        var replacement = service.claim("new");
        assertThat(replacement.token()).isGreaterThan(old.token());
        service.execute(old);
        assertThat(service.status(job.jobId(), identity(0)).state()).isEqualTo("RUNNING");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM analytics_query_page", Integer.class))
                .isZero();
        service.execute(replacement);
        assertThat(service.status(job.jobId(), identity(0)).state()).isEqualTo("SUCCEEDED");
    }

    @Test
    void cancellationFencesInFlightWriterAndPublishesNoPartialPages() {
        emit(501);
        var job = submit("r1");
        var lease = service.claim("worker");
        service.cancel(job.jobId(), identity(0));
        service.execute(lease);
        assertThat(service.status(job.jobId(), identity(0)).state()).isEqualTo("CANCELLED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM analytics_query_page", Integer.class))
                .isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamFailureDiscardsAttemptPagesAndRetriesAreBounded() {
        doAnswer(
                        i -> {
                            Consumer<Map<String, Object>> c = i.getArgument(4);
                            for (int n = 0; n < 500; n++) c.accept(Map.of("linkId", 1));
                            throw new QueryFailure("UNAVAILABLE", "network interrupted");
                        })
                .when(ch)
                .query(anyString(), anyString(), anyInt(), anyLong(), any());
        var job = submit("r1");
        for (int attempt = 0; attempt < 3; attempt++) {
            db.update("UPDATE analytics_query_job SET next_attempt_at=0");
            service.execute(service.claim("worker"));
            assertThat(
                            db.queryForObject(
                                    "SELECT COUNT(*) FROM analytics_query_page", Integer.class))
                    .isZero();
        }
        assertThat(service.status(job.jobId(), identity(0)).state()).isEqualTo("FAILED");
        assertThat(service.claim("worker")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void epochChangeBetweenResultReadAndCommitPreventsPublication() {
        doAnswer(
                        i -> {
                            ((Consumer<Map<String, Object>>) i.getArgument(4))
                                    .accept(Map.of("linkId", 1));
                            epoch.set("epoch2");
                            return null;
                        })
                .when(ch)
                .query(anyString(), anyString(), anyInt(), anyLong(), any());
        var job = submit("r1");
        service.execute(service.claim("worker"));
        assertThat(db.queryForObject("SELECT state FROM analytics_query_job", String.class))
                .isEqualTo("FAILED");
        assertThatThrownBy(() -> service.page(job.jobId(), identity(0)))
                .hasMessageContaining("epoch changed");
    }

    @Test
    void expiredRetentionRemovesOnlyJobAndItsPagesAndReleasesAdmission() {
        emit(1);
        var job = submit("r1");
        service.execute(service.claim("worker"));
        db.update("UPDATE analytics_query_job SET expires_at=0 WHERE job_id=?", job.jobId());
        service.cleanup();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM analytics_query_job", Integer.class))
                .isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM analytics_query_page", Integer.class))
                .isZero();
        assertThat(submit("r1").jobId()).isNotEqualTo(job.jobId());
    }

    @Test
    void concurrentRetriesAdmitExactlyOneDurableJob() throws Exception {
        var workers = java.util.concurrent.Executors.newFixedThreadPool(3);
        var start = new java.util.concurrent.CyclicBarrier(3);
        try {
            List<java.util.concurrent.Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 3; i++)
                results.add(
                        workers.submit(
                                () -> {
                                    start.await();
                                    return submit("shared-key").jobId();
                                }));
            Set<String> ids = new HashSet<>();
            for (var result : results)
                ids.add(result.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM analytics_query_job", Integer.class))
                    .isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void completedResultsDoNotContinueConsumingExecutionQuota() {
        emit(1);
        submit("r1");
        submit("r2");
        assertThatThrownBy(() -> submit("r3")).hasMessageContaining("capacity");
        service.execute(service.claim("worker"));
        assertThat(submit("r3").state()).isEqualTo("QUEUED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM analytics_query_job", Integer.class))
                .isEqualTo(3);
    }
}
