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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CampaignReplanApplicationServiceTest {
    @Test
    void invalidOwnerOrRunTokenIsRejectedBeforeCoordinator() {
        Fixture fixture = fixture((owner, token, capability) -> true);
        CampaignRunStore.Caller invalidOwner = new CampaignRunStore.Caller("tenant", "", 1);
        CampaignReplanApplicationService.Request invalid = new CampaignReplanApplicationService.Request(
                invalidOwner, fixture.token, CampaignReplanApplicationService.REQUIRED_CAPABILITY,
                fixture.replan, fixture.candidate, fixture.candidateAssessment);

        CampaignReplanApplicationService service = fixture.service();
        assertThatThrownBy(() -> service.execute(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPLAN_OWNER_INVALID");
        assertEquals(0, fixture.applyCount.get());
    }

    @Test
    void capabilityDenialIsRejectedWithoutDelegation() {
        Fixture fixture = fixture((owner, token, capability) -> false);

        assertThatThrownBy(() -> fixture.service().execute(fixture.request))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_CAPABILITY_DENIED");
        assertEquals(0, fixture.applyCount.get());
    }

    @Test
    void acceptedDelegationAndReplayOutcomeRemainVisible() throws Exception {
        Fixture fixture = fixture((owner, token, capability) -> true);

        CampaignReplanApplicationService.Response applied = fixture.service().execute(fixture.request);
        CampaignReplanApplicationService.Response replay = fixture.service().execute(fixture.request);

        assertEquals(ReplanCoordinator.Outcome.APPLIED, applied.outcome());
        assertEquals(true, applied.applied());
        assertEquals("ACCEPTED", applied.reasonCode());
        assertEquals(ReplanCoordinator.Outcome.IDEMPOTENT, replay.outcome());
        assertEquals(true, replay.idempotentReplay());
        assertEquals(applied.receipt(), replay.receipt());
        assertEquals(1, fixture.applyCount.get());
    }

    private static Fixture fixture(CampaignReplanApplicationService.CapabilityAuthorizer authorizer) {
        CampaignRunStore.Caller owner = new CampaignRunStore.Caller("tenant", "subject", 1);
        CampaignRunStore.RunToken token = new CampaignRunStore.RunToken(
                new CampaignRunStore.RunDefinition(owner, "session", "run", "plan", 1, "base"), 1, "advance-1");
        PlanSpec base = plan(1, List.of());
        PlanningAssessment baseAssessment = assessment(1);
        ReplanRequest replan = ReplanRequest.create(base, baseAssessment, java.util.Set.of("old"),
                List.of(new ReplanRequest.Evidence("new-evidence", "new-artifact", "stats-v1", "hash")),
                List.of(new ReplanRequest.UnsatisfiedRequirement("gap", "MISSING", "needs evidence")),
                "new evidence");
        PlanSpec candidate = plan(2, List.of(step()));
        PlanningAssessment candidateAssessment = assessment(2);
        CampaignReplanApplicationService.Request request = new CampaignReplanApplicationService.Request(owner, token,
                CampaignReplanApplicationService.REQUIRED_CAPABILITY, replan, candidate, candidateAssessment);
        MemoryReceipts receipts = new MemoryReceipts();
        AtomicInteger applyCount = new AtomicInteger();
        var expected = replan.assess(candidate, candidateAssessment);
        ReplanCoordinator coordinator = new ReplanCoordinator(receipts,
                (plan, ignored) -> new ReplanCoordinator.PreparedGraph(plan.planId(), plan.revision(),
                        expected.candidatePlanHash()),
                command -> {
                    applyCount.incrementAndGet();
                    CampaignRunStore.RunToken next = new CampaignRunStore.RunToken(
                            new CampaignRunStore.RunDefinition(owner, "session", "run", "plan", 2, "candidate"),
                            2, "advance-2");
                    return new ReplanCoordinator.AppliedRevision(next,
                            receipts.record(command.baseRun(), 2, expected.candidatePlanHash(), command.requestJson(),
                                    "ACCEPTED", "ACCEPTED"));
                });
        return new Fixture(owner, token, replan, candidate, candidateAssessment, request, coordinator, authorizer,
                applyCount);
    }

    private record Fixture(CampaignRunStore.Caller owner, CampaignRunStore.RunToken token,
                           ReplanRequest replan, PlanSpec candidate, PlanningAssessment candidateAssessment,
                           CampaignReplanApplicationService.Request request, ReplanCoordinator coordinator,
                           CampaignReplanApplicationService.CapabilityAuthorizer authorizer,
                           AtomicInteger applyCount) {
        CampaignReplanApplicationService service() {
            return new CampaignReplanApplicationService(coordinator, authorizer);
        }
    }

    private static PlanSpec plan(int revision, List<PlanSpec.Step> steps) {
        return new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan", revision, "run", "inputs", List.of(), steps);
    }

    private static PlanningAssessment assessment(int revision) {
        var requirement = new PlanningAssessment.Requirement("gap", "goal",
                PlanningAssessment.RequirementKind.DATA, true, "criterion", "v1", java.util.Map.of());
        return new PlanningAssessment("plan", revision, "catalog-v1", List.of(requirement), List.of(),
                List.of(new PlanningAssessment.Gap("gap", PlanningAssessment.GapReason.NEEDS_INPUT, "missing")));
    }

    private static PlanSpec.Step step() {
        var executor = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "tool", "v1");
        var policy = new PlanSpec.ExplorationPolicy("policy", "v1", List.of(executor), "scope", "periods",
                List.of(), "stop");
        return new PlanSpec.Step("step", List.of(), PlanSpec.ExecutionMode.FIXED, executor, policy, List.of(),
                java.util.Map.of(), java.util.Map.of(), "output");
    }

    private static final class MemoryReceipts implements ReplanReceiptStore {
        private Receipt receipt;

        @Override
        public Receipt record(CampaignRunStore.RunToken run, int revision, String hash, String json,
                              String decision, String reason) {
            return receipt = new Receipt("receipt", run.definition().runId(), run.definition().revision(), revision,
                    hash, json, decision, reason, Instant.EPOCH);
        }

        @Override
        public Optional<Receipt> find(CampaignRunStore.RunToken run) { return Optional.ofNullable(receipt); }

        @Override
        public Optional<Receipt> findFinalized(CampaignRunStore.Caller caller, String runId, int baseRevision) {
            return Optional.ofNullable(receipt).filter(value -> value.runId().equals(runId)
                    && value.baseRevision() == baseRevision);
        }
    }
}
