package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import java.util.List;
import java.util.Optional;

/**
 * Trusted business boundary for the P0 native-agent experiment. No implementation is registered
 * with Spring: production requires the durable action/child ledger from P1.
 */
public interface ExplorationLedger {
    enum Status { ACTIVE, WAITING, CANDIDATE, FAILED, BLOCKED }

    record View(Status status, String reason, List<String> artifactIds, String jobId, int activeCallbacks) {
        public View {
            artifactIds = List.copyOf(artifactIds);
        }
    }

    /** Bounded fields approved before dispatch; backend assigns action and assistant identities. */
    record CallInput(String toolCallId, String toolName, String arguments, String assistantText) {}

    record PendingCall(String actionId, String assistantMessageId, String toolCallId, String toolName,
                       String arguments, String assistantText, String jobId) {}

    /** Authoritative completion receipt, supplied by reconciliation rather than model text. */
    record ReadyReceipt(String observationId, String actionId, String jobId, String artifactId) {}

    record ResumeFacts(NativeExplorationAdapter.ExecutionKey identity, String originalInput,
                       List<PendingCall> pendingCalls, List<ReadyReceipt> readyReceipts) {
        public ResumeFacts {
            pendingCalls = List.copyOf(pendingCalls);
            readyReceipts = List.copyOf(readyReceipts);
        }
    }

    NativeExplorationAdapter.ExecutionKey identity();

    View view();

    void freezeInput(String input);

    void registerCall(CallInput call);

    /** Facts remain recoverable until acknowledgeResume, including when native checkpointing fails. */
    Optional<ResumeFacts> readyToResume();

    /** Trusted CAS admission; must reject consumed observations and an unresolved live callback. */
    boolean approveResume(String observationId);

    /** Only after native invocation/checkpoint publication succeeds; idempotent by observation ID. */
    void acknowledgeResume(String observationId);

    /** Release the P0 attempt reservation without consuming an unpublished receipt. */
    void releaseResume(String observationId);

    /** Called by the native before-model hook, not merely after invoke has returned. */
    boolean mayCallModel();

    /** Count survives repeated invokes; false means the approved repair allowance is exhausted. */
    boolean rejectBatch(int maximumRepairs);

    void fail(String reason);

    void candidate();

    long beginCallback(String toolCallId, String toolName);

    /** Recheck for every real child request, including a later page inside the same callback. */
    boolean mayDispatch(long attempt);

    void recordObservation(long attempt, NativeExplorationAdapter.Observation observation);

    void unresolved(long attempt);

    /** Only the actual delegate's finally block calls this; Future completion is insufficient. */
    void callbackExited(long attempt);
}
