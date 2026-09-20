package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanValidator;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NativeGraphPrecompilerTest {
    @Test
    void compilesWithoutInvokingDriverOrWritingCheckpoint() throws Exception {
        PlanSpec base = plan(1);
        PlanningAssessment assessment = assessment(1);
        FrozenCampaignRun frozen = FrozenCampaignRun.freeze(base, new FrozenInputSet(
                "inputs", "run", Map.of(), Map.of()), assessment);
        CampaignRunStore.RunToken token = new CampaignRunStore.RunToken(
                new CampaignRunStore.RunDefinition(new CampaignRunStore.Caller("tenant", "subject", 3),
                        "session", "run", "plan", 1,
                        frozen.definition(new CampaignRunStore.Caller("tenant", "subject", 3), "session").definitionJson()),
                1, "advance");
        NativeGraphPrecompiler precompiler = new NativeGraphPrecompiler(token, frozen,
                new PlanValidator(new EmptyCatalog()), null);

        PlanSpec candidate = plan(2);
        ReplanCoordinator.PreparedGraph prepared = assertDoesNotThrow(
                () -> precompiler.precompile(candidate, assessment(2)));

        assertThat(prepared.planId()).isEqualTo("plan");
        assertThat(prepared.revision()).isEqualTo(2);
        assertThat(prepared.candidatePlanHash()).isEqualTo(com.jupiter.shortlink.agent.campaignanalysisagent.planning.ReplanRequest.planHash(candidate));
    }

    @Test
    void rejectsBaseDefinitionMismatchBeforeCompilation() {
        PlanSpec base = plan(1);
        PlanningAssessment assessment = assessment(1);
        FrozenCampaignRun frozen = FrozenCampaignRun.freeze(base, new FrozenInputSet("inputs", "run", Map.of(), Map.of()), assessment);
        CampaignRunStore.RunToken mismatched = new CampaignRunStore.RunToken(
                new CampaignRunStore.RunDefinition(new CampaignRunStore.Caller("tenant", "subject", 3),
                        "session", "other-run", "plan", 1, "{}"), 1, "advance");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new NativeGraphPrecompiler(
                mismatched, frozen, new PlanValidator(new EmptyCatalog()), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPLAN_BASE_DEFINITION_MISMATCH");
    }

    private static PlanSpec plan(int revision) {
        return new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan", revision, "run", "inputs",
                List.of(new PlanSpec.Goal("goal", "question", false, "acceptance")), List.of());
    }

    private static PlanningAssessment assessment(int revision) {
        var requirement = new PlanningAssessment.Requirement("delivery", "goal",
                PlanningAssessment.RequirementKind.DELIVERY, true, "delivery", "1", Map.of());
        return new PlanningAssessment("plan", revision, "catalog-v1", List.of(requirement), List.of(),
                List.of(new PlanningAssessment.Gap("delivery", PlanningAssessment.GapReason.NEEDS_INPUT,
                        "awaiting evidence")));
    }

    private static final class EmptyCatalog implements com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog {
        @Override public String version() { return "catalog-v1"; }
        @Override public java.util.Optional<Capability> capability(PlanSpec.ExecutorRef executor) { return java.util.Optional.empty(); }
        @Override public java.util.Optional<Policy> policy(String ref, String version) { return java.util.Optional.empty(); }
        @Override public java.util.Optional<Criterion> criterion(String ref, String version) { return java.util.Optional.empty(); }
    }
}
