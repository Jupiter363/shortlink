package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import java.util.List;

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

    NativeExplorationAdapter.ExecutionKey identity();

    View view();

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
