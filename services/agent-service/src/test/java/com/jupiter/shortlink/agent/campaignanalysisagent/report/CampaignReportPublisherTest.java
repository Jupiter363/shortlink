package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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

    private static PlanningAssessment.Requirement requirement(String id, String goal,
                                                              PlanningAssessment.RequirementKind kind) {
        return new PlanningAssessment.Requirement(id, goal, kind, true, kind.name().toLowerCase(), "1", Map.of());
    }
}
