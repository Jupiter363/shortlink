package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCandidateStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandle;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CampaignReplanCandidateGateTest {
    private static final Caller OWNER = new Caller("tenant-1", "analyst-1", 7);
    private static final Caller OTHER_OWNER = new Caller("tenant-2", "analyst-1", 7);
    private static final CampaignReplanCandidateGate.Request REQUEST =
            new CampaignReplanCandidateGate.Request(OWNER, "session-1", "run-1", "step-1");

    @Test
    void missingOrTerminalTokenDoesNotReadCandidate() {
        CountingCandidates candidates = new CountingCandidates(replanAssessment());
        CampaignReplanCandidateGate gate = new CampaignReplanCandidateGate(
                (owner, session, run) -> Optional.empty(), candidates);

        assertThat(gate.resolve(REQUEST)).isEmpty();
        assertThat(candidates.reads).hasValue(0);
    }

    @Test
    void resolverTokenOwnerMismatchFailsBeforeCandidateRead() {
        CountingCandidates candidates = new CountingCandidates(replanAssessment());
        RunToken otherToken = token(OTHER_OWNER, "session-1", "run-1");
        CampaignReplanCandidateGate gate = new CampaignReplanCandidateGate(
                (owner, session, run) -> Optional.of(otherToken), candidates);

        assertThatThrownBy(() -> gate.resolve(REQUEST))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_CANDIDATE_TOKEN_BINDING_MISMATCH");
        assertThat(candidates.reads).hasValue(0);
    }

    @Test
    void resolverTokenSessionOrRunMismatchFailsBeforeCandidateRead() {
        CountingCandidates candidates = new CountingCandidates(replanAssessment());
        CampaignReplanCandidateGate sessionGate = new CampaignReplanCandidateGate(
                (owner, session, run) -> Optional.of(token(OWNER, "other-session", "run-1")), candidates);
        CampaignReplanCandidateGate runGate = new CampaignReplanCandidateGate(
                (owner, session, run) -> Optional.of(token(OWNER, "session-1", "other-run")), candidates);

        assertThatThrownBy(() -> sessionGate.resolve(REQUEST))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_CANDIDATE_TOKEN_BINDING_MISMATCH");
        assertThatThrownBy(() -> runGate.resolve(REQUEST))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_CANDIDATE_TOKEN_BINDING_MISMATCH");
        assertThat(candidates.reads).hasValue(0);
    }

    @Test
    void missingAndNonReplanAssessmentsReturnEmpty() {
        AtomicInteger reads = new AtomicInteger();
        RunToken token = token(OWNER, "session-1", "run-1");
        CampaignExplorationCandidateStore missing = store(() -> {
            reads.incrementAndGet();
            return Optional.empty();
        });
        CampaignReplanCandidateGate missingGate = new CampaignReplanCandidateGate(
                (owner, session, run) -> Optional.of(token), missing);
        assertThat(missingGate.resolve(REQUEST)).isEmpty();

        CampaignExplorationCandidateStore complete = store(() -> {
            reads.incrementAndGet();
            return Optional.of(new CampaignExplorationCandidateStore.Assessment(
                    "assessment-1", "child-1", "response-1", "candidate-1", "registry-1",
                    CampaignExplorationCandidateStore.Verdict.COMPLETE, Map.of(), List.of(), List.of()));
        });
        CampaignReplanCandidateGate completeGate = new CampaignReplanCandidateGate(
                (owner, session, run) -> Optional.of(token), complete);
        assertThat(completeGate.resolve(REQUEST)).isEmpty();
        assertThat(reads).hasValue(2);
    }

    @Test
    void nullAssessmentIsFailClosedAfterExactTokenRead() {
        AtomicInteger reads = new AtomicInteger();
        CampaignExplorationCandidateStore malformed = store(() -> {
            reads.incrementAndGet();
            return null;
        });
        CampaignReplanCandidateGate gate = new CampaignReplanCandidateGate(
                (owner, session, run) -> Optional.of(token(OWNER, "session-1", "run-1")), malformed);

        assertThat(gate.resolve(REQUEST)).isEmpty();
        assertThat(reads).hasValue(1);
    }

    @Test
    void validReplanReturnsOnlyImmutableMinimalSignalAndDoesNotCache() {
        CountingCandidates candidates = new CountingCandidates(replanAssessment());
        RunToken token = token(OWNER, "session-1", "run-1");
        CampaignReplanCandidateGate gate = new CampaignReplanCandidateGate(
                (owner, session, run) -> Optional.of(token), candidates);

        CampaignReplanCandidateGate.PendingReplan first = gate.resolve(REQUEST).orElseThrow();
        CampaignReplanCandidateGate.PendingReplan second = gate.resolve(REQUEST).orElseThrow();

        assertThat(first.owner()).isEqualTo(OWNER);
        assertThat(first.token()).isEqualTo(new CampaignRunHandle(
                OWNER, "session-1", "run-1", "plan-1", 1, CampaignRunStore.RunStatus.ACTIVE));
        assertThat(first.stepId()).isEqualTo("step-1");
        assertThat(first.candidateHash()).isEqualTo("candidate-hash-1");
        assertThat(first.reasonCodes()).containsExactly("CANDIDATE_REPLAN_REQUESTED");
        assertThat(first).isEqualTo(second);
        assertThat(candidates.reads).hasValue(2);
        assertThatThrownBy(() -> first.reasonCodes().add("MUTATED"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void dependencyExceptionsArePropagatedWithoutCandidateFallback() {
        RuntimeException resolverFailure = new RuntimeException("resolver-down");
        CountingCandidates candidates = new CountingCandidates(replanAssessment());
        CampaignReplanCandidateGate resolverGate = new CampaignReplanCandidateGate(
                (owner, session, run) -> { throw resolverFailure; }, candidates);
        assertThatThrownBy(() -> resolverGate.resolve(REQUEST)).isSameAs(resolverFailure);
        assertThat(candidates.reads).hasValue(0);

        RuntimeException assessmentFailure = new RuntimeException("assessment-down");
        CampaignReplanCandidateGate assessmentGate = new CampaignReplanCandidateGate(
                (owner, session, run) -> Optional.of(token(OWNER, "session-1", "run-1")),
                store(() -> { throw assessmentFailure; }));
        assertThatThrownBy(() -> assessmentGate.resolve(REQUEST)).isSameAs(assessmentFailure);
    }

    @Test
    void constructorAndRequestRejectMissingDependenciesOrIdentity() {
        assertThatThrownBy(() -> new CampaignReplanCandidateGate(null, store(Optional::empty)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("REPLAN_TOKEN_RESOLVER_REQUIRED");
        assertThatThrownBy(() -> new CampaignReplanCandidateGate(
                (owner, session, run) -> Optional.empty(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("REPLAN_CANDIDATE_STORE_REQUIRED");
        assertThatThrownBy(() -> new CampaignReplanCandidateGate.Request(
                OWNER, "session-1", "run-1", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPLAN_STEP_ID_REQUIRED");
    }

    private static CampaignExplorationCandidateStore.Assessment replanAssessment() {
        return new CampaignExplorationCandidateStore.Assessment(
                "assessment-1", "child-1", "response-1", "candidate-hash-1", "registry-1",
                CampaignExplorationCandidateStore.Verdict.REPLAN_REQUESTED, Map.of(), List.of(),
                List.of("CANDIDATE_REPLAN_REQUESTED"));
    }

    private static RunToken token(Caller owner, String sessionId, String runId) {
        return new RunToken(new RunDefinition(owner, sessionId, runId, "plan-1", 1, "{}"),
                1L, "advance-1");
    }

    private static CampaignExplorationCandidateStore store(
            java.util.function.Supplier<Optional<CampaignExplorationCandidateStore.Assessment>> reader) {
        return new CampaignExplorationCandidateStore() {
            @Override public String configurationId(RunDefinition definition, String stepId) {
                throw new UnsupportedOperationException();
            }

            @Override public Assessment assess(CampaignStepStore.StepPermit step, String modelChildId) {
                throw new UnsupportedOperationException();
            }

            @Override public Optional<Assessment> assessment(RunToken token, String stepId) {
                return reader.get();
            }

            @Override public CampaignStepStore.StepRecord settleComplete(CampaignStepStore.StepPermit permit) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static final class CountingCandidates implements CampaignExplorationCandidateStore {
        private final AtomicInteger reads = new AtomicInteger();
        private final Assessment assessment;

        private CountingCandidates(Assessment assessment) { this.assessment = assessment; }

        @Override public String configurationId(RunDefinition definition, String stepId) {
            throw new UnsupportedOperationException();
        }

        @Override public Assessment assess(CampaignStepStore.StepPermit step, String modelChildId) {
            throw new UnsupportedOperationException();
        }

        @Override public Optional<Assessment> assessment(RunToken token, String stepId) {
            reads.incrementAndGet();
            return Optional.ofNullable(assessment);
        }

        @Override public CampaignStepStore.StepRecord settleComplete(CampaignStepStore.StepPermit permit) {
            throw new UnsupportedOperationException();
        }
    }
}
