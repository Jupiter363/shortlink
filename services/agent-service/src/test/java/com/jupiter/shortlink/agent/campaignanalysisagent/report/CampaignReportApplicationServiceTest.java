package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CampaignReportApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");

    @Test
    void missingDurableStoreIsRejectedBeforeAnyPublishOrRead() {
        Fixture fixture = fixture(false, (owner, capability, operation) -> true);

        assertThatThrownBy(() -> new CampaignReportApplicationService(fixture.publisher,
                (owner, capability, operation) -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("REPORT_LIFECYCLE_STORE_REQUIRED");
        assertThat(fixture.lifecycle.publishCalls).isZero();
        assertThat(fixture.lifecycle.readCalls).isZero();
    }

    @Test
    void ownerMismatchIsDeniedBeforeDurableIo() {
        Fixture fixture = fixture(true, (owner, capability, operation) -> "owner-a".equals(owner));
        CampaignReportApplicationService service = fixture.service;

        CampaignReportApplicationService.PublishRequest mismatchedPublish = new CampaignReportApplicationService.PublishRequest(
                fixture.publisherRequest, "owner-b", "report/read", NOW.plusSeconds(1800), NOW.plusSeconds(2400));
        assertThatThrownBy(() -> service.publish(mismatchedPublish))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPORT_ACCESS_DENIED");

        CampaignReportApplicationService.ReadRequest mismatchedRead = new CampaignReportApplicationService.ReadRequest(
                "report-application", 1, "owner-b", "report/read", ReportLifecycleStore.Mode.HISTORY_VIEW);
        assertThatThrownBy(() -> service.read(mismatchedRead))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPORT_ACCESS_DENIED");

        assertThat(fixture.lifecycle.publishCalls).isZero();
        assertThat(fixture.lifecycle.readCalls).isZero();
    }

    @Test
    void typedPublicationReturnsLifecycleOutcomeAndPreservesHistoryExportModes() {
        Fixture fixture = fixture(true, (owner, capability, operation) -> true);
        CampaignReportApplicationService.PublishRequest request = new CampaignReportApplicationService.PublishRequest(
                fixture.publisherRequest, "owner-a", "report/read", NOW.plusSeconds(1800), NOW.plusSeconds(2400));

        ReportLifecycleStore.Published first = fixture.service.publish(request);
        ReportLifecycleStore.Published replay = fixture.service.publish(request);

        assertThat(first).isSameAs(replay);
        assertThat(fixture.lifecycle.publishCalls).isEqualTo(2);
        assertThat(fixture.service.read(new CampaignReportApplicationService.ReadRequest(
                "report-application", 1, "owner-a", "report/read", ReportLifecycleStore.Mode.HISTORY_VIEW)))
                .containsSame(first);
        assertThat(fixture.service.read(new CampaignReportApplicationService.ReadRequest(
                "report-application", 1, "owner-a", "report/read", ReportLifecycleStore.Mode.EXPORT)))
                .containsSame(first);
        assertThat(fixture.lifecycle.modes)
                .containsExactly(ReportLifecycleStore.Mode.HISTORY_VIEW, ReportLifecycleStore.Mode.EXPORT);
    }

    @Test
    void typedRequestsRejectInvalidOwnerAndRetentionMetadata() {
        Fixture fixture = fixture(true, (owner, capability, operation) -> true);

        assertThatThrownBy(() -> new CampaignReportApplicationService.PublishRequest(
                fixture.publisherRequest, "", "report/read", NOW.plusSeconds(1), NOW.plusSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPORT_OWNER_INVALID");
        assertThatThrownBy(() -> new CampaignReportApplicationService.PublishRequest(
                fixture.publisherRequest, "owner-a", "report/read", null, NOW.plusSeconds(2)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("REPORT_RETAINED_UNTIL_REQUIRED");
        assertThatThrownBy(() -> new CampaignReportApplicationService.ReadRequest(
                "report-application", 0, "owner-a", "report/read", ReportLifecycleStore.Mode.HISTORY_VIEW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPORT_REVISION_INVALID");
    }

    private static Fixture fixture(boolean durable, CampaignReportApplicationService.AccessAuthorizer authorizer) {
        RecordingLifecycleStore lifecycle = new RecordingLifecycleStore();
        CampaignReportPublisher publisher = durable
                ? new CampaignReportPublisher(new GoalAssessor(), id -> Optional.of(
                        new CampaignReportPublisher.Evidence(id, "stats", "stats/v1", "checksum-" + id,
                                NOW.plusSeconds(3600), "READ_RESULT", true)), Clock.fixed(NOW, ZoneOffset.UTC), "report/v1",
                        lifecycle)
                : new CampaignReportPublisher(new GoalAssessor(), id -> Optional.of(
                        new CampaignReportPublisher.Evidence(id, "stats", "stats/v1", "checksum-" + id,
                                NOW.plusSeconds(3600), "READ_RESULT", true)), Clock.fixed(NOW, ZoneOffset.UTC), "report/v1");
        var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-application", 1, "run-application", "inputs",
                List.of(new PlanSpec.Goal("goal", "Compare visits", true, "Deliver result")), List.of());
        var assessment = new PlanningAssessment("plan-application", 1, "catalog-v1", List.of(
                new PlanningAssessment.Requirement("data", "goal", PlanningAssessment.RequirementKind.DATA,
                        true, "data", "1", Map.of()),
                new PlanningAssessment.Requirement("delivery", "goal", PlanningAssessment.RequirementKind.DELIVERY,
                        true, "delivery", "1", Map.of())), List.of(), List.of());
        var draft = new ReportDraft("report-application", 1, "run-application", "plan-application", 1,
                List.of(new ReportSection("summary", 0, "Summary", List.of("goal"), List.of(
                        new ReportBlock("metric", ReportBlock.Kind.METRIC, "Visits", null,
                                Map.of("value", 12), List.of("artifact-application"), true)))), List.of());
        var observations = Map.of(
                "data", new GoalAssessor.RequirementObservation(RequirementAssessment.Verdict.MET, null,
                        List.of("artifact-application")),
                "delivery", new GoalAssessor.RequirementObservation(RequirementAssessment.Verdict.MET, null,
                        List.of("artifact-application")));
        CampaignReportPublisher.PublishRequest publisherRequest = new CampaignReportPublisher.PublishRequest(
                plan, assessment, observations, draft, "report/v1");
        CampaignReportApplicationService service = durable
                ? new CampaignReportApplicationService(publisher, authorizer) : null;
        return new Fixture(publisher, publisherRequest, lifecycle, service);
    }

    private record Fixture(CampaignReportPublisher publisher,
                           CampaignReportPublisher.PublishRequest publisherRequest,
                           RecordingLifecycleStore lifecycle,
                           CampaignReportApplicationService service) { }

    private static final class RecordingLifecycleStore implements ReportLifecycleStore {
        private Draft draft;
        private Published published;
        private int publishCalls;
        private int readCalls;
        private final List<Mode> modes = new ArrayList<>();

        @Override
        public Published publish(Draft draft) {
            publishCalls++;
            if (published == null) {
                this.draft = draft;
                published = new Published(draft.key(), draft.runId(), draft.planRevision(), draft.owner(), draft.capability(),
                        draft.manifestJson(), draft.manifestChecksum(), draft.evidenceRetainedUntil(), draft.retainedUntil(),
                        draft.reuseExpiresAt(), draft.payloadJson(), 1, 0);
            }
            return published;
        }

        @Override
        public Optional<Published> read(Key key, String owner, String capability, Mode mode) {
            readCalls++;
            modes.add(mode);
            return Optional.ofNullable(published);
        }

        @Override public long retain(Key key, String referenceId, String owner, String capability) { return 1; }
        @Override public long release(Key key, String referenceId) { return 1; }
        @Override public boolean cleanup(Key key, long expectedVersion) { return false; }
    }
}
