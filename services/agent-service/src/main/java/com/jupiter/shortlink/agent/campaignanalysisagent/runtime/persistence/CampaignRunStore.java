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
    enum DispatchPurpose { FRESH, RECONCILE, RELEASE }
    /** QUERY_CAPACITY_EXHAUSTED is a proven non-admission marker, not an unknown submission. */
    enum UnresolvedReason { READ_RESULT_UNKNOWN, SUBMISSION_UNRESOLVED, JOB_RESULT_UNKNOWN, QUERY_CAPACITY_EXHAUSTED }
    enum CapacityKind { ACTIVE_EXECUTION, RESULT_STORAGE, RECOVERY_IDENTITY }

    record SubmissionBackoff(long initialDelayMillis, long maxDelayMillis) {
        public SubmissionBackoff {
            if (initialDelayMillis < 1 || maxDelayMillis < initialDelayMillis)
                throw new IllegalArgumentException("Positive ordered submission backoff delays are required");
        }
        public static SubmissionBackoff defaults() { return new SubmissionBackoff(1_000, 30_000); }
    }

    record SubmissionDeferral(CapacityKind kind, int rejectedAttempts, long retryNotBeforeMillis,
                              String lastAttemptId, long lastAttemptVersion) {
        public SubmissionDeferral {
            if (kind == null || rejectedAttempts < 1 || retryNotBeforeMillis < 1
                    || lastAttemptId == null || lastAttemptId.isBlank() || lastAttemptVersion < 1)
                throw new IllegalArgumentException("Invalid durable submission deferral");
        }
    }

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

    /**
     * Trusted protocol adapter only: persist an explicit admitted=false capacity receipt for the
     * exact fresh ASYNC attempt. A timeout, missing receipt or failed recovery is never this proof.
     * Preserves the original request identity and the live callback until its actual exit.
     */
    void deferUnadmitted(DispatchPermit permit, CapacityKind kind);

    /** Returns the validated receipt only while the child is PREPARED because of non-admission. */
    Optional<SubmissionDeferral> submissionDeferral(RunToken token, String childId);

    /** Advisory due check; beginDispatch also enforces the persisted deadline under the run lock. */
    boolean submissionDue(RunToken token, String childId);

    /** ASYNC only; permits recovery/status/page reads, never a fresh submission. */
    DispatchPermit beginReconciliation(RunToken token, String childId);

    /**
     * Acquire a separate callback attempt for an ASYNC READY result without changing its output.
     * The trusted caller must first prepare a release intent in the release-binding store and must
     * check that store's mayRelease together with mayDispatch before every actual I/O. This permit
     * alone is not authorization to release remote pages or proof that all consumers can read them.
     */
    DispatchPermit beginRelease(RunToken token, String childId);

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

    /** Recheck current ownership, authorization and expiry without loading the immutable payload. */
    ArtifactMetadata inspectArtifact(Caller current, String artifactId, ArtifactAuthorizer authorizer);

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
