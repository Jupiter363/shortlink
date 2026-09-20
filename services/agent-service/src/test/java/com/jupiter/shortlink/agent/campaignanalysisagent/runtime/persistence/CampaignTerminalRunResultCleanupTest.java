package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher.ReportRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore.BindingDraft;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.ExecutionStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.NextAction;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.NextActionKind;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.CampaignRunReportBindingVerifier;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.JdbcReportLifecycleStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class CampaignTerminalRunResultCleanupTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC);
    private static final String OWNER = "owner";
    private static final String CAPABILITY = "capability";

    @Test
    void releasesAnExpiredReportAfterCancellationAndIsIdempotent() {
        Fixture fixture = fixture("terminal_cleanup_cancelled");
        ReportRef report = fixture.publishReport();
        fixture.coordinator.bind(fixture.token,
                new BindingDraft(report, ExecutionStatus.SUCCEEDED, NextAction.none()));
        fixture.jdbc.update("UPDATE campaign_report_lifecycle SET retained_until=1 WHERE report_id=? AND revision=?",
                report.reportId(), report.revision());
        assertThatThrownBy(() -> fixture.reports.read(new ReportLifecycleStore.Key(report.reportId(), report.revision()),
                OWNER, CAPABILITY, ReportLifecycleStore.Mode.HISTORY_VIEW))
                .hasMessage("REPORT_DATA_EXPIRED");

        fixture.runs.cancel(fixture.token);
        CampaignTerminalRunResultCleanup.CleanupOutcome first = fixture.cleanup.cleanup(
                fixture.caller, "run-1", 1, report);
        assertThat(first.bindingPresent()).isTrue();
        assertThat(first.runStatus()).isEqualTo(CampaignRunStore.RunStatus.CANCELLED);
        assertThat(first.reportPresent()).isTrue();
        assertThat(first.referenceRemoved()).isTrue();
        assertThat(fixture.referenceCount(report)).isZero();

        CampaignTerminalRunResultCleanup.CleanupOutcome second = fixture.cleanup.cleanup(
                fixture.caller, "run-1", 1, report);
        assertThat(second.reportPresent()).isTrue();
        assertThat(second.referenceRemoved()).isFalse();
        assertThat(fixture.referenceCount(report)).isZero();
    }

    @Test
    void cleansUpWhenTheLifecycleRowWasAlreadyDeleted() {
        Fixture fixture = fixture("terminal_cleanup_missing_report");
        ReportRef report = fixture.publishReport();
        fixture.coordinator.bind(fixture.token,
                new BindingDraft(report, ExecutionStatus.SUCCEEDED, NextAction.none()));
        fixture.runs.cancel(fixture.token);
        fixture.jdbc.update("DELETE FROM campaign_report_reference WHERE report_id=? AND revision=?",
                report.reportId(), report.revision());
        fixture.jdbc.update("DELETE FROM campaign_report_lifecycle WHERE report_id=? AND revision=?",
                report.reportId(), report.revision());

        CampaignTerminalRunResultCleanup.CleanupOutcome result = fixture.cleanup.cleanup(
                fixture.caller, "run-1", 1, report);
        assertThat(result.bindingPresent()).isTrue();
        assertThat(result.reportPresent()).isFalse();
        assertThat(result.referenceRemoved()).isFalse();
        assertThat(fixture.cleanup.cleanup(fixture.caller, "run-1", 1, report))
                .isEqualTo(result);
    }

    @Test
    void rejectsAnActiveRunBeforeTouchingReferences() {
        Fixture fixture = fixture("terminal_cleanup_active");
        ReportRef report = fixture.publishReport();
        fixture.coordinator.bind(fixture.token,
                new BindingDraft(report, ExecutionStatus.SUCCEEDED, NextAction.none()));

        assertThatThrownBy(() -> fixture.cleanup.cleanup(fixture.caller, "run-1", 1, report))
                .hasMessage("RUN_RESULT_CLEANUP_RUN_ACTIVE");
        assertThat(fixture.referenceCount(report)).isEqualTo(1);
    }

    @Test
    void rejectsAForeignCallerAndAReportIdentityMismatch() {
        Fixture fixture = fixture("terminal_cleanup_authorization");
        ReportRef report = fixture.publishReport();
        fixture.coordinator.bind(fixture.token,
                new BindingDraft(report, ExecutionStatus.SUCCEEDED, NextAction.none()));
        fixture.runs.cancel(fixture.token);

        assertThatThrownBy(() -> fixture.cleanup.cleanup(new Caller("tenant-2", "subject-1", 1),
                "run-1", 1, report)).hasMessage("RUN_RESULT_ACCESS_DENIED");
        assertThatThrownBy(() -> fixture.cleanup.cleanup(fixture.caller, "run-1", 1,
                new ReportRef("other", 1))).hasMessage("RUN_RESULT_REPORT_REF_MISMATCH");
        assertThat(fixture.referenceCount(report)).isEqualTo(1);
    }

    @Test
    void cleansSupersededRevisionAndBindingWithoutAReport() {
        Fixture fixture = fixture("terminal_cleanup_superseded");
        fixture.coordinator.bind(fixture.token,
                new BindingDraft(ExecutionStatus.WAITING,
                        new NextAction(NextActionKind.WAIT, "WAITING_CHILD", java.util.List.of("child"))));
        RunToken next = fixture.runs.revise(fixture.token, 2, "{\"revision\":2}");

        CampaignTerminalRunResultCleanup.CleanupOutcome result = fixture.cleanup.cleanup(
                fixture.caller, "run-1", 1, null);
        assertThat(result.bindingPresent()).isTrue();
        assertThat(result.runStatus()).isEqualTo(CampaignRunStore.RunStatus.SUPERSEDED);
        assertThat(result.reportRef()).isNull();
        assertThat(next.definition().revision()).isEqualTo(2);
    }

    private static Fixture fixture(String name) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + name + "_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(
                new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260920_22__campaign_report_lifecycle.sql"),
                new ClassPathResource("sql/migration/V20260921__campaign_run_result_binding.sql"))
                .execute(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        JdbcCampaignRunStore runs = new JdbcCampaignRunStore(jdbc, transactions, CLOCK);
        Caller caller = new Caller("tenant-1", "subject-1", 1);
        RunDefinition definition = new RunDefinition(caller, "session-1", "run-1", "plan-1", 1, "{}");
        RunToken token = runs.createRun(definition);
        JdbcReportLifecycleStore reports = new JdbcReportLifecycleStore(jdbc, transactions, CLOCK);
        CampaignRunReportBindingVerifier verifier = new CampaignRunReportBindingVerifier(reports, OWNER, CAPABILITY);
        JdbcCampaignRunResultStore results = new JdbcCampaignRunResultStore(jdbc, transactions, CLOCK, verifier);
        JdbcCampaignRunResultBindingCoordinator coordinator = new JdbcCampaignRunResultBindingCoordinator(
                results, reports, jdbc, transactions, OWNER, CAPABILITY);
        CampaignTerminalRunResultCleanup cleanup = new CampaignTerminalRunResultCleanup(
                results, reports, jdbc, transactions);
        return new Fixture(jdbc, runs, reports, coordinator, cleanup, caller, token);
    }

    private record Fixture(JdbcTemplate jdbc, JdbcCampaignRunStore runs, JdbcReportLifecycleStore reports,
                           JdbcCampaignRunResultBindingCoordinator coordinator,
                           CampaignTerminalRunResultCleanup cleanup, Caller caller, RunToken token) {
        ReportRef publishReport() {
            ReportLifecycleStore.Key key = new ReportLifecycleStore.Key("report-1", 1);
            reports.publish(new ReportLifecycleStore.Draft(key, "run-1", 1, OWNER, CAPABILITY,
                    "{}", "a".repeat(64), Instant.ofEpochMilli(100_000),
                    Instant.ofEpochMilli(90_000), Instant.ofEpochMilli(80_000), "payload"));
            return new ReportRef(key.reportId(), key.revision());
        }

        int referenceCount(ReportRef report) {
            return jdbc.queryForObject("SELECT reference_count FROM campaign_report_lifecycle WHERE report_id=? AND revision=?",
                    Integer.class, report.reportId(), report.revision());
        }
    }
}
