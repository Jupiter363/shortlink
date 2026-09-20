package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportApplicationService;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher.ReportRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.GoalAssessor;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportBlock;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportDraft;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportSection;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.RequirementAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore.BindingDraft;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignTrustedRunResultAdapter;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.ExecutionStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.NextAction;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.NextActionKind;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class CampaignRunReportPublicationCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String OWNER = "owner";
    private static final String CAPABILITY = "report/v1";

    @Test
    void publishesAndBindsTheReportInOneTransaction() {
        Fixture fixture = fixture("publication_success", true);

        CampaignRunReportPublicationCoordinator.PublicationResult result = fixture.coordinator.publishAndBind(
                fixture.caller, fixture.token, fixture.publication(), fixture.binding());

        assertThat(result.reportRef()).isEqualTo(new ReportRef("report-1", 1));
        assertThat(result.binding().reportRef()).isEqualTo(new ReportRef("report-1", 1));
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_lifecycle", Integer.class))
                .isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT reference_count FROM campaign_report_lifecycle",
                Integer.class)).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_reference", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void staleBindingRollsBackTheNewlyPublishedReport() {
        Fixture fixture = fixture("publication_rollback", true);
        RunToken stale = fixture.token;
        fixture.runs.advance(stale);

        assertThatThrownBy(() -> fixture.coordinator.publishAndBind(
                fixture.caller, stale, fixture.publication(), fixture.binding()))
                .hasMessage("RUN_RESULT_TOKEN_FENCED");
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_lifecycle", Integer.class))
                .isZero();
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_run_result_binding", Integer.class))
                .isZero();
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_reference", Integer.class))
                .isZero();
    }

    @Test
    void exactReplayDoesNotRetainTheSameReferenceTwice() {
        Fixture fixture = fixture("publication_replay", true);
        CampaignRunReportPublicationCoordinator.PublicationResult first = fixture.coordinator.publishAndBind(
                fixture.caller, fixture.token, fixture.publication(), fixture.binding());
        CampaignRunReportPublicationCoordinator.PublicationResult second = fixture.coordinator.publishAndBind(
                fixture.caller, fixture.token, fixture.publication(), fixture.binding());

        assertThat(second.reportRef()).isEqualTo(first.reportRef());
        assertThat(second.binding()).isEqualTo(first.binding());
        assertThat(fixture.jdbc.queryForObject("SELECT reference_count FROM campaign_report_lifecycle",
                Integer.class)).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_reference", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void exactReplayStillRequiresCurrentReportCredentials() {
        Fixture fixture = fixture("publication_replay_auth", true);
        fixture.coordinator.publishAndBind(fixture.caller, fixture.token, fixture.publication(), fixture.binding());

        assertThatThrownBy(() -> fixture.resultAdapter.bind(fixture.caller, fixture.token, fixture.binding(),
                "revoked-owner", CAPABILITY)).hasMessage("REPORT_ACCESS_DENIED");
        assertThat(fixture.jdbc.queryForObject("SELECT reference_count FROM campaign_report_lifecycle",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void authorizationFailureDoesNotPublishAnything() {
        Fixture fixture = fixture("publication_denied", false);

        assertThatThrownBy(() -> fixture.coordinator.publishAndBind(
                fixture.caller, fixture.token, fixture.publication(), fixture.binding()))
                .hasMessage("REPORT_ACCESS_DENIED");
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_lifecycle", Integer.class))
                .isZero();
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_run_result_binding", Integer.class))
                .isZero();
    }

    @Test
    void mismatchedBindingReferenceFailsBeforePublication() {
        Fixture fixture = fixture("publication_identity", true);
        BindingDraft wrong = new BindingDraft(new ReportRef("other", 1), ExecutionStatus.SUCCEEDED, NextAction.none());

        assertThatThrownBy(() -> fixture.coordinator.publishAndBind(
                fixture.caller, fixture.token, fixture.publication(), wrong))
                .hasMessage("RUN_RESULT_REPORT_REF_MISMATCH");
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_lifecycle", Integer.class))
                .isZero();
    }

    @Test
    void completeReportCannotBeBoundAsWaiting() {
        Fixture fixture = fixture("publication_status", true);
        BindingDraft waiting = new BindingDraft(new ReportRef("report-1", 1), ExecutionStatus.WAITING,
                new NextAction(NextActionKind.WAIT, "WAITING_FOR_INPUT", List.of()));

        assertThatThrownBy(() -> fixture.coordinator.publishAndBind(
                fixture.caller, fixture.token, fixture.publication(), waiting))
                .hasMessage("RUN_RESULT_REPORT_STATUS_MISMATCH");
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_lifecycle", Integer.class))
                .isZero();
    }

    @Test
    void bindingConflictAfterPublicationRollsBackTheNewReport() {
        Fixture fixture = fixture("publication_bind_conflict", true);
        fixture.jdbc.update("INSERT INTO campaign_run_result_binding (run_id,revision,report_id,report_revision,"
                        + "execution_status,next_action_kind,next_action_reason,required_inputs_json,limitations_json,"
                        + "source_row_version,source_advance_token,binding_version,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "run-1", 1, "old-report", 1, "WAITING", "WAIT", "WAITING_FOR_INPUT", "[]", "[]",
                fixture.token.version(), fixture.token.advanceToken(), 1L, NOW.toEpochMilli(), NOW.toEpochMilli());

        assertThatThrownBy(() -> fixture.coordinator.publishAndBind(
                fixture.caller, fixture.token, fixture.publication(), fixture.binding()))
                .hasMessage("RUN_RESULT_BINDING_CONFLICT");
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_lifecycle", Integer.class))
                .isZero();
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_reference", Integer.class))
                .isZero();
    }

    private static Fixture fixture(String name, boolean authorize) {
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
        JdbcReportLifecycleStore lifecycle = new JdbcReportLifecycleStore(jdbc, transactions, CLOCK);
        CampaignReportPublisher publisher = new CampaignReportPublisher(new GoalAssessor(), id -> Optional.of(
                new CampaignReportPublisher.Evidence(id, "stats", "stats/v1", "checksum-" + id,
                        NOW.plusSeconds(3_600), "READ_RESULT", true)), CLOCK, CAPABILITY, lifecycle);
        CampaignReportApplicationService application = new CampaignReportApplicationService(
                publisher, (owner, capability, operation) -> authorize);
        CampaignTrustedRunResultAdapter resultAdapter = new CampaignTrustedRunResultAdapter(
                jdbc, lifecycle, transactions, CLOCK);
        CampaignRunReportPublicationCoordinator coordinator = new CampaignRunReportPublicationCoordinator(
                application, resultAdapter, lifecycle, jdbc, transactions);
        return new Fixture(jdbc, runs, resultAdapter, coordinator, caller, token);
    }

    private record Fixture(JdbcTemplate jdbc, JdbcCampaignRunStore runs,
                           CampaignTrustedRunResultAdapter resultAdapter,
                           CampaignRunReportPublicationCoordinator coordinator,
                           Caller caller, RunToken token) {
        CampaignReportApplicationService.PublishRequest publication() {
            PlanSpec plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "run-1", "inputs-1",
                    List.of(new PlanSpec.Goal("goal-1", "Compare visits", true, "Deliver result")), List.of());
            PlanningAssessment assessment = new PlanningAssessment("plan-1", 1, "catalog-v1", List.of(
                    requirement("data-1", PlanningAssessment.RequirementKind.DATA),
                    requirement("delivery-1", PlanningAssessment.RequirementKind.DELIVERY)), List.of(), List.of());
            ReportDraft draft = new ReportDraft("report-1", 1, "run-1", "plan-1", 1,
                    List.of(new ReportSection("summary", 0, "Summary", List.of("goal-1"), List.of(
                            new ReportBlock("metric", ReportBlock.Kind.METRIC, "Visits", null,
                                    Map.of("value", 12), List.of("artifact-1"), true)))), List.of());
            Map<String, GoalAssessor.RequirementObservation> observations = Map.of(
                    "data-1", new GoalAssessor.RequirementObservation(RequirementAssessment.Verdict.MET,
                            null, List.of("artifact-1")),
                    "delivery-1", new GoalAssessor.RequirementObservation(RequirementAssessment.Verdict.MET,
                            null, List.of("artifact-1")));
            CampaignReportPublisher.PublishRequest publisherRequest = new CampaignReportPublisher.PublishRequest(
                    plan, assessment, observations, draft, CAPABILITY);
            return new CampaignReportApplicationService.PublishRequest(publisherRequest, OWNER, CAPABILITY,
                    NOW.plusSeconds(1_800), NOW.plusSeconds(2_400));
        }

        BindingDraft binding() {
            return new BindingDraft(new ReportRef("report-1", 1), ExecutionStatus.SUCCEEDED, NextAction.none());
        }

        private static PlanningAssessment.Requirement requirement(String id, PlanningAssessment.RequirementKind kind) {
            return new PlanningAssessment.Requirement(id, "goal-1", kind, true, kind.name().toLowerCase(), "1", Map.of());
        }
    }
}
