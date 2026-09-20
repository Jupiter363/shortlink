package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.ReplanRequest;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.ReplanReceiptStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReplanCoordinatorTest {
    @Test
    void precompileFailureDoesNotReachRevisionApplier() {
        PlanSpec base = plan(1, List.of());
        PlanningAssessment assessment = assessment(1);
        ReplanRequest request = ReplanRequest.create(base, assessment, java.util.Set.of("old"),
                List.of(new ReplanRequest.Evidence("new-evidence", "new-artifact", "stats-v1", "hash")),
                List.of(new ReplanRequest.UnsatisfiedRequirement("gap", "MISSING", "needs evidence")), "new evidence");
        PlanSpec candidate = plan(2, List.of(step()));
        PlanningAssessment candidateAssessment = assessment(2);
        RunTokenHolder holder = new RunTokenHolder();
        MemoryReceipts receipts = new MemoryReceipts();
        AtomicBoolean applied = new AtomicBoolean();
        ReplanCoordinator coordinator = new ReplanCoordinator(receipts,
                (plan, ignored) -> { throw new IllegalStateException("PRECOMPILE_FAILED"); },
                command -> { applied.set(true); throw new AssertionError("applier must not run"); });

        assertThatThrownBy(() -> coordinator.replan(holder.token, request, candidate, candidateAssessment))
                .isInstanceOf(IllegalStateException.class).hasMessage("PRECOMPILE_FAILED");
        assertEquals(false, applied.get());
        assertEquals(1, holder.token.definition().revision());
        assertEquals(Optional.empty(), receipts.find(holder.token));
    }

    @Test
    void acceptedRevisionIsAppliedOnceAndCanBeReplayedAfterBaseIsRetired() throws Exception {
        PlanSpec base = planWithGoal(1, List.of());
        PlanningAssessment baseAssessment = assessment(1);
        ReplanRequest request = ReplanRequest.create(base, baseAssessment, java.util.Set.of("old"),
                List.of(new ReplanRequest.Evidence("new-evidence", "new-artifact", "stats-v1", "hash")),
                List.of(new ReplanRequest.UnsatisfiedRequirement("gap", "MISSING", "needs evidence")), "new evidence");
        PlanSpec candidate = planWithGoal(2, List.of(step()));
        PlanningAssessment candidateAssessment = assessment(2);
        var expected = request.assess(candidate, candidateAssessment);
        AtomicInteger applyCount = new AtomicInteger();
        MemoryReceipts receipts = new MemoryReceipts();
        RunTokenHolder holder = new RunTokenHolder();
        ReplanCoordinator coordinator = new ReplanCoordinator(receipts,
                (plan, ignored) -> new ReplanCoordinator.PreparedGraph(plan.planId(), plan.revision(), expected.candidatePlanHash()),
                command -> {
                    applyCount.incrementAndGet();
                    var next = new CampaignRunStore.RunToken(new CampaignRunStore.RunDefinition(
                            command.baseRun().definition().caller(), "session", "run", "plan", 2, "candidate"), 2, "next");
                    return new ReplanCoordinator.AppliedRevision(next,
                            receipts.record(command.baseRun(), 2, expected.candidatePlanHash(), command.requestJson(),
                                    "ACCEPTED", "ACCEPTED"));
                });

        var applied = coordinator.replan(holder.token, request, candidate, candidateAssessment);
        var replay = coordinator.replan(holder.token, request, candidate, candidateAssessment);

        assertEquals(ReplanCoordinator.Outcome.APPLIED, applied.outcome());
        assertEquals(ReplanCoordinator.Outcome.IDEMPOTENT, replay.outcome());
        assertEquals(1, applyCount.get());
        assertEquals(applied.receipt(), replay.receipt());
    }

    private static PlanSpec plan(int revision, List<PlanSpec.Step> steps) {
        return new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan", revision, "run", "inputs", List.of(), steps);
    }

    private static PlanSpec planWithGoal(int revision, List<PlanSpec.Step> steps) {
        return new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan", revision, "run", "inputs",
                List.of(new PlanSpec.Goal("goal", "question", true, "acceptance")), steps);
    }

    private static PlanningAssessment assessment(int revision) {
        var requirement = new PlanningAssessment.Requirement("gap", "goal", PlanningAssessment.RequirementKind.DATA,
                true, "criterion", "v1", java.util.Map.of());
        return new PlanningAssessment("plan", revision, "catalog-v1", List.of(requirement), List.of(),
                List.of(new PlanningAssessment.Gap("gap", PlanningAssessment.GapReason.NEEDS_INPUT, "missing")));
    }

    private static PlanSpec.Step step() {
        var executor = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "tool", "v1");
        var policy = new PlanSpec.ExplorationPolicy("policy", "v1", List.of(executor), "scope", "periods", List.of(), "stop");
        return new PlanSpec.Step("step", List.of(), PlanSpec.ExecutionMode.FIXED, executor, policy, List.of(), java.util.Map.of(), java.util.Map.of(), "output");
    }

    private static final class RunTokenHolder {
        final CampaignRunStore.RunToken token = new CampaignRunStore.RunToken(
                new CampaignRunStore.RunDefinition(new CampaignRunStore.Caller("tenant", "subject", 1),
                        "session", "run", "plan", 1, "{}"), 1, "advance");
    }

    private static final class MemoryReceipts implements ReplanReceiptStore {
        private Receipt receipt;
        @Override public Receipt record(CampaignRunStore.RunToken run, int revision, String hash, String json,
                                        String decision, String reason) {
            return receipt = new Receipt("receipt", run.definition().runId(), run.definition().revision(), revision,
                    hash, json, decision, reason, Instant.EPOCH);
        }
        @Override public Optional<Receipt> find(CampaignRunStore.RunToken run) { return Optional.ofNullable(receipt); }
        @Override public Optional<Receipt> findFinalized(CampaignRunStore.Caller caller, String runId, int baseRevision) {
            return Optional.ofNullable(receipt).filter(value -> value.runId().equals(runId)
                    && value.baseRevision() == baseRevision);
        }
    }
}
