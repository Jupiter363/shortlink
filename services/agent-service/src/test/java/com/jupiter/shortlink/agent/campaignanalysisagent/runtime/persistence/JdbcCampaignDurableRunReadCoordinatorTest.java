package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher.ReportRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore.Binding;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore.BindingDraft;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignDurableRunReadSnapshot;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.ExecutionStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.NextAction;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.JdbcReportLifecycleStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcCampaignDurableRunReadCoordinatorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC);
    private static final Caller CALLER = new Caller("tenant-1", "subject-1", 1);
    private static final String REPORT_OWNER = "report-owner";
    private static final String REPORT_CAPABILITY = "campaign/report/v1";

    @Test
    void missingBindingDoesNotReadProgressOrReport() {
        Fixture fixture = fixture("coordinator_no_binding", false, false);

        JdbcCampaignDurableRunReadCoordinator.Outcome outcome = fixture.coordinator.read(
                new JdbcCampaignDurableRunReadCoordinator.Request(CALLER, "run-1", 1));

        assertThat(outcome.status()).isEqualTo(JdbcCampaignDurableRunReadCoordinator.Outcome.Status.NO_BINDING);
        assertThat(outcome.snapshot()).isEmpty();
    }

    @Test
    void boundReadUsesExactProgressFactsAndCurrentRunFence() {
        Fixture fixture = fixture("coordinator_bound", true, false);
        fixture.initializeStep();
        Binding expected = fixture.results.bind(fixture.token,
                new BindingDraft(ExecutionStatus.EMPTY,
                        new NextAction(com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.NextActionKind.CONTINUE,
                                "RUN_NOT_STARTED", List.of())));

        JdbcCampaignDurableRunReadCoordinator.Outcome outcome = fixture.coordinator.read(
                new JdbcCampaignDurableRunReadCoordinator.Request(CALLER, "run-1", 1));

        CampaignDurableRunReadSnapshot snapshot = outcome.snapshot().orElseThrow();
        assertThat(outcome.status()).isEqualTo(JdbcCampaignDurableRunReadCoordinator.Outcome.Status.BOUND);
        assertThat(snapshot.binding()).isEqualTo(expected);
        assertThat(snapshot.progress().execution().run().definition().revision()).isOne();
        assertThat(snapshot.progress().execution().steps()).extracting(CampaignStepStore.StepRecord::status)
                .containsExactly(CampaignStepStore.StepStatus.PENDING);
        assertThat(snapshot.report()).isEmpty();

        fixture.runs.advance(fixture.token);
        assertThatThrownBy(() -> fixture.coordinator.read(
                new JdbcCampaignDurableRunReadCoordinator.Request(CALLER, "run-1", 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RUN_RESULT_READ_CONFLICT");
    }

    @Test
    void reportReferenceRequiresAccessAndReturnsOnlyIdentity() {
        Fixture fixture = fixture("coordinator_report", true, true);
        fixture.initializeStep();
        ReportRef report = fixture.publishReport();
        Binding expected = fixture.results.bind(fixture.token,
                new BindingDraft(report, ExecutionStatus.SUCCEEDED, NextAction.none()));

        assertThatThrownBy(() -> fixture.coordinator.read(
                new JdbcCampaignDurableRunReadCoordinator.Request(CALLER, "run-1", 1)))
                .isInstanceOf(SecurityException.class)
                .hasMessage("RUN_RESULT_REPORT_ACCESS_REQUIRED");

        JdbcCampaignDurableRunReadCoordinator.Outcome outcome = fixture.coordinator.read(
                new JdbcCampaignDurableRunReadCoordinator.Request(CALLER, "run-1", 1,
                        Optional.of(new JdbcCampaignDurableRunReadCoordinator.ReportAccess(
                                REPORT_OWNER, REPORT_CAPABILITY, ReportLifecycleStore.Mode.HISTORY_VIEW))));

        CampaignDurableRunReadSnapshot snapshot = outcome.snapshot().orElseThrow();
        assertThat(snapshot.binding()).isEqualTo(expected);
        assertThat(snapshot.report()).get().extracting(CampaignDurableRunReadSnapshot.ReportIdentity::reportRef)
                .isEqualTo(report);
    }

    @Test
    void constructorRequiresOneSharedWritableTransaction() {
        Fixture fixture = fixture("coordinator_guard", true, false);
        TransactionTemplate other = new TransactionTemplate(new DataSourceTransactionManager(fixture.source));

        assertThatThrownBy(() -> new JdbcCampaignDurableRunReadCoordinator(fixture.results, fixture.progress,
                fixture.reports, fixture.jdbc, other))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Durable run reads require one writable REQUIRED DataSource transaction");
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
        RunToken token = runs.createRun(new RunDefinition(CALLER, "session-1", "run-1", "plan-1", 1, "{}"));
        JdbcCampaignRunResultStore results = new JdbcCampaignRunResultStore(jdbc, transactions, CLOCK,
                (definition, report) -> true);
        JdbcCampaignResultProgressReader progress = new JdbcCampaignResultProgressReader(jdbc, transactions, CLOCK);
        JdbcReportLifecycleStore reports = new JdbcReportLifecycleStore(jdbc, transactions, CLOCK);
        JdbcCampaignDurableRunReadCoordinator coordinator = new JdbcCampaignDurableRunReadCoordinator(
                results, progress, reports, jdbc, transactions);
        return new Fixture(source, jdbc, transactions, runs, results, progress, reports, coordinator, token);
    }

    private record Fixture(DataSource source, JdbcTemplate jdbc, TransactionTemplate transactions,
                           JdbcCampaignRunStore runs, JdbcCampaignRunResultStore results,
                           JdbcCampaignResultProgressReader progress, JdbcReportLifecycleStore reports,
                           JdbcCampaignDurableRunReadCoordinator coordinator, RunToken token) {
        void initializeStep() {
            new JdbcCampaignStepStore(jdbc, transactions, CLOCK).initialize(token,
                    List.of(new CampaignStepStore.StepSpec("step-1", "{}", List.of(), Set.of(), Set.of())));
        }

        ReportRef publishReport() {
            ReportLifecycleStore.Key key = new ReportLifecycleStore.Key("report-1", 1);
            reports.publish(new ReportLifecycleStore.Draft(key, "run-1", 1, REPORT_OWNER, REPORT_CAPABILITY,
                    "{}", "a".repeat(64), Instant.ofEpochMilli(100_000), Instant.ofEpochMilli(90_000),
                    Instant.ofEpochMilli(80_000), "{}"));
            return new ReportRef(key.reportId(), key.revision());
        }
    }
}
