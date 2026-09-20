package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.BoundInputs;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessExecutionScope;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.ExplorationLedger;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.JdbcExplorationLedger;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.NativeExplorationAdapter;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepPermit;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Trusted opt-in bridge to the existing native ReAct loop; owns no model or tool runner. */
public final class PersistentExplorationExecutor implements PersistentPlanDriver.ReactAction {
    @FunctionalInterface public interface Factory {
        Session create(PlanSpec.Step step, BoundInputs inputs, StepPermit permit,
                       BooleanSupplier currentAuthorization) throws Exception;
    }

    @FunctionalInterface public interface SkillContinuation {
        void resume(StepPermit permit, String callId, long invocationVersion) throws Exception;
    }

    public record Session(JdbcExplorationLedger ledger, NativeExplorationAdapter adapter,
                          String prompt, SkillContinuation continuation) {
        public Session {
            Objects.requireNonNull(ledger); Objects.requireNonNull(adapter); Objects.requireNonNull(continuation);
            if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("EXPLORATION_INPUT_REQUIRED");
        }
    }

    private final Factory factory;
    private final ProcessExecutionScope processScope;

    public PersistentExplorationExecutor(Factory factory) { this(factory, null); }

    public PersistentExplorationExecutor(Factory factory, ProcessExecutionScope processScope) {
        this.factory = Objects.requireNonNull(factory);
        this.processScope = processScope;
    }

    @Override public ExplorationLedger.View execute(PlanSpec.Step step, BoundInputs inputs, StepPermit permit,
                                                    BooleanSupplier currentAuthorization) throws Exception {
        try (var ignored = processScope == null ? null : processScope.enter()) {
            return executeAdmitted(step, inputs, permit, currentAuthorization);
        }
    }

    private ExplorationLedger.View executeAdmitted(PlanSpec.Step step, BoundInputs inputs, StepPermit permit,
                                                    BooleanSupplier currentAuthorization) throws Exception {
        requireCurrent(currentAuthorization);
        if (step.executionMode() != PlanSpec.ExecutionMode.REACT || step.explorationPolicy() == null
                || !step.stepId().equals(permit.stepId())) throw new IllegalArgumentException("EXPLORATION_REQUIRES_REACT_STEP");
        Session session = Objects.requireNonNull(factory.create(step, inputs, permit, currentAuthorization));
        if (processScope != null && session.adapter().processExecutionScope() != processScope)
            throw new IllegalArgumentException("EXPLORATION_PROCESS_SCOPE_CHANGED");
        requireCurrent(currentAuthorization);
        var definition = permit.runToken().definition();
        var frozen = FrozenCampaignRun.read(definition);
        var caller = definition.caller();
        var expected = new NativeExplorationAdapter.ExecutionKey(caller.tenantId(), caller.subject(), caller.authVersion(),
                definition.sessionId(), definition.runId(), definition.planId(), definition.revision(), step.stepId(),
                step.explorationPolicy().policyVersion(), frozen.runnerVersion(), frozen.topologyVersion());
        if (!expected.equals(session.ledger().identity())) throw new IllegalArgumentException("EXPLORATION_SESSION_CHANGED");

        // Freeze before any continuation I/O; a changed prompt cannot reuse an existing invocation.
        session.ledger().freezeInput(session.prompt());
        var pending = session.ledger().pendingSkillContinuation();
        if (pending.isPresent()) {
            requireCurrent(currentAuthorization);
            var continuation = pending.get();
            session.continuation().resume(permit, continuation.callId(), continuation.invocationVersion());
            requireCurrent(currentAuthorization);
        }
        session.adapter().invoke(session.prompt());
        requireCurrent(currentAuthorization);
        // A view is a scheduling hint. The outer Driver independently reads the candidate receipt.
        return session.ledger().view();
    }

    private static void requireCurrent(BooleanSupplier current) {
        if (!Objects.requireNonNull(current).getAsBoolean()) throw new SecurityException("EXECUTION_ACCESS_DENIED");
    }
}
