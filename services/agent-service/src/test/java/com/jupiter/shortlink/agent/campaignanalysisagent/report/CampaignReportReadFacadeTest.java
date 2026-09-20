package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CampaignReportReadFacadeTest {
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");
    private static final String REPORT_ID = "report-facade";
    private static final int REVISION = 3;
    private static final String RUN_ID = "run-facade";
    private static final String PLAN_ID = "plan-facade";
    private static final String OWNER = "owner-facade";
    private static final String CAPABILITY = "campaign/report/v1";

    @Test
    void forwardsOneFixedKeyAndPreservesExportMode() {
        AtomicReference<CampaignReportApplicationService.ReadRequest> seen = new AtomicReference<>();
        CampaignReportReadFacade facade = new CampaignReportReadFacade(request -> {
            seen.set(request);
            return Optional.of(snapshot(ReportLifecycleStore.Mode.EXPORT));
        });

        CampaignReportReadFacade.View view = facade.read(request(ReportLifecycleStore.Mode.EXPORT)).orElseThrow();

        assertThat(seen.get().reportId()).isEqualTo(REPORT_ID);
        assertThat(seen.get().revision()).isEqualTo(REVISION);
        assertThat(seen.get().owner()).isEqualTo(OWNER);
        assertThat(seen.get().capability()).isEqualTo(CAPABILITY);
        assertThat(seen.get().mode()).isEqualTo(ReportLifecycleStore.Mode.EXPORT);
        assertThat(view.schemaVersion()).isEqualTo(CampaignReportReadFacade.SCHEMA);
        assertThat(view.reportRef()).isEqualTo(new CampaignReportPublisher.ReportRef(REPORT_ID, REVISION));
        assertThat(view.mode()).isEqualTo(ReportLifecycleStore.Mode.EXPORT);
        assertThat(view.draft().planId()).isEqualTo(PLAN_ID);
    }

    @Test
    void keepsMissingFixedReportEmptyWithoutFallback() {
        AtomicInteger reads = new AtomicInteger();
        CampaignReportReadFacade facade = new CampaignReportReadFacade(request -> {
            reads.incrementAndGet();
            return Optional.empty();
        });

        assertThat(facade.read(request(ReportLifecycleStore.Mode.HISTORY_VIEW))).isEmpty();
        assertThat(reads).hasValue(1);
    }

    @Test
    void rejectsSnapshotKeyOrModeDrift() {
        CampaignReportReadFacade facade = new CampaignReportReadFacade(request -> Optional.of(
                new CampaignReportReadProjection.Snapshot(
                        CampaignReportReadProjection.SCHEMA, ReportLifecycleStore.Mode.HISTORY_VIEW,
                        new CampaignReportPublisher.ReportRef("other-report", REVISION), RUN_ID, REVISION,
                        snapshot(ReportLifecycleStore.Mode.HISTORY_VIEW).draft(), List.<GoalAssessment>of(),
                        "a".repeat(64), NOW.plusSeconds(3600), NOW.plusSeconds(1800), NOW.plusSeconds(2400))));

        assertThatThrownBy(() -> facade.read(request(ReportLifecycleStore.Mode.HISTORY_VIEW)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("REPORT_VIEW_IDENTITY_MISMATCH");
    }

    @Test
    void viewDoesNotSerializeStorageOrAuthorizationMetadata() throws JsonProcessingException {
        CampaignReportReadFacade facade = new CampaignReportReadFacade(request -> Optional.of(
                snapshot(ReportLifecycleStore.Mode.HISTORY_VIEW)));

        String json = new ObjectMapper().writeValueAsString(
                facade.read(request(ReportLifecycleStore.Mode.HISTORY_VIEW)).orElseThrow());

        assertThat(json).contains("reportRef", "goalAssessments", "draft");
        assertThat(json).doesNotContain("manifestChecksum", "evidenceRetainedUntil", "retainedUntil",
                "reuseExpiresAt", "owner", "capability", "payloadJson");
    }

    @Test
    void requestRequiresExplicitNonLatestIdentity() {
        assertThatThrownBy(() -> new CampaignReportReadFacade.Request(REPORT_ID, 0, OWNER, CAPABILITY,
                ReportLifecycleStore.Mode.HISTORY_VIEW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPORT_REVISION_INVALID");
        assertThatThrownBy(() -> new CampaignReportReadFacade.Request(" ", REVISION, OWNER, CAPABILITY,
                ReportLifecycleStore.Mode.HISTORY_VIEW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPORT_ID_INVALID");
    }

    private static CampaignReportReadFacade.Request request(ReportLifecycleStore.Mode mode) {
        return new CampaignReportReadFacade.Request(REPORT_ID, REVISION, OWNER, CAPABILITY, mode);
    }

    private static CampaignReportReadProjection.Snapshot snapshot(ReportLifecycleStore.Mode mode) {
        ReportBlock block = new ReportBlock("metric-facade", ReportBlock.Kind.METRIC, "Visits", null,
                Map.of("value", 12), List.of("artifact-facade"), true);
        ReportDraft draft = new ReportDraft(REPORT_ID, REVISION, RUN_ID, PLAN_ID, REVISION,
                List.of(new ReportSection("summary", 0, "Summary", List.of("goal-facade"), List.of(block))),
                List.of());
        GoalAssessment goal = new GoalAssessment("goal-facade", GoalAssessment.Status.ANSWERED, null,
                List.of("artifact-facade"), List.of(), List.of());
        return new CampaignReportReadProjection.Snapshot(CampaignReportReadProjection.SCHEMA, mode,
                new CampaignReportPublisher.ReportRef(REPORT_ID, REVISION), RUN_ID, REVISION, draft,
                List.of(goal), "a".repeat(64), NOW.plusSeconds(3600), NOW.plusSeconds(1800),
                NOW.plusSeconds(2400));
    }
}
