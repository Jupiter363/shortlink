package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Business ledger, independent of native graph checkpoints. No production registration in P1b. */
public interface CampaignRunStore {
    enum RunStatus { ACTIVE, CANCELLED, SUPERSEDED }
    enum ChildMode { SYNC, ASYNC }
    enum ChildState { PREPARED, DISPATCHING, WAITING, READY, UNRESOLVED }
    enum DispatchPurpose { FRESH, RECONCILE }
    enum UnresolvedReason { READ_RESULT_UNKNOWN, SUBMISSION_UNRESOLVED, JOB_RESULT_UNKNOWN }

    record Caller(String tenantId, String subject, long authVersion) {}

    record Limits(int definitionBytes, int requestBytes, int artifactBytes) {
        public Limits {
            if (definitionBytes < 1 || requestBytes < 1 || artifactBytes < 1)
                throw new IllegalArgumentException("Positive explicit byte limits are required");
        }
        public static Limits defaults() { return new Limits(16 * 1024 * 1024, 1024 * 1024, 64 * 1024 * 1024); }
    }

    /** definitionJson freezes the serialized Plan, FrozenInputSet and version pins, not graph state. */
    record RunDefinition(Caller caller, String sessionId, String runId, String planId, int revision,
                         String definitionJson) {
        public String definitionHash() { return sha256(definitionJson); }
    }

    record RunToken(RunDefinition definition, long version, String advanceToken) {}

    record RunRecord(RunDefinition definition, RunStatus status, long version, String advanceToken) {
        public RunToken token() { return new RunToken(definition, version, advanceToken); }
    }

    record ActionSpec(String actionId, String stepId, String executorKind, String executorName,
                      String executorVersion, String definitionJson) {
        public String definitionHash() { return sha256(definitionJson); }
    }

    /** Exact wire body is frozen; trusted callers must keep credentials and graph state out of it. */
    record WireRequest(String method, String path, String bodyJson) {
        public String hash() {
            return sha256(method.length() + ":" + method + path.length() + ":" + path
                    + bodyJson.length() + ":" + bodyJson);
        }
    }

    record ChildSpec(String childId, String actionId, ChildMode mode, String requestId, WireRequest wire) {}

    record ChildRecord(ChildSpec spec, ChildState state, String jobId, String artifactId,
                       String attemptId, long attemptVersion, DispatchPurpose purpose,
                       boolean callbackActive, UnresolvedReason reason) {}

    record DispatchPermit(RunToken token, String childId, String attemptId, long attemptVersion,
                          DispatchPurpose purpose) {}

    record ArtifactDraft(String artifactId, String type, String schemaVersion, String scopeRef,
                         String periodsRef, String qualityJson, String provenanceJson,
                         Instant expiresAt, String payloadJson) {}

    record ArtifactRef(String artifactId, String type, String schemaVersion, String payloadHash,
                       String scopeRef, String periodsRef, Instant expiresAt) {}

    record ArtifactMetadata(ArtifactRef ref, Caller owner, String runId, String planId, int revision,
                            String actionId, String childId, String executorVersion,
                            String qualityJson, String provenanceJson) {}

    record Artifact(ArtifactMetadata metadata, String payloadJson) {}

    @FunctionalInterface
    interface ArtifactAuthorizer {
        boolean mayRead(Caller current, ArtifactMetadata artifact);
    }

    RunToken createRun(RunDefinition definition);

    /** A read is not writer acquisition: callers must CAS advance before taking over a run. */
    Optional<RunRecord> loadRun(Caller caller, String runId);

    /** Fails while any recorded callback remains active; no inference from Future completion. */
    RunToken advance(RunToken token);

    RunToken revise(RunToken token, int revision, String definitionJson);

    void cancel(RunToken token);

    void prepareAction(RunToken token, ActionSpec action);

    ChildRecord prepareChild(RunToken token, ChildSpec child);

    /** Service-side records include frozen requests; Graph callers should project short refs only. */
    List<ChildRecord> children(RunToken token);

    Optional<ChildRecord> child(RunToken token, String childId);

    DispatchPermit beginDispatch(RunToken token, String childId);

    /** ASYNC only; permits recovery/status/page reads, never a fresh submission. */
    DispatchPermit beginReconciliation(RunToken token, String childId);

    /** Recheck immediately before each actual I/O; cannot revoke a request already on the wire. */
    boolean mayDispatch(DispatchPermit permit);

    void recordWaiting(DispatchPermit permit, String jobId);

    /** Retain an exact old attempt's remote identity after fencing, without reopening/publishing it. */
    void recordLateJob(DispatchPermit permit, String jobId);

    /** Immutable payload + metadata + child READY reference are committed in one transaction. */
    ArtifactRef publishReady(DispatchPermit permit, ArtifactDraft artifact);

    void markUnresolved(DispatchPermit permit);

    /** Does not release callbackActive: re-opening a store is not proof of process death. */
    void recoverInterrupted(RunToken token);

    /** Allowed after fencing/cancellation, but only for the exact original attempt identity. */
    void callbackExited(DispatchPermit permit);

    /** Analysis reuse only: current owner/auth version, expiry and authorization callback all apply. */
    Artifact readArtifact(Caller current, String artifactId, ArtifactAuthorizer authorizer);

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var writer = new OutputStreamWriter(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest), StandardCharsets.UTF_8)) {
                writer.write(text);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException | IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
