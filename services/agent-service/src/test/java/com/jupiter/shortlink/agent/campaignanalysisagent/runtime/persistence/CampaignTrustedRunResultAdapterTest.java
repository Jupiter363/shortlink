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

class CampaignTrustedRunResultAdapterTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC);

    @Test
    void resolvesReportCredentialsPerRequestInsteadOfSharingOneIdentity() {
        Fixture fixture = fixture("trusted_result_adapter_identity");
        ReportRef report = fixture.publishReport("owner-a", "cap-a");
        BindingDraft draft = new BindingDraft(report, ExecutionStatus.SUCCEEDED, NextAction.none());

        assertThatThrownBy(() -> fixture.adapter.bind(fixture.caller, fixture.token, draft,
                "owner-b", "cap-b"))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPORT_ACCESS_DENIED");

        Binding accepted = fixture.adapter.bind(fixture.caller, fixture.token, draft, "owner-a", "cap-a");
        assertThat(accepted.reportRef()).isEqualTo(report);
        assertThat(fixture.jdbc.queryForObject(
                "SELECT reference_count FROM campaign_report_lifecycle WHERE report_id=? AND revision=?",
                Integer.class, report.reportId(), report.revision())).isEqualTo(1);
    }

    @Test
    void rejectsCallerThatDoesNotOwnTheRunBeforeAnyReportRead() {
        Fixture fixture = fixture("trusted_result_adapter_caller");
        ReportRef report = fixture.publishReport("owner-a", "cap-a");
        Caller foreign = new Caller("tenant-foreign", "subject-1", 1);

        assertThatThrownBy(() -> fixture.adapter.bind(foreign, fixture.token,
                new BindingDraft(report, ExecutionStatus.SUCCEEDED, NextAction.none()),
                "owner-a", "cap-a"))
                .isInstanceOf(SecurityException.class)
                .hasMessage("RUN_RESULT_ACCESS_DENIED");
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_reference", Integer.class))
                .isZero();
    }

    @Test
    void staleRequestRollsBackRetainThroughTheSharedCoordinator() {
        Fixture fixture = fixture("trusted_result_adapter_rollback");
        ReportRef report = fixture.publishReport("owner-a", "cap-a");
        RunToken stale = fixture.token;
        fixture.runs.advance(stale);

        assertThatThrownBy(() -> fixture.adapter.bind(fixture.caller, stale,
                new BindingDraft(report, ExecutionStatus.SUCCEEDED, NextAction.none()),
                "owner-a", "cap-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RUN_RESULT_TOKEN_FENCED");
        assertThat(fixture.jdbc.queryForObject(
                "SELECT reference_count FROM campaign_report_lifecycle WHERE report_id=? AND revision=?",
                Integer.class, report.reportId(), report.revision())).isZero();
    }

    @Test
    void releaseUsesTheSameRequestBoundCredentials() {
        Fixture fixture = fixture("trusted_result_adapter_release");
        ReportRef report = fixture.publishReport("owner-a", "cap-a");
        fixture.adapter.bind(fixture.caller, fixture.token,
                new BindingDraft(report, ExecutionStatus.SUCCEEDED, NextAction.none()),
                "owner-a", "cap-a");

        assertThatThrownBy(() -> fixture.adapter.release(fixture.caller, fixture.token, report,
                "owner-b", "cap-b"))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPORT_ACCESS_DENIED");
        assertThat(fixture.jdbc.queryForObject(
                "SELECT reference_count FROM campaign_report_lifecycle WHERE report_id=? AND revision=?",
                Integer.class, report.reportId(), report.revision())).isEqualTo(1);

        fixture.adapter.release(fixture.caller, fixture.token, report, "owner-a", "cap-a");
        assertThat(fixture.jdbc.queryForObject(
                "SELECT reference_count FROM campaign_report_lifecycle WHERE report_id=? AND revision=?",
                Integer.class, report.reportId(), report.revision())).isZero();
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
        CampaignTrustedRunResultAdapter adapter =
                new CampaignTrustedRunResultAdapter(jdbc, reports, transactions, CLOCK);
        return new Fixture(source, jdbc, transactions, runs, reports, adapter, caller, token);
    }

    private record Fixture(DataSource source, JdbcTemplate jdbc, TransactionTemplate transactions,
                           JdbcCampaignRunStore runs, JdbcReportLifecycleStore reports,
                           CampaignTrustedRunResultAdapter adapter, Caller caller, RunToken token) {
        ReportRef publishReport(String owner, String capability) {
            ReportLifecycleStore.Key key = new ReportLifecycleStore.Key("report-1", 1);
            reports.publish(new ReportLifecycleStore.Draft(key, "run-1", 1, owner, capability,
                    "{}", "a".repeat(64), Instant.ofEpochMilli(100_000),
                    Instant.ofEpochMilli(90_000), Instant.ofEpochMilli(80_000), "payload"));
            return new ReportRef(key.reportId(), key.revision());
        }
    }
}
