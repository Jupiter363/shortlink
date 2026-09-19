package com.jupiter.shortlink.analytics.api.job;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jupiter.shortlink.analytics.api.*;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

class QueryJobResultReleaseTest {
    private static final String VERSION = "a".repeat(64);
    private static final String TOKEN = "test-token-long-enough-for-analytics";

    @Test
    void releasingOneOfEightResultsAdmitsNinthButReplayKeepsOriginalIdentityAndDeadline() throws Exception {
        var f = fixture();
        List<QueryJobService.Status> completed = new ArrayList<>();
        for (int i = 0; i < 8; i++) completed.add(complete(f, request("retained-" + i)));
        var original = request("retained-0");
        var first = completed.get(0);
        var before = row(f, first.jobId());
        assertThat(first.resultState()).isEqualTo("AVAILABLE");
        assertThat(first.resultReady()).isTrue();
        assertThat(pageCount(f, first.jobId())).isEqualTo(2); // One business page and the whole-window summary.
        var mvc = MockMvcBuilders.standaloneSetup(new QueryJobController(f.service, f.settings)).build();
        mvc.perform(post("/internal/analytics/v1/jobs/frozen").header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(f.json.writeValueAsBytes(request("ninth"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("QUERY_CAPACITY_EXHAUSTED"))
                .andExpect(jsonPath("$.admitted").value(false)).andExpect(jsonPath("$.capacityKind").value("RESULT_STORAGE"));
        assertThat(jobCount(f)).isEqualTo(8);
        mvc.perform(post("/internal/analytics/v1/jobs/" + first.jobId() + "/release-result")
                        .header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(f.json.writeValueAsBytes(original)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.jobId").value(first.jobId()))
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.resultState").value("RELEASED"))
                .andExpect(jsonPath("$.data.resultReady").value(false))
                .andExpect(jsonPath("$.data.resultCode").value("RESULT_RELEASED"))
                .andExpect(jsonPath("$.data.expiresAt").value(first.expiresAt()));
        var after = row(f, first.jobId());
        for (String field : List.of("request_id", "request_hash", "request_json", "manifest_hash", "recovery_epoch",
                "ownership_version", "state", "expires_at", "row_count", "byte_count", "page_count", "lease_token"))
            assertThat(after.get(field)).as(field).isEqualTo(before.get(field));
        assertThat(after.get("manifest_json")).isEqualTo("{}");
        assertThat(((Number) after.get("released_at")).longValue()).isPositive();
        assertThat(pageCount(f, first.jobId())).isZero();
        assertThat(f.service.submitFrozen(request("ninth")).state()).isEqualTo("QUEUED");
        noInsert(f);
        // Lost release reply, submit retry and recover all resolve to the same released identity.
        for (var status : List.of(f.service.releaseResult(first.jobId(), original), f.service.submitFrozen(original),
                f.service.recoverExistingFrozen(original), f.service.status(first.jobId(), f.identity(0)))) {
            assertThat(status.jobId()).isEqualTo(first.jobId());
            assertThat(status.expiresAt()).isEqualTo(first.expiresAt());
            assertThat(status.resultState()).isEqualTo("RELEASED");
            assertThat(status.resultReady()).isFalse();
        }
        assertThat(row(f, first.jobId()).get("released_at")).isEqualTo(after.get("released_at"));
        failure(() -> f.service.page(first.jobId(), f.identity(0)), "RESULT_RELEASED");
        assertThat(jobCount(f)).isEqualTo(9);
        assertThat(QueryJobServiceTest.RecoveryInsertGuard.attempts).hasValue(0);
    }

    @Test
    void executionResultAndRecoveryIdentityBudgetsRemainIndependentAndErrorsCannotForgeAdmissionProof() {
        var active = fixture();
        active.service.submitFrozen(request("a")); active.service.submitFrozen(request("b"));
        capacity(() -> active.service.submitFrozen(request("c")), "ACTIVE_EXECUTION");
        assertThat(jobCount(active)).isEqualTo(2);

        var identities = fixture();
        identities.service = service(identities, identities.db,
                new QueryJobCapacity(2, 8, 8, 128, 1L << 30, 1, 16384, 1L << 24, 1L << 27));
        var original = request("identity"); var job = complete(identities, original);
        identities.service.releaseResult(job.jobId(), original);
        capacity(() -> identities.service.submitFrozen(request("next")), "RECOVERY_IDENTITY");
        assertThat(identities.service.recoverExistingFrozen(original).jobId()).isEqualTo(job.jobId());

        var identityBytes = fixture();
        identityBytes.service = service(identityBytes, identityBytes.db,
                new QueryJobCapacity(2, 8, 8, 128, 1L << 30, 2048, 16384, 1, 1L << 27));
        capacity(() -> identityBytes.service.submitFrozen(request("bytes")), "RECOVERY_IDENTITY");
        assertThat(jobCount(identityBytes)).isZero();
        var resultBytes = fixture();
        resultBytes.service = service(resultBytes, resultBytes.db,
                new QueryJobCapacity(2, 8, 8, 128, QueryJobService.MAX_BYTES, 2048, 16384, 1L << 24, 1L << 27));
        capacity(() -> resultBytes.service.submitFrozen(request("bytes")), "RESULT_STORAGE");
        assertThat(jobCount(resultBytes)).isZero();

        assertThatThrownBy(() -> new QueryFailure("UNAVAILABLE", "remote", Map.of("admitted", false, "capacityKind", "RESULT_STORAGE")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueryFailure("QUERY_CAPACITY_EXHAUSTED", "remote", Map.of("admitted", true, "capacityKind", "RESULT_STORAGE")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueryFailure("QUERY_CAPACITY_EXHAUSTED", "remote", Map.of("admitted", false, "capacityKind", "UNKNOWN")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacyActiveWrongIdentityAndRevokedScopeCannotReleaseAndCancelledWorkerCannotResurrect() {
        var f = fixture();
        var legacyRequest = new QueryJobService.Submit("legacy", f.request("1"));
        var legacy = f.service.submit(legacyRequest);
        f.service.cancel(legacy.jobId(), f.identity(0));
        failure(() -> f.service.releaseResult(legacy.jobId(), legacyRequest), "RESULT_RELEASE_UNSUPPORTED");

        var request = request("managed"); var job = f.service.submitFrozen(request);
        failure(() -> f.service.releaseResult(job.jobId(), request), "RESULT_NOT_RELEASABLE");
        failure(() -> f.service.releaseResult(job.jobId(), new QueryJobService.Submit("other", request.query())), "CONFLICT");
        var changed = changed(f, request.query(), "endExclusive", 599999L);
        failure(() -> f.service.releaseResult(job.jobId(), new QueryJobService.Submit(request.requestId(), changed)), "CONFLICT");
        var otherSubject = changed(f, request.query(), "subjectId", "bob");
        failure(() -> f.service.releaseResult(job.jobId(), new QueryJobService.Submit(request.requestId(), otherSubject)), "FORBIDDEN");
        var lease = f.service.claim("worker");
        failure(() -> f.service.releaseResult(job.jobId(), request), "RESULT_NOT_RELEASABLE");
        assertThat(f.service.cancel(job.jobId(), f.identity(0)).resultState()).isEqualTo("UNAVAILABLE");
        doThrow(new QueryFailure("FORBIDDEN", "Revoked")).when(f.auth).authorize(any());
        failure(() -> f.service.releaseResult(job.jobId(), request), "FORBIDDEN");
        authorize(f);
        doReturn(new AuthorizationClient.Scope("1", request.query().linkIds(), "b".repeat(64))).when(f.auth).authorize(any());
        failure(() -> f.service.releaseResult(job.jobId(), request), "QUERY_SCOPE_CHANGED");
        authorize(f);
        f.db.update("UPDATE analytics_epoch SET mode='RECOVERING'");
        failure(() -> f.service.releaseResult(job.jobId(), request), "SNAPSHOT_EXPIRED");
        f.db.update("UPDATE analytics_epoch SET mode='ACTIVE'");
        var released = f.service.releaseResult(job.jobId(), request);
        f.service.execute(lease);
        assertThat(f.service.status(job.jobId(), f.identity(0))).isEqualTo(released);
        assertThat(pageCount(f, job.jobId())).isZero();
        assertThat(f.service.claim("later-worker")).isNull();
        failure(() -> f.service.page(job.jobId(), f.identity(0)), "RESULT_RELEASED");

        f.db.update("UPDATE analytics_query_job SET expires_at=0 WHERE job_id=?", job.jobId());
        noInsert(f);
        failure(() -> f.service.releaseResult(job.jobId(), request), "REPLAY_UNAVAILABLE");
        f.service.cleanup();
        failure(() -> f.service.recoverExistingFrozen(request), "REPLAY_UNAVAILABLE");
        assertThat(QueryJobServiceTest.RecoveryInsertGuard.attempts).hasValue(0);
    }

    @Test
    void pageIsAtomicWithReleaseAndStillSeesConcurrentEpochChange() throws Exception {
        for (boolean release : List.of(true, false)) {
            var f = fixture(); var request = request("page-race"); var job = complete(f, request);
            CountDownLatch readPage = new CountDownLatch(1), finishPage = new CountDownLatch(1), releaseAtRow = new CountDownLatch(1);
            JdbcTemplate observed = new JdbcTemplate(f.db.getDataSource()) {
                @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
                    if (Thread.currentThread().getName().equals("result-release")
                            && sql.contains("subject_id=? FOR UPDATE")) releaseAtRow.countDown();
                    var rows = super.queryForList(sql, args);
                    if (Thread.currentThread().getName().equals("result-page")
                            && sql.contains("SELECT payload_json FROM analytics_query_page") && sql.endsWith("page_index=?")) {
                        readPage.countDown(); await(finishPage);
                    }
                    return rows;
                }
            };
            var reader = service(f, observed, QueryJobCapacity.defaults());
            // This read is intentionally a SQL read inside pageTx: repeatable-read would hide the change.
            doAnswer(call -> f.db.queryForObject("SELECT recovery_epoch FROM analytics_epoch WHERE singleton=1", String.class))
                    .when(f.auth).activeEpoch();
            var threads = Executors.newFixedThreadPool(2);
            try {
                var page = threads.submit(() -> { Thread.currentThread().setName("result-page"); return reader.page(job.jobId(), f.identity(0)); });
                assertThat(readPage.await(3, TimeUnit.SECONDS)).isTrue();
                Future<QueryJobService.Status> released = null;
                if (release) {
                    released = threads.submit(() -> { Thread.currentThread().setName("result-release"); return reader.releaseResult(job.jobId(), request); });
                    assertThat(releaseAtRow.await(3, TimeUnit.SECONDS)).isTrue();
                    assertThat(released.isDone()).isFalse();
                } else f.db.update("UPDATE analytics_epoch SET recovery_epoch='epoch2'");
                finishPage.countDown();
                if (release) {
                    var result = page.get(5, TimeUnit.SECONDS);
                    assertThat((List<?>) result.get("items")).hasSize(1);
                    assertThat((Map<?, ?>) result.get("metrics")).isNotEmpty();
                    assertThat(released.get(5, TimeUnit.SECONDS).resultState()).isEqualTo("RELEASED");
                    failure(() -> reader.page(job.jobId(), f.identity(0)), "RESULT_RELEASED");
                } else assertThatThrownBy(() -> page.get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(QueryFailure.class)
                        .satisfies(error -> assertThat(((QueryFailure) error.getCause()).code).isEqualTo("SNAPSHOT_EXPIRED"));
            } finally {
                finishPage.countDown(); threads.shutdownNow();
                assertThat(threads.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void cleanupWinningTheGateMakesReleaseUnavailableWithoutRecreatingAnything() throws Exception {
        var f = fixture(); var request = request("cleanup-race"); var job = complete(f, request);
        noInsert(f);
        CountDownLatch atGate = new CountDownLatch(1);
        JdbcTemplate observed = new JdbcTemplate(f.db.getDataSource()) {
            @Override public List<Map<String, Object>> queryForList(String sql) {
                if (sql.equals("SELECT singleton FROM analytics_query_gate WHERE singleton=1 FOR UPDATE")) atGate.countDown();
                return super.queryForList(sql);
            }
        };
        var releaser = service(f, observed, QueryJobCapacity.defaults());
        var holder = new TransactionTemplate(new DataSourceTransactionManager(f.db.getDataSource()));
        var thread = Executors.newSingleThreadExecutor();
        var result = new java.util.concurrent.atomic.AtomicReference<Future<QueryJobService.Status>>();
        try {
            holder.executeWithoutResult(transaction -> {
                f.db.queryForList("SELECT singleton FROM analytics_query_gate WHERE singleton=1 FOR UPDATE");
                result.set(thread.submit(() -> releaser.releaseResult(job.jobId(), request)));
                await(atGate);
                assertThat(result.get().isDone()).isFalse();
                f.db.update("UPDATE analytics_query_job SET expires_at=0 WHERE job_id=?", job.jobId());
                f.service.cleanup();
            });
            assertThatThrownBy(() -> result.get().get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(QueryFailure.class)
                    .satisfies(error -> assertThat(((QueryFailure) error.getCause()).code).isEqualTo("FORBIDDEN"));
            assertThat(jobCount(f)).isZero(); assertThat(pageCount(f, job.jobId())).isZero();
            assertThat(QueryJobServiceTest.RecoveryInsertGuard.attempts).hasValue(0);
        } finally {
            thread.shutdownNow(); assertThat(thread.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static QueryJobServiceTest fixture() {
        var f = new QueryJobServiceTest(); f.setup(); authorize(f);
        doAnswer(call -> {
            Consumer<Map<String, Object>> output = call.getArgument(4);
            output.accept(Map.of("group_row", 1, "pv", 0, "uv", 0, "uip", 0, "denied", 0));
            return null;
        }).when(f.ch).query(anyString(), anyString(), anyInt(), anyLong(), any());
        return f;
    }

    private static void authorize(QueryJobServiceTest f) {
        doAnswer(call -> { QueryRequest q = call.getArgument(0);
            return new AuthorizationClient.Scope(q.tenantId(), q.linkIds(), VERSION);
        }).when(f.auth).authorize(any());
    }

    private static QueryJobService.Submit request(String id) {
        List<Long> ids = List.of(4000000001L); String hash = FrozenQueryScope.memberHash(ids);
        var scope = new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET", "scope-result-release", hash, 1,
                "e".repeat(64), FrozenQueryScope.shardIdFor("scope-result-release", 0, hash), 0, 1, hash, ids);
        return new QueryJobService.Submit(id, new QueryRequest("1", "alice", 7, "g1", ids, 300000L, 600000L,
                null, "REQUESTED", null, null, 500, "LINK_METRICS", null, null, scope));
    }

    private static QueryJobService service(QueryJobServiceTest f, JdbcTemplate db, QueryJobCapacity capacity) {
        return new QueryJobService(db, f.json, f.auth, f.settings, f.ch,
                new DataSourceTransactionManager(f.db.getDataSource()), capacity);
    }

    private static QueryJobService.Status complete(QueryJobServiceTest f, QueryJobService.Submit request) {
        var job = f.service.submitFrozen(request); f.service.execute(f.service.claim("worker"));
        var status = f.service.status(job.jobId(), f.identity(0));
        assertThat(status.state()).isEqualTo("SUCCEEDED"); return status;
    }

    private static QueryRequest changed(QueryJobServiceTest f, QueryRequest q, String field, Object value) {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) f.json.valueToTree(q);
        node.set(field, f.json.valueToTree(value)); return f.json.convertValue(node, QueryRequest.class);
    }

    private static Map<String, Object> row(QueryJobServiceTest f, String id) {
        return f.db.queryForMap("SELECT * FROM analytics_query_job WHERE job_id=?", id);
    }
    private static int jobCount(QueryJobServiceTest f) { return f.db.queryForObject("SELECT COUNT(*) FROM analytics_query_job", Integer.class); }
    private static int pageCount(QueryJobServiceTest f, String id) { return f.db.queryForObject("SELECT COUNT(*) FROM analytics_query_page WHERE job_id=?", Integer.class, id); }
    private static void noInsert(QueryJobServiceTest f) {
        QueryJobServiceTest.RecoveryInsertGuard.attempts.set(0);
        f.db.execute("CREATE TRIGGER release_no_insert BEFORE INSERT ON analytics_query_job FOR EACH ROW CALL '"
                + QueryJobServiceTest.RecoveryInsertGuard.class.getName() + "'");
    }
    private static void capacity(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String kind) {
        assertThatThrownBy(action).isInstanceOfSatisfying(QueryFailure.class, failure -> {
            assertThat(failure.code).isEqualTo("QUERY_CAPACITY_EXHAUSTED");
            assertThat(failure.details).isEqualTo(Map.of("admitted", false, "capacityKind", kind));
        });
    }
    private static void failure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(QueryFailure.class, failure -> assertThat(failure.code).isEqualTo(code));
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
}
