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

class JdbcCampaignRunResultBindingCoordinatorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC);
    private static final String OWNER = "owner";
    private static final String CAPABILITY = "capability";

    @Test
    void bindsAndRetainsReportInOneTransactionThenReleasesIt() {
        Fixture fixture = fixture("binding_coordinator_success");
        ReportRef report = fixture.publishReport();

        Binding binding = fixture.coordinator.bind(fixture.token,
                new BindingDraft(report, ExecutionStatus.SUCCEEDED, NextAction.none()));

        assertThat(binding.reportRef()).isEqualTo(report);
        assertThat(fixture.jdbc.queryForObject(
                "SELECT reference_count FROM campaign_report_lifecycle WHERE report_id=? AND revision=?",
                Integer.class, report.reportId(), report.revision())).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_reference", Integer.class))
                .isEqualTo(1);

        fixture.coordinator.release(fixture.token, report);
        assertThat(fixture.jdbc.queryForObject(
                "SELECT reference_count FROM campaign_report_lifecycle WHERE report_id=? AND revision=?",
                Integer.class, report.reportId(), report.revision())).isEqualTo(0);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_reference", Integer.class))
                .isZero();
    }

    @Test
    void staleBindingRollsBackTheReportRetain() {
        Fixture fixture = fixture("binding_coordinator_rollback");
        ReportRef report = fixture.publishReport();
        RunToken stale = fixture.token;
        fixture.runs.advance(stale);

        assertThatThrownBy(() -> fixture.coordinator.bind(stale,
                new BindingDraft(report, ExecutionStatus.SUCCEEDED, NextAction.none())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RUN_RESULT_TOKEN_FENCED");
        assertThat(fixture.jdbc.queryForObject(
                "SELECT reference_count FROM campaign_report_lifecycle WHERE report_id=? AND revision=?",
                Integer.class, report.reportId(), report.revision())).isZero();
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_reference", Integer.class))
                .isZero();
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_run_result_binding", Integer.class))
                .isZero();
    }

    @Test
    void staleReleaseCannotDropAnActiveBindingReference() {
        Fixture fixture = fixture("binding_coordinator_release_fence");
        ReportRef report = fixture.publishReport();
        fixture.coordinator.bind(fixture.token,
                new BindingDraft(report, ExecutionStatus.SUCCEEDED, NextAction.none()));
        RunToken stale = fixture.token;
        fixture.runs.advance(stale);

        assertThatThrownBy(() -> fixture.coordinator.release(stale, report))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RUN_RESULT_TOKEN_FENCED");
        assertThat(fixture.jdbc.queryForObject(
                "SELECT reference_count FROM campaign_report_lifecycle WHERE report_id=? AND revision=?",
                Integer.class, report.reportId(), report.revision())).isEqualTo(1);
    }

    @Test
    void releaseRequiresTheReportRecordedByTheBinding() {
        Fixture fixture = fixture("binding_coordinator_release_identity");
        ReportRef report = fixture.publishReport();
        fixture.coordinator.bind(fixture.token,
                new BindingDraft(report, ExecutionStatus.SUCCEEDED, NextAction.none()));

        assertThatThrownBy(() -> fixture.coordinator.release(fixture.token, new ReportRef("other", 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RUN_RESULT_REPORT_REF_MISMATCH");
        assertThat(fixture.jdbc.queryForObject(
                "SELECT reference_count FROM campaign_report_lifecycle WHERE report_id=? AND revision=?",
                Integer.class, report.reportId(), report.revision())).isEqualTo(1);
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
        CampaignRunReportBindingVerifier verifier =
                new CampaignRunReportBindingVerifier(reports, OWNER, CAPABILITY);
        JdbcCampaignRunResultStore results =
                new JdbcCampaignRunResultStore(jdbc, transactions, CLOCK, verifier);
        JdbcCampaignRunResultBindingCoordinator coordinator =
                new JdbcCampaignRunResultBindingCoordinator(
                        results, reports, jdbc, transactions, OWNER, CAPABILITY);
        return new Fixture(source, jdbc, transactions, runs, reports, coordinator, caller, token);
    }

    private record Fixture(DataSource source, JdbcTemplate jdbc, TransactionTemplate transactions,
                           JdbcCampaignRunStore runs, JdbcReportLifecycleStore reports,
                           JdbcCampaignRunResultBindingCoordinator coordinator,
                           Caller caller, RunToken token) {
        ReportRef publishReport() {
            ReportLifecycleStore.Key key = new ReportLifecycleStore.Key("report-1", 1);
            reports.publish(new ReportLifecycleStore.Draft(key, "run-1", 1, OWNER, CAPABILITY,
                    "{}", "a".repeat(64), Instant.ofEpochMilli(100_000),
                    Instant.ofEpochMilli(90_000), Instant.ofEpochMilli(80_000), "payload"));
            return new ReportRef(key.reportId(), key.revision());
        }
    }
}
