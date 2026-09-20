package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher.ReportRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore.Binding;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore.BindingDraft;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.ExecutionStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.NextAction;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.NextActionKind;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcCampaignRunResultStoreTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(123_456), ZoneOffset.UTC);

    @Test
    void bindsReplaysExactlyAndRejectsConflictingFacts() {
        Fixture fixture = fixture("result_binding_replay");
        AtomicInteger verifierCalls = new AtomicInteger();
        JdbcCampaignRunResultStore store = fixture.store((run, ref) -> {
            verifierCalls.incrementAndGet();
            return ref.equals(new ReportRef("report-1", 1));
        });
        RunToken token = fixture.token();
        BindingDraft draft = new BindingDraft(new ReportRef("report-1", 1), ExecutionStatus.SUCCEEDED,
                NextAction.none(), List.of("TRUNCATED_EVIDENCE"));

        Binding first = store.bind(token, draft);
        Binding replay = store.bind(token, draft);

        assertThat(replay).isEqualTo(first);
        assertThat(verifierCalls).hasValue(1);
        assertThat(store.read(fixture.caller, "run-1", 1)).contains(first);
        assertThatThrownBy(() -> store.bind(token, new BindingDraft(new ReportRef("report-2", 1),
                ExecutionStatus.SUCCEEDED, NextAction.none())))
                .isInstanceOf(IllegalStateException.class).hasMessage("RUN_RESULT_BINDING_CONFLICT");
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_run_result_binding", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void fencesStaleAndForeignTokensAndValidatesReportAndStatus() {
        Fixture fixture = fixture("result_binding_fences");
        JdbcCampaignRunResultStore store = fixture.store((run, ref) -> ref.reportId().equals("allowed"));
        RunToken token = fixture.token();

        assertThatThrownBy(() -> store.bind(token, new BindingDraft(new ReportRef("denied", 1),
                ExecutionStatus.SUCCEEDED, NextAction.none())))
                .isInstanceOf(SecurityException.class).hasMessage("RUN_RESULT_REPORT_NOT_AUTHORIZED");

        RunToken advanced = fixture.runs.advance(token);
        assertThatThrownBy(() -> store.bind(token, new BindingDraft(ExecutionStatus.RUNNING,
                new NextAction(NextActionKind.CONTINUE, "RUNNING", List.of()))))
                .isInstanceOf(IllegalStateException.class).hasMessage("RUN_RESULT_TOKEN_FENCED");

        RunDefinition foreign = new RunDefinition(new Caller("other-tenant", "subject-1", 1),
                token.definition().sessionId(), token.definition().runId(), token.definition().planId(),
                token.definition().revision(), token.definition().definitionJson());
        RunToken foreignToken = new RunToken(foreign, advanced.version(), advanced.advanceToken());
        assertThatThrownBy(() -> store.bind(foreignToken, new BindingDraft(ExecutionStatus.RUNNING,
                new NextAction(NextActionKind.CONTINUE, "RUNNING", List.of()))))
                .isInstanceOf(SecurityException.class).hasMessage("RUN_RESULT_ACCESS_DENIED");

        assertThatThrownBy(() -> new BindingDraft(new ReportRef("allowed", 1), ExecutionStatus.EMPTY, NextAction.none()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("RUN_RESULT_REPORT_STATUS_MISMATCH");
        assertThatThrownBy(() -> new BindingDraft(ExecutionStatus.SUCCEEDED, NextAction.none()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("RUN_RESULT_REPORT_REQUIRED");

        Binding running = store.bind(advanced, new BindingDraft(ExecutionStatus.RUNNING,
                new NextAction(NextActionKind.CONTINUE, "RUNNING", List.of())));
        assertThat(running.sourceRowVersion()).isEqualTo(advanced.version());
        assertThat(store.read(new Caller("wrong", "subject-1", 1), "run-1", 1)).isEmpty();
    }

    @Test
    void terminalRunRequiresMatchingTerminalBinding() {
        Fixture fixture = fixture("result_binding_terminal");
        JdbcCampaignRunResultStore store = fixture.store((run, ref) -> true);
        RunToken token = fixture.token();
        fixture.runs.cancel(token);
        RunToken cancelled = fixture.runs.loadRun(fixture.caller, "run-1").orElseThrow().token();

        assertThatThrownBy(() -> store.bind(cancelled, new BindingDraft(ExecutionStatus.FAILED,
                new NextAction(NextActionKind.RETRY, "RETRY_LATER", List.of()))))
                .isInstanceOf(IllegalStateException.class).hasMessage("RUN_RESULT_STATUS_MISMATCH");
        Binding result = store.bind(cancelled, new BindingDraft(ExecutionStatus.CANCELLED, NextAction.none()));
        assertThat(result.executionStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(store.read(fixture.caller, "run-1", 1)).contains(result);
    }

    @Test
    void permitsAReportReferenceForAnIncompleteWaitingResult() {
        Fixture fixture = fixture("result_binding_partial");
        JdbcCampaignRunResultStore store = fixture.store((run, ref) -> ref.equals(new ReportRef("partial", 1)));
        Binding binding = store.bind(fixture.token(), new BindingDraft(new ReportRef("partial", 1),
                ExecutionStatus.WAITING, new NextAction(NextActionKind.WAIT, "REPORT_INCOMPLETE", List.of())));

        assertThat(binding.reportRef()).isEqualTo(new ReportRef("partial", 1));
        assertThat(binding.executionStatus()).isEqualTo(ExecutionStatus.WAITING);
    }

    @Test
    void concurrentIdenticalBindsConvergeOnOneImmutableBinding() throws Exception {
        Fixture fixture = fixture("result_binding_concurrent");
        JdbcCampaignRunResultStore first = fixture.store((run, ref) -> true);
        JdbcCampaignRunResultStore second = fixture.store((run, ref) -> true);
        BindingDraft draft = new BindingDraft(ExecutionStatus.WAITING,
                new NextAction(NextActionKind.WAIT, "REMOTE_RESULT", List.of()));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Binding> left = workers.submit(() -> bindAfterBarrier(first, fixture.token(), draft, ready, start));
            Future<Binding> right = workers.submit(() -> bindAfterBarrier(second, fixture.token(), draft, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Binding leftResult = left.get(5, TimeUnit.SECONDS);
            Binding rightResult = right.get(5, TimeUnit.SECONDS);
            assertThat(leftResult).isEqualTo(rightResult);
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_run_result_binding", Integer.class))
                    .isEqualTo(1);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static Binding bindAfterBarrier(JdbcCampaignRunResultStore store, RunToken token,
                                            BindingDraft draft, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return store.bind(token, draft);
    }

    private static Fixture fixture(String name) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260921__campaign_run_result_binding.sql")).execute(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        JdbcCampaignRunStore runs = new JdbcCampaignRunStore(jdbc, transactions, CLOCK);
        Caller caller = new Caller("tenant-1", "subject-1", 1);
        RunDefinition definition = new RunDefinition(caller, "session-1", "run-1", "plan-1", 1, "{}");
        RunToken token = runs.createRun(definition);
        return new Fixture(source, jdbc, transactions, runs, caller, token);
    }

    private record Fixture(DataSource source, JdbcTemplate jdbc, TransactionTemplate transactions,
                           JdbcCampaignRunStore runs, Caller caller, RunToken token) {
        JdbcCampaignRunResultStore store(CampaignRunResultStore.ReportBindingVerifier verifier) {
            return new JdbcCampaignRunResultStore(jdbc, transactions, CLOCK, verifier);
        }
    }
}
