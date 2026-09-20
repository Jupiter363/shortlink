package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local.LocalCalculationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Business ledger, independent of native graph checkpoints. No production registration in P1b. */
public interface CampaignRunStore {
    enum RunStatus { ACTIVE, CANCELLED, SUPERSEDED }
    enum ChildMode { SYNC, ASYNC, LOCAL, MODEL }
    enum ChildState { PREPARED, DISPATCHING, WAITING, READY, UNRESOLVED }
    enum DispatchPurpose { FRESH, RECONCILE, RELEASE, AUTHORITY_PAGE_READ, LOCAL_REPLAY }
    /** QUERY_CAPACITY_EXHAUSTED is a proven non-admission marker, not an unknown submission. */
    enum UnresolvedReason { READ_RESULT_UNKNOWN, SUBMISSION_UNRESOLVED, JOB_RESULT_UNKNOWN, QUERY_CAPACITY_EXHAUSTED, LOCAL_RESULT_UNKNOWN, LOCAL_RESULT_INVALID, MODEL_RESULT_UNKNOWN }
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

    record ChildSpec(String childId, String actionId, ChildMode mode, String requestId, WireRequest wire,
                     LocalCalculationRegistry.InvocationSpec localInvocation,
                     ModelInvocationRegistry.InvocationSpec modelInvocation) {
        public ChildSpec(String childId, String actionId, ChildMode mode, String requestId, WireRequest wire) {
            this(childId, actionId, mode, requestId, wire, null, null);
        }
        public ChildSpec(String childId, String actionId, ChildMode mode, String requestId, WireRequest wire,
                         LocalCalculationRegistry.InvocationSpec localInvocation) {
            this(childId, actionId, mode, requestId, wire, localInvocation, null);
        }
    }

    record ChildRecord(ChildSpec spec, ChildState state, String jobId, String artifactId,
                       String attemptId, long attemptVersion, DispatchPurpose purpose,
                       boolean callbackActive, UnresolvedReason reason) {}

    /** An existing producer remains immutable; only this current consumer may receive its original job. */
    record AdoptionLease(String bindingId, String consumerId, RunToken consumerToken, long bindingVersion) {}

    record DispatchPermit(RunToken token, String childId, String attemptId, long attemptVersion,
                          DispatchPurpose purpose, CampaignExplorationCallStore.CallPermit parentCall,
                          AdoptionLease adoptionLease) {
        public DispatchPermit(RunToken token, String childId, String attemptId, long attemptVersion,
                              DispatchPurpose purpose) {
            this(token, childId, attemptId, attemptVersion, purpose, null, null);
        }
        public DispatchPermit(RunToken token, String childId, String attemptId, long attemptVersion,
                              DispatchPurpose purpose, CampaignExplorationCallStore.CallPermit parentCall) {
            this(token, childId, attemptId, attemptVersion, purpose, parentCall, null);
        }
    }

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

    /** Trusted registered local calculation only; no HTTP request is synthesized. */
    ChildRecord prepareLocalChild(RunToken token, ChildSpec child, LocalCalculationRegistry.Approval approval,
                                  ArtifactAuthorizer authorizer);

    /** Approved MODEL requests are separate from capability actions and never masquerade as HTTP children. */
    ChildRecord prepareModelChild(CampaignStepStore.StepPermit step, ModelInvocationRegistry.ModelActionSpec action,
                                  ChildSpec child, ModelInvocationRegistry.Approval approval, ArtifactAuthorizer authorizer);

    /** Fresh PREPARED models only. Unknown outcomes require a later explicit recovery protocol, not redispatch. */
    DispatchPermit beginModelDispatch(CampaignStepStore.StepPermit step, String childId,
                                      ModelInvocationRegistry.Approval approval, ArtifactAuthorizer authorizer);

    /** The canonical response DTO and child READY state commit together; neither is a business Artifact. */
    void publishModelResponse(CampaignStepStore.StepPermit step, DispatchPermit permit,
                              ModelInvocationRegistry.Approval approval, ModelInvocationRegistry.Response response,
                              ArtifactAuthorizer authorizer);

