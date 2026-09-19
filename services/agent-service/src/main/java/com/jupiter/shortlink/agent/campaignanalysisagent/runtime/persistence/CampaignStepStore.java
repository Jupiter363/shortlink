package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactAuthorizer;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Durable business step state. It is neither a Graph checkpoint nor a scheduler. */
public interface CampaignStepStore {
    enum StepStatus { PENDING, READY, RUNNING, WAITING, BLOCKED, FAILED, SUCCEEDED }

    record StepSpec(String stepId, String definitionJson, List<String> dependsOn,
                    Set<String> allowedOutputs, Set<String> requiredOutputs) {
        public StepSpec {
            dependsOn = List.copyOf(dependsOn);
            allowedOutputs = Set.copyOf(allowedOutputs);
            requiredOutputs = Set.copyOf(requiredOutputs);
        }
    }

    record StepRecord(StepSpec spec, StepStatus status, long rowVersion, String attemptId,
                      boolean callbackActive, String reason, Map<String, String> outputs) {
        public StepRecord { outputs = Map.copyOf(outputs); }
    }

    record StepPermit(RunToken runToken, String stepId, String attemptId, long attemptVersion) {}

    /** Internal read model; full definitions must be projected before exposure through an API. */
    record ProgressSnapshot(RunRecord run, List<StepRecord> steps) {
        public ProgressSnapshot { steps = List.copyOf(steps); }
    }

    /** Latest authorized revision including cancellation; never acquires a writer or loads artifact payloads. */
    ProgressSnapshot snapshot(Caller caller, String runId);

    /** Freeze the complete, nonempty set once per run revision; repeated identical initialization is safe. */
    void initialize(RunToken token, List<StepSpec> steps);

    /** Atomically fence the previous writer only after all recorded step and child callbacks exit. */
    RunToken acquireRun(RunToken token);

    Optional<StepRecord> step(RunToken token, String stepId);

    List<StepRecord> steps(RunToken token);

    boolean mayAdvance(RunToken token);

    /** Exact running step eligibility, including its own active callback; recheck before every real I/O. */
    boolean mayExecute(StepPermit permit);

    StepPermit beginStep(RunToken token, String stepId);

    /** Validate ready artifact references and publish outputs with the step state in the same transaction. */
    StepRecord settle(StepPermit permit, StepStatus target, Map<String, String> outputIds,
                      String reason, ArtifactAuthorizer authorizer);

    /** Real finally signal only; an unsettled RUNNING callback becomes BLOCKED/STEP_RESULT_UNKNOWN. */
    void callbackExited(StepPermit permit);

    /**
     * Reads durable child receipts only for WAITING or BLOCKED/STEP_RESULT_UNKNOWN.
     * Other blocked causes need an explicit caller decision; this method never polls or submits.
     */
    StepRecord refreshWaiting(RunToken token, String stepId);
}
