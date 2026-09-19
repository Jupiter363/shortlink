package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import java.util.ArrayList;
import java.util.List;

/** Trusted in-memory P0 stand-in only; intentionally provides no durable recovery implementation. */
final class ExplorationTestLedger implements ExplorationLedger {
    private final NativeExplorationAdapter.ExecutionKey identity;
    private final List<String> artifacts = new ArrayList<>();
    private final List<String> acceptedCalls = new ArrayList<>();
    private Status status = Status.ACTIVE;
    private String reason = "";
    private String job;
    private long epoch;
    private int callbacks;
    private int repairs;

    ExplorationTestLedger(NativeExplorationAdapter.ExecutionKey identity) { this.identity = identity; }

    @Override public NativeExplorationAdapter.ExecutionKey identity() { return identity; }
    @Override public synchronized View view() {
        return new View(status, reason, artifacts, job, callbacks);
    }
    @Override public synchronized boolean mayCallModel() { return status == Status.ACTIVE && callbacks == 0; }
    @Override public synchronized boolean rejectBatch(int maximumRepairs) {
        if (++repairs > maximumRepairs) {
            status = Status.BLOCKED;
            reason = "PROTOCOL_REPAIR_EXHAUSTED";
            return false;
        }
        return true;
    }
    @Override public synchronized void fail(String failure) { status = Status.FAILED; reason = failure; }
    @Override public synchronized void candidate() {
        if (mayCallModel()) status = Status.CANDIDATE;
    }
    @Override public synchronized long beginCallback(String callId, String name) {
        if (!mayCallModel()) throw new IllegalStateException("Callback admission denied");
        callbacks++;
        acceptedCalls.add(callId + ":" + name);
        return ++epoch;
    }
    @Override public synchronized boolean mayDispatch(long attempt) {
        return attempt == epoch && status == Status.ACTIVE && callbacks == 1;
    }
    @Override public synchronized void recordObservation(long attempt, NativeExplorationAdapter.Observation value) {
        if (attempt != epoch || status != Status.ACTIVE) return; // Late receipts cannot reopen a revoked attempt.
        if (value.artifactId() != null) artifacts.add(value.artifactId());
        else { job = value.jobId(); status = Status.WAITING; }
    }
    @Override public synchronized void unresolved(long attempt) {
        if (attempt == epoch && status != Status.FAILED) {
            status = Status.BLOCKED;
            reason = "EXECUTION_UNRESOLVED";
        }
    }
    @Override public synchronized void callbackExited(long attempt) {
        if (callbacks <= 0) throw new IllegalStateException("Callback exit counted twice");
        callbacks--;
    }
    synchronized List<String> acceptedCalls() { return List.copyOf(acceptedCalls); }
    synchronized int repairs() { return repairs; }
}