    ModelInvocationRegistry.Response readModelResponse(RunToken token, String childId,
                                                       ModelInvocationRegistry.Approval approval, ArtifactAuthorizer authorizer);

    /** Service-side records include frozen requests; Graph callers should project short refs only. */
    List<ChildRecord> children(RunToken token);

    Optional<ChildRecord> child(RunToken token, String childId);

    DispatchPermit beginDispatch(RunToken token, String childId);

    /** A CALL-owned child needs its exact live outer callback permit, not only the run writer. */
    DispatchPermit beginDispatch(RunToken token, String childId, CampaignExplorationCallStore.CallPermit parentCall);

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

    DispatchPermit beginReconciliation(RunToken token, String childId, CampaignExplorationCallStore.CallPermit parentCall);

    /** Adopted statistics reads only; never grants a fresh submission or changes original producer identity. */
    default DispatchPermit beginAdoptedReconciliation(RunToken consumer, String consumerId) {
        throw new UnsupportedOperationException("STATISTICS_CONSUMERS_UNAVAILABLE");
    }

    /**
     * Re-read only an unresolved SYNC authority page whose original POST body pins a positive
     * cursor and ownership version. The first unpinned page and arbitrary SYNC reads cannot use
     * this entrypoint. Callers must still authorize each real I/O and verify the returned version.
     */
    DispatchPermit beginAuthorityPageReconciliation(RunToken token, String childId);

    DispatchPermit beginAuthorityPageReconciliation(RunToken token, String childId,
                                                    CampaignExplorationCallStore.CallPermit parentCall);

    /** Replays only an unresolved approved calculation while all frozen inputs remain authorized. */
    DispatchPermit beginLocalReplay(RunToken token, String childId, LocalCalculationRegistry.Approval approval,
                                    ArtifactAuthorizer authorizer);

    DispatchPermit beginLocalReplay(RunToken token, String childId, LocalCalculationRegistry.Approval approval,
                                    ArtifactAuthorizer authorizer, CampaignExplorationCallStore.CallPermit parentCall);

    /**
     * Acquire a separate callback attempt for an ASYNC READY result without changing its output.
     * The trusted caller must first prepare a release intent in the release-binding store and must
     * check that store's mayRelease together with mayDispatch before every actual I/O. This permit
     * alone is not authorization to release remote pages or proof that all consumers can read them.
     */
    DispatchPermit beginRelease(RunToken token, String childId);

    DispatchPermit beginRelease(RunToken token, String childId, CampaignExplorationCallStore.CallPermit parentCall);

    /** Recheck immediately before each actual I/O; cannot revoke a request already on the wire. */
    boolean mayDispatch(DispatchPermit permit);

    void recordWaiting(DispatchPermit permit, String jobId);

    /** Retain an exact old attempt's remote identity after fencing, without reopening/publishing it. */
    void recordLateJob(DispatchPermit permit, String jobId);

    /** Immutable payload + metadata + child READY reference are committed in one transaction. */
    ArtifactRef publishReady(DispatchPermit permit, ArtifactDraft artifact);

    /** All local outputs and READY publish atomically under the current running step and child attempts. */
    Map<String, ArtifactRef> publishLocalReady(CampaignStepStore.StepPermit step, DispatchPermit permit,
                                               LocalCalculationRegistry.Approval approval,
                                               Map<String, ArtifactDraft> outputs, ArtifactAuthorizer authorizer);

    /** Read a READY local output binding with current authorization, expiry and payload verification. */
    Map<String, ArtifactRef> localOutputs(RunToken token, String childId, ArtifactAuthorizer authorizer);

    void markUnresolved(DispatchPermit permit);

    /** Known local contract rejection is not replayable uncertainty; actual callback exit is separate. */
    void markLocalInvalid(DispatchPermit permit);

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
