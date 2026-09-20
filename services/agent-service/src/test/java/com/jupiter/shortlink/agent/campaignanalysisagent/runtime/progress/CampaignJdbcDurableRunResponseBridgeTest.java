package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportReadProjection;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.GoalAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportBlock;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportDraft;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportSection;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignDurableRunReadCoordinator;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignResultProgressReader;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.ExecutionStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.NextAction;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.JdbcReportLifecycleStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class CampaignJdbcDurableRunResponseBridgeTest {
    private static final Instant NOW = Instant.ofEpochMilli(1_000);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Caller CALLER = new Caller("tenant-1", "subject-1", 1);
    private static final String OWNER = "report-owner";
    private static final String CAPABILITY = "campaign/report/v1";

    @Test
    void noBindingDoesNotInvokeAnyProjector() {
        Fixture fixture = fixture("bridge_empty", false, false);
        AtomicInteger progressCalls = new AtomicInteger();
        AtomicInteger reportCalls = new AtomicInteger();
        AtomicInteger resultCalls = new AtomicInteger();
        CampaignJdbcDurableRunResponseBridge bridge = bridge(fixture,
                (caller, snapshot) -> { progressCalls.incrementAndGet(); throw new AssertionError(); },
                (published, mode) -> { reportCalls.incrementAndGet(); throw new AssertionError(); },
                (progress, report) -> { resultCalls.incrementAndGet(); throw new AssertionError(); });

        CampaignJdbcDurableRunResponseBridge.Outcome outcome = bridge.read(request(fixture, "plan-1"));

        assertThat(outcome.status()).isEqualTo(CampaignJdbcDurableRunResponseBridge.Outcome.Status.NO_BINDING);
        assertThat(outcome.response()).isEmpty();
        assertThat(progressCalls).hasValue(0);
        assertThat(reportCalls).hasValue(0);
        assertThat(resultCalls).hasValue(0);
    }

    @Test
    void waitingProjectionIsBoundOnceInsideCoordinatorTransaction() {
        Fixture fixture = fixture("bridge_waiting", true, false);
        fixture.initializeStep();
        fixture.results.bind(fixture.token,
                new CampaignRunResultStore.BindingDraft(ExecutionStatus.EMPTY,
                        new NextAction(CampaignRunResultProjection.NextActionKind.CONTINUE,
                                "RUN_NOT_STARTED", List.of())));
        AtomicInteger progressCalls = new AtomicInteger();
        AtomicInteger resultCalls = new AtomicInteger();
        CampaignJdbcDurableRunResponseBridge bridge = bridge(fixture,
                (caller, snapshot) -> {
                    progressCalls.incrementAndGet();
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    return progress(fixture.token, CampaignProgressView.WorkState.PENDING,
                            CampaignStepStore.StepStatus.PENDING);
                },
                (published, mode) -> { throw new AssertionError("report should not be read"); },
                (current, report) -> {
                    resultCalls.incrementAndGet();
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    return new CampaignRunReportReadProjection(
                            (caller, runId) -> current,
                            request -> Optional.empty()).projectPreloaded(current, report);
                });

        CampaignJdbcDurableRunResponseBridge.Outcome outcome = bridge.read(request(fixture, "plan-1"));

        assertThat(outcome.status()).isEqualTo(CampaignJdbcDurableRunResponseBridge.Outcome.Status.BOUND_RESPONSE);
        assertThat(outcome.response()).isPresent();
        assertThat(outcome.response().orElseThrow().report().availability())
                .isEqualTo(com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignLegacyAnswerAdapter.Availability.EMPTY);
        assertThat(progressCalls).hasValue(1);
        assertThat(resultCalls).hasValue(1);
    }

    @Test
    void successfulReportUsesSanitizedProjectionAndNeverLeaksLifecycleMetadata() throws Exception {
        Fixture fixture = fixture("bridge_success", true, true);
        fixture.initializeStep();
        fixture.markStepSucceeded();
        CampaignReportPublisher.ReportRef reportRef = fixture.publishReport();
        fixture.results.bind(fixture.token,
                new CampaignRunResultStore.BindingDraft(reportRef, ExecutionStatus.SUCCEEDED, NextAction.none()));
        CampaignReportReadProjection.Snapshot report = reportSnapshot();
        CampaignJdbcDurableRunResponseBridge bridge = bridge(fixture,
                (caller, snapshot) -> progress(fixture.token, CampaignProgressView.WorkState.EXECUTED,
                        CampaignStepStore.StepStatus.SUCCEEDED),
                (published, mode) -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    return report;
                },
                (current, loaded) -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    return new CampaignRunReportReadProjection(
                        (caller, runId) -> current, request -> loaded).projectPreloaded(current, loaded);
                });

        AgentRunResult response = bridge.read(request(fixture, "plan-1", reportRef)).response().orElseThrow();
        String json = new ObjectMapper().writeValueAsString(response);

        assertThat(response.report().reportRef()).isEqualTo(reportRef);
        assertThat(response.report().availability())
                .isEqualTo(com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignLegacyAnswerAdapter.Availability.COMPLETE);
        assertThat(json).doesNotContain(OWNER, CAPABILITY, "payloadJson", "manifestChecksum", "retainedUntil");
    }

    @Test
    void bindingAndDerivedProjectionMustAgree() {
        Fixture fixture = fixture("bridge_conflict", true, false);
        fixture.initializeStep();
        fixture.results.bind(fixture.token,
                new CampaignRunResultStore.BindingDraft(ExecutionStatus.EMPTY,
                        new NextAction(CampaignRunResultProjection.NextActionKind.CONTINUE,
                                "RUN_NOT_STARTED", List.of())));
        CampaignJdbcDurableRunResponseBridge bridge = bridge(fixture,
                (caller, snapshot) -> progress(fixture.token, CampaignProgressView.WorkState.RUNNING,
                        CampaignStepStore.StepStatus.RUNNING),
                (published, mode) -> { throw new AssertionError(); },
                (current, report) -> new CampaignRunReportReadProjection(
                        (caller, runId) -> current, request -> Optional.empty()).projectPreloaded(current, report));

        assertThatThrownBy(() -> bridge.read(request(fixture, "plan-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RUN_RESULT_BINDING_FACT_MISMATCH");
    }

    @Test
    void reportReferenceStillRequiresExplicitAccessBeforeProjectors() {
        Fixture fixture = fixture("bridge_access", true, true);
        fixture.initializeStep();
        CampaignReportPublisher.ReportRef reportRef = fixture.publishReport();
        fixture.results.bind(fixture.token,
                new CampaignRunResultStore.BindingDraft(reportRef, ExecutionStatus.WAITING,
                        new NextAction(CampaignRunResultProjection.NextActionKind.WAIT, "RESULT_PENDING", List.of())));
        CampaignJdbcDurableRunResponseBridge bridge = bridge(fixture,
                (caller, snapshot) -> { throw new AssertionError(); },
                (published, mode) -> { throw new AssertionError(); },
                (current, report) -> { throw new AssertionError(); });

        assertThatThrownBy(() -> bridge.read(request(fixture, "plan-1")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("RUN_RESULT_REPORT_ACCESS_REQUIRED");
    }

    private static CampaignJdbcDurableRunResponseBridge bridge(Fixture fixture,
                                                                 CampaignJdbcDurableRunResponseBridge.ProgressProjector progress,
                                                                 CampaignJdbcDurableRunResponseBridge.ReportSnapshotProjector report,
                                                                 CampaignJdbcDurableRunResponseBridge.ResultProjector result) {
        return new CampaignJdbcDurableRunResponseBridge(fixture.coordinator, progress, report, result);
    }

    private static CampaignJdbcDurableRunResponseBridge.Request request(Fixture fixture, String planId) {
        return request(fixture, planId, null);
    }

    private static CampaignJdbcDurableRunResponseBridge.Request request(Fixture fixture, String planId,
                                                                          CampaignReportPublisher.ReportRef report) {
        Optional<JdbcCampaignDurableRunReadCoordinator.ReportAccess> access = report == null
                ? Optional.empty()
                : Optional.of(new JdbcCampaignDurableRunReadCoordinator.ReportAccess(
                        OWNER, CAPABILITY, ReportLifecycleStore.Mode.HISTORY_VIEW));
        return new CampaignJdbcDurableRunResponseBridge.Request(
                new AgentRunResult("session-1", "trace-1", "legacy", List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                new JdbcCampaignDurableRunReadCoordinator.Request(CALLER, "run-1", 1, access), planId);
    }

    private static CampaignProgressView progress(RunToken token, CampaignProgressView.WorkState work,
                                                  CampaignStepStore.StepStatus status) {
        CampaignProgressView.GoalProgress goal = new CampaignProgressView.GoalProgress(
                "goal-1", "question", true, work, List.of("step-1"), List.of(), List.of(), List.of());
        CampaignProgressView.StepProgress step = new CampaignProgressView.StepProgress(
                "step-1", List.of("goal-1"), status, work, null, List.of(), List.of(), List.of());
        return new CampaignProgressView(CampaignProgressView.SCHEMA, token.definition().runId(),
                token.definition().planId(), token.definition().revision(),
                com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus.ACTIVE,
                work, CampaignProgressView.DeliveryState.NOT_ASSESSED, List.of(goal), List.of(step));
    }

    private static CampaignReportReadProjection.Snapshot reportSnapshot() {
        ReportBlock block = new ReportBlock("metric-1", ReportBlock.Kind.METRIC, "Visits", null,
                Map.of("value", 12), List.of("artifact-1"), true);
        ReportDraft draft = new ReportDraft("report-1", 1, "run-1", "plan-1", 1,
                List.of(new ReportSection("summary", 0, "Summary", List.of("goal-1"), List.of(block))), List.of());
        GoalAssessment goal = new GoalAssessment("goal-1", GoalAssessment.Status.ANSWERED, null,
                List.of("artifact-1"), List.of(), List.of());
        return new CampaignReportReadProjection.Snapshot(CampaignReportReadProjection.SCHEMA,
                ReportLifecycleStore.Mode.HISTORY_VIEW, new CampaignReportPublisher.ReportRef("report-1", 1),
                "run-1", 1, draft, List.of(goal), "a".repeat(64),
                NOW.plusSeconds(3600), NOW.plusSeconds(1800), NOW.plusSeconds(2400));
    }

    private static Fixture fixture(String name, boolean withSteps, boolean withReport) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + name + "_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260921__campaign_run_result_binding.sql"));
        if (withSteps) {
            populator.addScript(new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"));
            populator.addScript(new ClassPathResource("sql/migration/V20260920__campaign_statistics_result.sql"));
        }
        if (withReport) populator.addScript(new ClassPathResource("sql/migration/V20260920_22__campaign_report_lifecycle.sql"));
        populator.execute(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        JdbcCampaignRunStore runs = new JdbcCampaignRunStore(jdbc, transactions, CLOCK);
        RunToken token = runs.createRun(new com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition(
                CALLER, "session-1", "run-1", "plan-1", 1, "{}"));
        JdbcCampaignRunResultStore results = new JdbcCampaignRunResultStore(jdbc, transactions, CLOCK,
                (definition, report) -> true);
        JdbcCampaignResultProgressReader progress = new JdbcCampaignResultProgressReader(jdbc, transactions, CLOCK);
        JdbcReportLifecycleStore reports = new JdbcReportLifecycleStore(jdbc, transactions, CLOCK);
        JdbcCampaignDurableRunReadCoordinator coordinator = new JdbcCampaignDurableRunReadCoordinator(
                results, progress, reports, jdbc, transactions);
        return new Fixture(source, jdbc, transactions, runs, results, reports, coordinator, token);
    }

    private record Fixture(DataSource source, JdbcTemplate jdbc, TransactionTemplate transactions,
                           JdbcCampaignRunStore runs, JdbcCampaignRunResultStore results,
                           JdbcReportLifecycleStore reports,
                           JdbcCampaignDurableRunReadCoordinator coordinator, RunToken token) {
        void initializeStep() {
            new JdbcCampaignStepStore(jdbc, transactions, CLOCK).initialize(token,
                    List.of(new CampaignStepStore.StepSpec("step-1", "{}", List.of(), Set.of(), Set.of())));
        }

        void markStepSucceeded() {
            jdbc.update("UPDATE campaign_step_ledger SET step_status='SUCCEEDED' WHERE run_id=? AND revision=? AND step_id=?",
                    token.definition().runId(), token.definition().revision(), "step-1");
        }

        CampaignReportPublisher.ReportRef publishReport() {
            ReportLifecycleStore.Key key = new ReportLifecycleStore.Key("report-1", 1);
            reports.publish(new ReportLifecycleStore.Draft(key, "run-1", 1, OWNER, CAPABILITY,
                    "{}", "a".repeat(64), NOW.plusSeconds(3600), NOW.plusSeconds(1800),
                    NOW.plusSeconds(2400), "{}"));
            return new CampaignReportPublisher.ReportRef(key.reportId(), key.revision());
        }
    }
}
