package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    private String input;
    private PendingCall pendingCall;
    private ResumeFacts ready;
    private boolean readyAcknowledged;
    private int resumeAcknowledgements;
    private boolean resumeInFlight;

    ExplorationTestLedger(NativeExplorationAdapter.ExecutionKey identity) { this.identity = identity; }

    @Override public NativeExplorationAdapter.ExecutionKey identity() { return identity; }
    @Override public synchronized View view() {
        return new View(status, reason, artifacts, job, callbacks);
    }
    @Override public synchronized void freezeInput(String value) {
        if (input != null && !input.equals(value)) throw new IllegalStateException("Frozen input changed");
        input = value;
    }
    @Override public synchronized void registerCall(CallInput call) {
        pendingCall = new PendingCall(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                call.toolCallId(), call.toolName(), call.arguments(), call.assistantText(), null);
    }
    @Override public synchronized Optional<ResumeFacts> readyToResume() {
        return readyAcknowledged ? Optional.empty() : Optional.ofNullable(ready);
    }
    @Override public synchronized boolean approveResume(String observationId) {
        if (resumeInFlight || readyAcknowledged || ready == null || ready.readyReceipts().size() != 1 || callbacks != 0
                || (status != Status.WAITING && status != Status.ACTIVE)
                || !ready.readyReceipts().get(0).observationId().equals(observationId)) return false;
        status = Status.ACTIVE;
        resumeInFlight = true;
        return true;
    }
    @Override public synchronized void acknowledgeResume(String observationId) {
        if (readyAcknowledged) return;
        if (ready == null || !ready.readyReceipts().get(0).observationId().equals(observationId))
            throw new IllegalStateException("Receipt mismatch");
        readyAcknowledged = true;
        resumeAcknowledgements++;
        String artifact = ready.readyReceipts().get(0).artifactId();
        if (!artifacts.contains(artifact)) artifacts.add(artifact);
    }
    @Override public synchronized void releaseResume(String observationId) { resumeInFlight = false; }
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
        else {
            job = value.jobId(); status = Status.WAITING;
            pendingCall = new PendingCall(pendingCall.actionId(), pendingCall.assistantMessageId(),
                    pendingCall.toolCallId(), pendingCall.toolName(), pendingCall.arguments(),
                    pendingCall.assistantText(), job);
        }
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
    synchronized ResumeFacts publishReady(String observationId, String artifactId) {
        var receipt = new ReadyReceipt(observationId, pendingCall.actionId(), pendingCall.jobId(), artifactId);
        var next = new ResumeFacts(identity, input, List.of(pendingCall), List.of(receipt));
        if (ready != null && !ready.equals(next)) throw new IllegalStateException("Conflicting completion receipt");
        ready = next;
        return ready;
    }
    synchronized void replaceReadyForCorruptionTest(ResumeFacts corrupted) { ready = corrupted; }
    synchronized int resumeAcknowledgements() { return resumeAcknowledgements; }
}
