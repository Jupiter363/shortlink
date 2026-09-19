package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.StatsTestFixtures;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

class StatisticsSubmissionReconcilerTest {
    private static final Caller OWNER = new Caller("1001", "zhangsan", 7);
    private static final String BODY = """
            {"requestId":"original-child-request","gid":"g1","startDate":"2026-07-01",
             "endDate":"2026-08-01","queryKind":"METRICS"}
            """;
    private DriverManagerDataSource source;
    private JdbcCampaignRunStore store;
    private RunToken token;
    private final AtomicInteger recoveries = new AtomicInteger();
    private Function<Map<String, Object>, ToolResult> recovery;

    @BeforeEach
    void setup() {
        source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"))
                .execute(source);
        store = reopen();
        token = store.createRun(new RunDefinition(OWNER, "session-1", "run-1", "plan-1", 1, "{}"));
        store.prepareAction(token, new ActionSpec("action-1", "step-1", "TOOL", "statistics", "v1", "{}"));
        recovery = request -> ToolResult.success(Map.of("jobId", "original-job", "state", "RUNNING"));
    }

    @Test
    void reopenedLedgerRecoversOriginalSubmissionOnceAndSubsequentCallsUseKnownIdentity() {
        unresolved(ChildMode.ASYNC);
        store = reopen();
        token = store.advance(store.loadRun(OWNER, "run-1").orElseThrow().token());
        var reconciler = new StatisticsSubmissionReconciler(store, gateway());
        assertThat(reconciler.recover(token, "child-1", StatsTestFixtures.PRINCIPAL).outcome())
                .isEqualTo(StatisticsSubmissionReconciler.Outcome.RECOVERED);
        ChildRecord saved = store.child(token, "child-1").orElseThrow();
        assertThat(saved.state()).isEqualTo(ChildState.WAITING);
        assertThat(saved.jobId()).isEqualTo("original-job");
        assertThat(saved.callbackActive()).isFalse();
        assertThat(saved.spec().wire().bodyJson()).isEqualTo(BODY);
        assertThat(saved.spec().requestId()).isEqualTo("original-child-request");
        assertThat(reconciler.recover(token, "child-1", StatsTestFixtures.PRINCIPAL).outcome())
                .isEqualTo(StatisticsSubmissionReconciler.Outcome.KNOWN_JOB);
        assertThatThrownBy(() -> reconciler.recover(token, "child-1", new AgentPrincipal("2002", "other-user", 7, false)))
                .isInstanceOf(SecurityException.class);
        assertThat(recoveries).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REPLAY_UNAVAILABLE", "RECOVERY_PROTOCOL_UNAVAILABLE", "CONFLICT"})
    void typedFailureRemainsUnresolvedAndCannotCreateAReplacement(String code) {
        unresolved(ChildMode.ASYNC);
        recovery = request -> new ToolResult(false, Map.of("code", code), "failure");
        var result = new StatisticsSubmissionReconciler(store, gateway())
                .recover(token, "child-1", StatsTestFixtures.PRINCIPAL);
        assertThat(result.code()).isEqualTo(code);
        assertThat(result.outcome()).isEqualTo(StatisticsSubmissionReconciler.Outcome.UNRESOLVED);
        var saved = store.child(token, "child-1").orElseThrow();
        assertThat(saved.state()).isEqualTo(ChildState.UNRESOLVED);
        assertThat(saved.reason()).isEqualTo(UnresolvedReason.SUBMISSION_UNRESOLVED);
        assertThat(saved.jobId()).isNull();
        assertThat(saved.callbackActive()).isFalse();
        assertThatThrownBy(() -> store.beginDispatch(token, "child-1")).isInstanceOf(IllegalStateException.class);
        assertThat(recoveries).hasValue(1);
    }

    @Test
    void lostSynchronousResponseCannotBeReplayedAsAnAsynchronousSubmission() {
        unresolved(ChildMode.SYNC);
        var result = new StatisticsSubmissionReconciler(store, gateway())
                .recover(token, "child-1", StatsTestFixtures.PRINCIPAL);
        assertThat(result.code()).isEqualTo("READ_RESULT_UNKNOWN");
        assertThat(recoveries).hasValue(0);
    }

    @Test
    void lateRecoveredIdentityCannotReopenACancelledRun() {
        unresolved(ChildMode.ASYNC);
        recovery = request -> {
            store.cancel(token);
            return ToolResult.success(Map.of("jobId", "original-job", "state", "SUCCEEDED"));
        };
        var result = new StatisticsSubmissionReconciler(store, gateway())
                .recover(token, "child-1", StatsTestFixtures.PRINCIPAL);
        assertThat(result.outcome()).isEqualTo(StatisticsSubmissionReconciler.Outcome.STOPPED);
        assertThat(result.jobId()).isEqualTo("original-job");
        assertThat(store.loadRun(OWNER, "run-1").orElseThrow().status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(recoveries).hasValue(1);
    }

    @Test
    void frozenWireUsesOnlyExistingFrozenRecoveryAndNeverFallsBackAfterAnUnavailableIdentity() throws Exception {
        List<Long> members = List.of(1L, 2L);
        String hash = FrozenQueryScope.memberHash(members);
        FrozenQueryScope scope = new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET", "scope-frozen", hash,
                members.size(), "a".repeat(64), FrozenQueryScope.shardIdFor("scope-frozen", 0, hash), 0, 1, hash, members);
        String body = new ObjectMapper().writeValueAsString(Map.of("requestId", "original-child-request", "gid", "g1",
                "startDate", "2026-07-01", "endDate", "2026-08-01", "queryKind", "METRICS", "scope", scope.asMap()));
        ChildSpec child = new ChildSpec("child-frozen", "action-1", ChildMode.ASYNC, "original-child-request",
                new WireRequest("POST", StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH, body));
        store.prepareChild(token, child);
        DispatchPermit dispatch = store.beginDispatch(token, child.childId());
        store.markUnresolved(dispatch);
        store.callbackExited(dispatch);
        store = reopen();
        token = store.advance(store.loadRun(OWNER, "run-1").orElseThrow().token());
        AtomicInteger frozenRecoveries = new AtomicInteger();
        ShortLinkBusinessGateway gateway = new ShortLinkBusinessGateway() {
            @Override public ToolResult get(String path, ToolContext context, Map<String, Object> query) {
                throw new AssertionError("Frozen recovery must not read a replacement query");
            }
            @Override public ToolResult post(String path, ToolContext context, Map<String, Object> request) {
                throw new AssertionError("Frozen recovery must never submit a replacement");
            }
            @Override public ToolResult recoverExistingStatisticsJob(ToolContext context, Map<String, Object> request) {
                throw new AssertionError("Frozen recovery must not fall back to the old endpoint");
            }
            @Override public ToolResult recoverExistingFrozenStatisticsJob(ToolContext context, Map<String, Object> request) {
                assertThat(context.principal()).isEqualTo(StatsTestFixtures.PRINCIPAL);
                assertThat(request).containsEntry("requestId", child.requestId());
                assertThat(FrozenQueryScope.fromMap((Map<?, ?>) request.get("scope"))).isEqualTo(scope);
                return frozenRecoveries.incrementAndGet() == 1
                        ? new ToolResult(false, Map.of("code", "REPLAY_UNAVAILABLE"), "unavailable")
                        : ToolResult.success(Map.of("jobId", "original-frozen-job", "state", "RUNNING"));
            }
        };
        var reconciler = new StatisticsSubmissionReconciler(store, gateway);
        assertThat(reconciler.recover(token, child.childId(), StatsTestFixtures.PRINCIPAL).outcome())
                .isEqualTo(StatisticsSubmissionReconciler.Outcome.UNRESOLVED);
        assertThat(store.child(token, child.childId()).orElseThrow().jobId()).isNull();
        assertThatThrownBy(() -> store.beginDispatch(token, child.childId())).isInstanceOf(IllegalStateException.class);
        assertThat(reconciler.recover(token, child.childId(), StatsTestFixtures.PRINCIPAL).outcome())
                .isEqualTo(StatisticsSubmissionReconciler.Outcome.RECOVERED);
        ChildRecord saved = store.child(token, child.childId()).orElseThrow();
        assertThat(saved.spec()).isEqualTo(child);
        assertThat(saved.spec().wire().bodyJson()).isEqualTo(body);
        assertThat(saved.jobId()).isEqualTo("original-frozen-job");
        assertThat(saved.callbackActive()).isFalse();
        assertThat(reconciler.recover(token, child.childId(), StatsTestFixtures.PRINCIPAL).outcome())
                .isEqualTo(StatisticsSubmissionReconciler.Outcome.KNOWN_JOB);
        assertThat(frozenRecoveries).hasValue(2);
    }

    private void unresolved(ChildMode mode) {
        store.prepareChild(token, new ChildSpec("child-1", "action-1", mode, "original-child-request",
                new WireRequest(mode == ChildMode.ASYNC ? "POST" : "GET",
                        "/internal/short-link-admin/v1/agent-tools/statistics/jobs", BODY)));
        var dispatched = store.beginDispatch(token, "child-1");
        store.markUnresolved(dispatched);
        store.callbackExited(dispatched);
    }

    private JdbcCampaignRunStore reopen() {
        return new JdbcCampaignRunStore(new JdbcTemplate(source),
                new TransactionTemplate(new DataSourceTransactionManager(source)), Clock.systemUTC());
    }

    private ShortLinkBusinessGateway gateway() {
        return new ShortLinkBusinessGateway() {
            @Override public ToolResult get(String path, ToolContext context, Map<String, Object> query) {
                throw new AssertionError("Recovery must not re-read synchronous results");
            }
            @Override public ToolResult post(String path, ToolContext context, Map<String, Object> body) {
                throw new AssertionError("Recovery must not submit a replacement");
            }
            @Override public ToolResult recoverExistingStatisticsJob(ToolContext context, Map<String, Object> request) {
                recoveries.incrementAndGet();
                assertThat(context.principal()).isEqualTo(StatsTestFixtures.PRINCIPAL);
                assertThat(request).containsEntry("requestId", "original-child-request").containsEntry("gid", "g1");
                return recovery.apply(request);
            }
        };
    }
}
