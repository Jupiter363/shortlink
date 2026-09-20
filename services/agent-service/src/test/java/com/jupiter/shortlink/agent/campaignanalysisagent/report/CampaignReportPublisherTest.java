package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CampaignReportPublisherTest {
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");

    @Test
    void missingFirstGoalDeliveryDowngradesOnlyFirstGoalAndPublishesFixedEvidenceManifest() {
        var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "run-1", "inputs-1", List.of(
                new PlanSpec.Goal("goal-1", "Compare visits", true, "Deliver first result"),
                new PlanSpec.Goal("goal-2", "Explain devices", true, "Deliver second result")), List.of());
        var assessment = new PlanningAssessment("plan-1", 1, "catalog-v1", List.of(
                requirement("data-1", "goal-1", PlanningAssessment.RequirementKind.DATA),
                requirement("delivery-1", "goal-1", PlanningAssessment.RequirementKind.DELIVERY),
                requirement("data-2", "goal-2", PlanningAssessment.RequirementKind.DATA),
                requirement("delivery-2", "goal-2", PlanningAssessment.RequirementKind.DELIVERY)), List.of(), List.of());
        var block = new ReportBlock("metric-2", ReportBlock.Kind.METRIC, "Device visits", null,
                Map.of("value", 42), List.of("artifact-2"), true);
        var draft = new ReportDraft("report-1", 1, "run-1", "plan-1", 1,
                List.of(new ReportSection("second", 0, "Second goal", List.of("goal-2"), List.of(block))), List.of());
        var observations = Map.of(
                "data-1", new GoalAssessor.RequirementObservation(RequirementAssessment.Verdict.MET, null, List.of("artifact-1")),
                "data-2", new GoalAssessor.RequirementObservation(RequirementAssessment.Verdict.MET, null, List.of("artifact-2")),
                "delivery-2", new GoalAssessor.RequirementObservation(RequirementAssessment.Verdict.MET, null, List.of("artifact-2")));
        var publisher = new CampaignReportPublisher(new GoalAssessor(), id -> java.util.Optional.of(
                new CampaignReportPublisher.Evidence(id, "stats", "stats/v1", "checksum-" + id,
                        NOW.plusSeconds(3600), "READ_RESULT", true)), Clock.fixed(NOW, ZoneOffset.UTC), "report/v1");

        var published = publisher.publish(new CampaignReportPublisher.PublishRequest(plan, assessment, observations,
                draft, "report/v1"));

        assertThat(published.ref()).isEqualTo(new CampaignReportPublisher.ReportRef("report-1", 1));
        assertThat(published.goalAssessments()).extracting(GoalAssessment::goalId)
                .containsExactly("goal-1", "goal-2");
        assertThat(published.goalAssessments().get(0).status()).isEqualTo(GoalAssessment.Status.PARTIAL);
        assertThat(published.goalAssessments().get(0).reasonCode()).isEqualTo("DELIVERY_MISSING");
        assertThat(published.goalAssessments().get(1).status()).isEqualTo(GoalAssessment.Status.ANSWERED);
        assertThat(published.evidenceManifest().entries()).extracting(CampaignReportPublisher.EvidenceEntry::artifactId)
                .containsExactlyInAnyOrder("artifact-1", "artifact-2");
        assertThat(publisher.read("report-1", 1)).contains(published);
    }

    @Test
    void durablePublicationPersistsStableManifestAndUsesFixedReadKey() {
        var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-durable", 1, "run-durable", "inputs-durable",
                List.of(new PlanSpec.Goal("goal", "Compare visits", true, "Deliver result")), List.of());
        var assessment = new PlanningAssessment("plan-durable", 1, "catalog-v1", List.of(
                requirement("data", "goal", PlanningAssessment.RequirementKind.DATA),
                requirement("delivery", "goal", PlanningAssessment.RequirementKind.DELIVERY)), List.of(), List.of());
        var draft = new ReportDraft("report-durable", 1, "run-durable", "plan-durable", 1,
                List.of(new ReportSection("summary", 0, "Summary", List.of("goal"), List.of(
                        new ReportBlock("metric", ReportBlock.Kind.METRIC, "Visits", null,
                                Map.of("value", 12), List.of("artifact-durable"), true)))), List.of());
        var observations = Map.of(
                "data", new GoalAssessor.RequirementObservation(RequirementAssessment.Verdict.MET, null,
                        List.of("artifact-durable")),
                "delivery", new GoalAssessor.RequirementObservation(RequirementAssessment.Verdict.MET, null,
                        List.of("artifact-durable")));
        var lifecycle = new CapturingLifecycleStore();
        var publisher = new CampaignReportPublisher(new GoalAssessor(), id -> Optional.of(
                new CampaignReportPublisher.Evidence(id, "stats", "stats/v1", "checksum-" + id,
                        NOW.plusSeconds(3600), "READ_RESULT", true)), Clock.fixed(NOW, ZoneOffset.UTC), "report/v1", lifecycle);
        var request = new CampaignReportPublisher.PublishRequest(plan, assessment, observations, draft, "report/v1");
        var publication = new CampaignReportPublisher.Publication("owner", "report/v1", NOW.plusSeconds(1800),
                NOW.plusSeconds(2400));

        var first = publisher.publishDurable(request, publication);
        var second = publisher.publishDurable(request, publication);

        assertThat(first).isEqualTo(second);
        assertThat(lifecycle.lastDraft).isNotNull();
        assertThat(lifecycle.lastDraft.manifestJson()).contains("reportId");
        assertThat(lifecycle.lastDraft.manifestChecksum()).hasSize(64);
        assertThat(lifecycle.lastDraft.payloadJson()).contains("goalAssessments");
        assertThat(publisher.read("report-durable", 1, "owner", "report/v1",
                com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore.Mode.HISTORY_VIEW))
                .contains(first);
    }

    private static PlanningAssessment.Requirement requirement(String id, String goal,
                                                              PlanningAssessment.RequirementKind kind) {
        return new PlanningAssessment.Requirement(id, goal, kind, true, kind.name().toLowerCase(), "1", Map.of());
    }

    private static final class CapturingLifecycleStore implements
            com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore {
        private Draft lastDraft;
        private Published published;

        @Override public Published publish(Draft draft) {
            if (published != null && !published.payloadJson().equals(draft.payloadJson()))
                throw new IllegalStateException("REPORT_DRAFT_CONFLICT");
            lastDraft = draft;
            if (published == null) {
                published = new Published(draft.key(), draft.runId(), draft.planRevision(), draft.owner(), draft.capability(),
                        draft.manifestJson(), draft.manifestChecksum(), draft.evidenceRetainedUntil(), draft.retainedUntil(),
                        draft.reuseExpiresAt(), draft.payloadJson(), 1, 0);
            }
            return published;
        }

        @Override public Optional<Published> read(Key key, String owner, String capability, Mode mode) {
            return Optional.ofNullable(published);
        }

        @Override public long retain(Key key, String referenceId, String owner, String capability) { return 1; }
        @Override public long release(Key key, String referenceId) { return 1; }
        @Override public boolean cleanup(Key key, long expectedVersion) { return false; }
    }
}
