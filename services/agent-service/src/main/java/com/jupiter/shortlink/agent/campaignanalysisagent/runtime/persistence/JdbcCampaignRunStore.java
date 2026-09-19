package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opt-in P1b store, without Spring registration or a model/worker loop. Database transactions fence
 * local publication; the caller still owns each real I/O boundary and actual callback-exit signal.
 * Reconstructing this class never proves that an earlier callback or process has exited.
 */
public final class JdbcCampaignRunStore implements CampaignRunStore {
    private static final JsonFactory JSON = new JsonFactory();
    private static final String AUTHORITY_PAGE_PATH =
            "/internal/short-link-admin/v1/agent-tools/authorization/group-members-page";
    private static final ObjectMapper AUTHORITY_JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Limits limits;
    private final SubmissionBackoff submissionBackoff;

    public JdbcCampaignRunStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this(jdbc, transactions, clock, Limits.defaults());
    }

    public JdbcCampaignRunStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock, Limits limits) {
        this(jdbc, transactions, clock, limits, SubmissionBackoff.defaults());
    }

    public JdbcCampaignRunStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock, Limits limits,
                                SubmissionBackoff submissionBackoff) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        this.limits = Objects.requireNonNull(limits);
        this.submissionBackoff = Objects.requireNonNull(submissionBackoff);
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly())
            throw new IllegalArgumentException("Ledger requires one writable REQUIRED DataSource transaction");
    }

    /** Narrow composition proof for stores that must publish business rows and READY atomically. */
    boolean sharesTransactionDataSource(JdbcTemplate other) {
        return other != null && other.getDataSource() == jdbc.getDataSource();
    }

    @Override
    public RunToken createRun(RunDefinition definition) {
        validateDefinition(definition);
        if (definition.revision() != 1) throw new IllegalArgumentException("A run starts at revision one");
        return transaction(() -> {
            var existing = findRun(definition.runId(), 1, true);
            if (existing.isPresent()) {
                if (!existing.get().definition().equals(definition)) conflict("RUN_DEFINITION_CHANGED");
                if (existing.get().status() != RunStatus.ACTIVE) conflict("RUN_NOT_ACTIVE");
                return existing.get().token();
            }
            var token = new RunToken(definition, 0, freshId());
            insertRun(token);
            return token;
        });
    }

    @Override
    public Optional<RunRecord> loadRun(Caller caller, String runId) {
        validateCaller(caller);
        id(runId, "runId", 96);
        List<RunRecord> rows = jdbc.query("SELECT * FROM campaign_run_ledger WHERE run_id=? ORDER BY revision DESC LIMIT 1",
                (rs, row) -> run(rs), runId);
        if (rows.isEmpty()) return Optional.empty();
        sameCaller(caller, rows.get(0).definition().caller());
        return Optional.of(rows.get(0));
    }

    @Override
    public RunToken advance(RunToken token) {
        return transaction(() -> {
            lockRun(token, true);
            requireNoCallbacks(token.definition().runId());
            var next = new RunToken(token.definition(), Math.addExact(token.version(), 1), freshId());
            int changed = jdbc.update("UPDATE campaign_run_ledger SET row_version=?, advance_token=?, updated_at=? "
                            + "WHERE run_id=? AND revision=? AND row_version=? AND advance_token=? AND run_status='ACTIVE'",
                    next.version(), next.advanceToken(), now(), token.definition().runId(), token.definition().revision(),
                    token.version(), token.advanceToken());
            requireChanged(changed);
            return next;
        });
    }

    @Override
    public RunToken revise(RunToken token, int revision, String definitionJson) {
        if (revision != token.definition().revision() + 1)
            throw new IllegalArgumentException("A revision must advance exactly once");
        var current = token.definition();
        var definition = new RunDefinition(current.caller(), current.sessionId(), current.runId(),
                current.planId(), revision, definitionJson);
        validateDefinition(definition);
        return transaction(() -> {
            lockRun(token, true);
            revoke(token, RunStatus.SUPERSEDED);
            var next = new RunToken(definition, Math.addExact(token.version(), 1), freshId());
            insertRun(next);
            return next;
        });
    }

    @Override
    public void cancel(RunToken token) {
        transaction(() -> {
            lockRun(token, true);
            revoke(token, RunStatus.CANCELLED);
            return null;
        });
    }

    @Override
    public void prepareAction(RunToken token, ActionSpec action) {
        validateAction(action);
        transaction(() -> {
            lockRun(token, true);
            var existing = action(token, action.actionId());
            if (existing.isPresent()) {
                if (!existing.get().equals(action)) conflict("ACTION_DEFINITION_CHANGED");
                return null;
            }
            jdbc.update("INSERT INTO campaign_action_ledger (run_id,revision,action_id,step_id,executor_kind,executor_name,"
                            + "executor_version,definition_hash,definition_json,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    token.definition().runId(), token.definition().revision(), action.actionId(), action.stepId(),
                    action.executorKind(), action.executorName(), action.executorVersion(), action.definitionHash(),
                    action.definitionJson(), now());
            return null;
        });
    }

    @Override
    public ChildRecord prepareChild(RunToken token, ChildSpec spec) {
        validateChild(spec);
        return transaction(() -> {
            lockRun(token, true);
            if (action(token, spec.actionId()).isEmpty()) conflict("ACTION_NOT_FOUND");
            var existing = findChild(token.definition(), spec.childId(), true);
            if (existing.isPresent()) {
                if (!existing.get().spec().equals(spec)) conflict("CHILD_REQUEST_CHANGED");
                return existing.get();
            }
            jdbc.update("INSERT INTO campaign_child_ledger (run_id,revision,child_id,action_id,tenant_id,child_mode,request_id,"
                            + "wire_method,wire_path,wire_hash,wire_body,child_state,attempt_version,callback_active,created_at,updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,'PREPARED',0,FALSE,?,?)",
                    token.definition().runId(), token.definition().revision(), spec.childId(), spec.actionId(),
                    token.definition().caller().tenantId(), spec.mode().name(), spec.requestId(), spec.wire().method(),
                    spec.wire().path(), spec.wire().hash(), spec.wire().bodyJson(), now(), now());
            return findChild(token.definition(), spec.childId(), false).orElseThrow();
        });
    }

    @Override
    public List<ChildRecord> children(RunToken token) {
        return transaction(() -> {
            lockRun(token, false);
            return jdbc.query("SELECT * FROM campaign_child_ledger WHERE run_id=? AND revision=? ORDER BY child_id",
                    (rs, row) -> child(rs), token.definition().runId(), token.definition().revision());
        });
    }

    @Override
    public Optional<ChildRecord> child(RunToken token, String childId) {
        id(childId, "childId", 96);
        return transaction(() -> {
            lockRun(token, false);
            return findChild(token.definition(), childId, false);
        });
    }

    @Override public DispatchPermit beginDispatch(RunToken token, String childId) {
        return begin(token, childId, DispatchPurpose.FRESH);
    }

    @Override
    public void deferUnadmitted(DispatchPermit permit, CapacityKind kind) {
        Objects.requireNonNull(permit, "Dispatch permit is required");
        Objects.requireNonNull(kind, "Validated capacity kind is required");
        if (permit.purpose() != DispatchPurpose.FRESH) conflict("DEFERRAL_REQUIRES_FRESH_ASYNC_ATTEMPT");
        transaction(() -> {
            RunToken token = permit.token();
            lockRun(token, true);
            ChildRecord child = requireAttempt(permit);
            if (child.spec().mode() != ChildMode.ASYNC || child.jobId() != null || child.artifactId() != null)
                conflict("DEFERRAL_REQUIRES_FRESH_ASYNC_ATTEMPT");
            if (isDeferred(child)) {
                SubmissionDeferral existing = requireDeferral(token, child);
                if (existing.kind() != kind) conflict("SUBMISSION_DEFERRAL_CHANGED");
                return null;
            }
            if (child.state() != ChildState.DISPATCHING || !child.callbackActive())
                conflict("ATTEMPT_NOT_DISPATCHING");
            Optional<SubmissionDeferral> previous = findDeferral(token, child);
            if (previous.isPresent() && previous.get().lastAttemptVersion() >= permit.attemptVersion())
                conflict("SUBMISSION_DEFERRAL_CORRUPTED");
            int rejected = previous.map(value -> Math.addExact(value.rejectedAttempts(), 1)).orElse(1);
            long rejectedAt = now();
            long retryAt = Math.addExact(rejectedAt, retryDelay(rejected));
            new SubmissionDeferral(kind, rejected, retryAt, permit.attemptId(), permit.attemptVersion());
            if (previous.isEmpty()) {
                jdbc.update("INSERT INTO campaign_submission_deferral (run_id,revision,child_id,request_id,wire_hash,"
                                + "capacity_kind,rejected_attempts,retry_not_before,last_attempt_id,last_attempt_version,updated_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        token.definition().runId(), token.definition().revision(), permit.childId(), child.spec().requestId(),
                        child.spec().wire().hash(), kind.name(), rejected, retryAt, permit.attemptId(), permit.attemptVersion(), rejectedAt);
            } else {
                jdbc.update("UPDATE campaign_submission_deferral SET capacity_kind=?,rejected_attempts=?,retry_not_before=?,"
                                + "last_attempt_id=?,last_attempt_version=?,updated_at=? WHERE run_id=? AND revision=? AND child_id=?",
                        kind.name(), rejected, retryAt, permit.attemptId(), permit.attemptVersion(), rejectedAt,
                        token.definition().runId(), token.definition().revision(), permit.childId());
            }
            jdbc.update("UPDATE campaign_child_ledger SET child_state='PREPARED',unresolved_reason=?,updated_at=? "
                            + "WHERE run_id=? AND revision=? AND child_id=?",
                    UnresolvedReason.QUERY_CAPACITY_EXHAUSTED.name(), rejectedAt, token.definition().runId(),
                    token.definition().revision(), permit.childId());
            return null;
        });
    }

    @Override
    public Optional<SubmissionDeferral> submissionDeferral(RunToken token, String childId) {
        id(childId, "childId", 96);
        return transaction(() -> {
            lockRun(token, true);
            ChildRecord child = findChild(token.definition(), childId, true)
                    .orElseThrow(() -> new IllegalStateException("CHILD_NOT_FOUND"));
            return isDeferred(child) ? Optional.of(requireDeferral(token, child)) : Optional.empty();
        });
    }

    @Override
    public boolean submissionDue(RunToken token, String childId) {
        return submissionDeferral(token, childId).map(value -> value.retryNotBeforeMillis() <= now()).orElse(true);
    }

    @Override public DispatchPermit beginReconciliation(RunToken token, String childId) {
        return begin(token, childId, DispatchPurpose.RECONCILE);
    }

    @Override public DispatchPermit beginAuthorityPageReconciliation(RunToken token, String childId) {
        return begin(token, childId, DispatchPurpose.AUTHORITY_PAGE_READ);
    }

    @Override public DispatchPermit beginRelease(RunToken token, String childId) {
        return begin(token, childId, DispatchPurpose.RELEASE);
    }

    private DispatchPermit begin(RunToken token, String childId, DispatchPurpose purpose) {
        id(childId, "childId", 96);
        return transaction(() -> {
            lockRun(token, true);
            requireNoCallbacks(token.definition().runId());
            ChildRecord child = findChild(token.definition(), childId, true)
                    .orElseThrow(() -> new IllegalStateException("CHILD_NOT_FOUND"));
            if (purpose == DispatchPurpose.FRESH && child.state() != ChildState.PREPARED)
                conflict("FRESH_DISPATCH_REQUIRES_PREPARED");
            if (purpose == DispatchPurpose.FRESH && isDeferred(child)
                    && requireDeferral(token, child).retryNotBeforeMillis() > now())
                conflict("SUBMISSION_BACKOFF_ACTIVE");
            if (purpose == DispatchPurpose.RECONCILE && (child.spec().mode() != ChildMode.ASYNC
                    || (child.state() != ChildState.WAITING && child.state() != ChildState.UNRESOLVED)))
                conflict("RECONCILIATION_REQUIRES_ASYNC_WAITING_OR_UNRESOLVED");
            if (purpose == DispatchPurpose.AUTHORITY_PAGE_READ) {
                if (child.spec().mode() != ChildMode.SYNC || child.state() != ChildState.UNRESOLVED
                        || child.reason() != UnresolvedReason.READ_RESULT_UNKNOWN
                        || child.jobId() != null || child.artifactId() != null)
                    conflict("AUTHORITY_PAGE_READ_REQUIRES_UNRESOLVED_SYNC");
                requirePinnedAuthorityPage(child.spec().wire());
            }
            if (purpose == DispatchPurpose.RELEASE && (child.spec().mode() != ChildMode.ASYNC
                    || child.state() != ChildState.READY || child.jobId() == null || child.artifactId() == null))
                conflict("RELEASE_REQUIRES_ASYNC_READY_RESULT");
            var permit = new DispatchPermit(token, childId, freshId(), Math.addExact(child.attemptVersion(), 1), purpose);
            String resultTransition = purpose == DispatchPurpose.RELEASE ? ""
                    : "child_state='DISPATCHING',unresolved_reason=NULL,";
            jdbc.update("UPDATE campaign_child_ledger SET " + resultTransition + "attempt_id=?,attempt_version=?,"
                            + "attempt_purpose=?,dispatch_run_version=?,dispatch_run_token=?,callback_active=TRUE,"
                            + "updated_at=? WHERE run_id=? AND revision=? AND child_id=?",
                    permit.attemptId(), permit.attemptVersion(), purpose.name(), token.version(), token.advanceToken(),
                    now(), token.definition().runId(), token.definition().revision(), childId);
            return permit;
        });
    }

    @Override
    public boolean mayDispatch(DispatchPermit permit) {
        return transaction(() -> {
            try {
                lockRun(permit.token(), true);
                ChildRecord child = requireAttempt(permit);
                ChildState expected = permit.purpose() == DispatchPurpose.RELEASE ? ChildState.READY : ChildState.DISPATCHING;
                return child.state() == expected && child.callbackActive();
            } catch (IllegalStateException | SecurityException denied) {
                return false;
            }
        });
    }

    @Override
    public void recordWaiting(DispatchPermit permit, String jobId) {
        requireResultMutationPurpose(permit);
        id(jobId, "jobId", 128);
        transaction(() -> {
            lockRun(permit.token(), true);
            ChildRecord child = requireAttempt(permit);
            if (child.spec().mode() != ChildMode.ASYNC) conflict("SYNC_CHILD_CANNOT_WAIT_ON_JOB");
            requireMatchingJob(child, jobId);
            if (child.state() == ChildState.WAITING) return null;
            if (child.state() != ChildState.DISPATCHING || !child.callbackActive()) conflict("ATTEMPT_NOT_DISPATCHING");
            jdbc.update("UPDATE campaign_child_ledger SET child_state='WAITING',job_id=?,unresolved_reason=NULL,updated_at=? "
                            + "WHERE run_id=? AND revision=? AND child_id=?",
                    jobId, now(), permit.token().definition().runId(), permit.token().definition().revision(), permit.childId());
            return null;
        });
    }

    @Override
    public void recordLateJob(DispatchPermit permit, String jobId) {
        requireResultMutationPurpose(permit);
        id(jobId, "jobId", 128);
        transaction(() -> {
            requireFrozenDefinition(permit.token().definition());
            ChildRecord child = requireAttempt(permit);
            if (child.spec().mode() != ChildMode.ASYNC) conflict("SYNC_CHILD_CANNOT_WAIT_ON_JOB");
            requireMatchingJob(child, jobId);
            if (child.state() == ChildState.READY) {
                jdbc.update("UPDATE campaign_child_ledger SET job_id=?,updated_at=? WHERE run_id=? AND revision=? AND child_id=?",
                        jobId, now(), permit.token().definition().runId(), permit.token().definition().revision(), permit.childId());
                return null;
            }
            jdbc.update("UPDATE campaign_child_ledger SET job_id=?,child_state='UNRESOLVED',unresolved_reason=?,updated_at=? "
                            + "WHERE run_id=? AND revision=? AND child_id=?",
                    jobId, UnresolvedReason.JOB_RESULT_UNKNOWN.name(), now(), permit.token().definition().runId(),
                    permit.token().definition().revision(), permit.childId());
            return null;
        });
    }

    @Override
    public ArtifactRef publishReady(DispatchPermit permit, ArtifactDraft draft) {
        requireResultMutationPurpose(permit);
        validateArtifact(draft);
        return transaction(() -> {
            lockRun(permit.token(), true);
            ChildRecord child = requireAttempt(permit);
            ActionSpec action = action(permit.token(), child.spec().actionId()).orElseThrow();
            ArtifactMetadata expected = metadata(permit, child, action, draft);
            var existing = findArtifact(draft.artifactId());
            if (child.state() == ChildState.READY) {
                if (!draft.artifactId().equals(child.artifactId()) || existing.isEmpty()
                        || !existing.get().equals(expected)) conflict("ARTIFACT_IMMUTABLE");
                return expected.ref();
            }
            if (child.state() != ChildState.DISPATCHING || !child.callbackActive()) conflict("ATTEMPT_NOT_DISPATCHING");
            if (existing.isPresent()) conflict("ARTIFACT_ID_ALREADY_USED");
            var definition = permit.token().definition();
            Caller owner = definition.caller();
            jdbc.update("INSERT INTO campaign_artifact (artifact_id,tenant_id,subject_name,auth_version,run_id,plan_id,revision,"
                            + "action_id,child_id,executor_version,artifact_type,schema_version,scope_ref,periods_ref,quality_json,"
                            + "provenance_json,payload_hash,expires_at,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    draft.artifactId(), owner.tenantId(), owner.subject(), owner.authVersion(), definition.runId(), definition.planId(),
                    definition.revision(), child.spec().actionId(), child.spec().childId(), action.executorVersion(), draft.type(),
                    draft.schemaVersion(), draft.scopeRef(), draft.periodsRef(), draft.qualityJson(), draft.provenanceJson(),
                    expected.ref().payloadHash(), draft.expiresAt().toEpochMilli(), now());
            jdbc.update("INSERT INTO campaign_artifact_payload (artifact_id,payload_json) VALUES (?,?)",
                    draft.artifactId(), draft.payloadJson());
            jdbc.update("UPDATE campaign_child_ledger SET child_state='READY',artifact_id=?,unresolved_reason=NULL,updated_at=? "
                            + "WHERE run_id=? AND revision=? AND child_id=?",
                    draft.artifactId(), now(), definition.runId(), definition.revision(), permit.childId());
            return expected.ref();
        });
    }

    @Override
    public void markUnresolved(DispatchPermit permit) {
        requireResultMutationPurpose(permit);
        transaction(() -> {
            lockRun(permit.token(), true);
            ChildRecord child = requireAttempt(permit);
            if (child.state() == ChildState.UNRESOLVED) return null;
            if (child.state() != ChildState.DISPATCHING) conflict("ATTEMPT_NOT_DISPATCHING");
            unresolved(permit.token().definition(), child);
            return null;
        });
    }

    @Override
    public void recoverInterrupted(RunToken token) {
        transaction(() -> {
            lockRun(token, true);
            var interrupted = jdbc.query("SELECT * FROM campaign_child_ledger WHERE run_id=? AND revision=? "
                            + "AND child_state='DISPATCHING' FOR UPDATE",
                    (rs, row) -> child(rs), token.definition().runId(), token.definition().revision());
            for (ChildRecord child : interrupted) unresolved(token.definition(), child);
            return null;
        });
    }

    @Override
    public void callbackExited(DispatchPermit permit) {
        transaction(() -> {
            requireFrozenDefinition(permit.token().definition());
            requireAttempt(permit);
            jdbc.update("UPDATE campaign_child_ledger SET callback_active=FALSE,updated_at=? "
                            + "WHERE run_id=? AND revision=? AND child_id=? AND attempt_id=? AND attempt_version=?",
                    now(), permit.token().definition().runId(), permit.token().definition().revision(), permit.childId(),
                    permit.attemptId(), permit.attemptVersion());
            return null;
        });
    }

    @Override
    public ArtifactMetadata inspectArtifact(Caller current, String artifactId, ArtifactAuthorizer authorizer) {
        validateCaller(current);
        id(artifactId, "artifactId", 96);
        Objects.requireNonNull(authorizer, "Current authorization callback is required");
        ArtifactMetadata metadata = findArtifact(artifactId)
                .orElseThrow(() -> new IllegalStateException("ARTIFACT_NOT_FOUND"));
        sameCaller(current, metadata.owner());
        if (!clock.instant().isBefore(metadata.ref().expiresAt())) throw new SecurityException("ARTIFACT_EXPIRED");
        json(metadata.qualityJson(), limits.artifactBytes(), true);
        json(metadata.provenanceJson(), limits.artifactBytes(), true);
        if (!authorizer.mayRead(current, metadata)) throw new SecurityException("ARTIFACT_ACCESS_DENIED");
        return metadata;
    }

    @Override
    public Artifact readArtifact(Caller current, String artifactId, ArtifactAuthorizer authorizer) {
        ArtifactMetadata metadata = inspectArtifact(current, artifactId, authorizer);
        String payload = jdbc.queryForObject("SELECT payload_json FROM campaign_artifact_payload WHERE artifact_id=?",
                String.class, artifactId);
        json(payload, limits.artifactBytes(), false);
        if (!CampaignRunStore.sha256(payload).equals(metadata.ref().payloadHash())) conflict("ARTIFACT_PAYLOAD_CORRUPTED");
        return new Artifact(metadata, payload);
    }

    private void unresolved(RunDefinition definition, ChildRecord child) {
        UnresolvedReason reason = child.spec().mode() == ChildMode.SYNC ? UnresolvedReason.READ_RESULT_UNKNOWN
                : child.jobId() == null ? UnresolvedReason.SUBMISSION_UNRESOLVED : UnresolvedReason.JOB_RESULT_UNKNOWN;
        jdbc.update("UPDATE campaign_child_ledger SET child_state='UNRESOLVED',unresolved_reason=?,updated_at=? "
                        + "WHERE run_id=? AND revision=? AND child_id=?",
                reason.name(), now(), definition.runId(), definition.revision(), child.spec().childId());
    }

    private RunRecord lockRun(RunToken token, boolean active) {
        Objects.requireNonNull(token, "Run token is required");
        RunRecord found = requireFrozenDefinition(token.definition());
        if (found.version() != token.version() || !found.advanceToken().equals(token.advanceToken())) conflict("RUN_TOKEN_FENCED");
        if (active && found.status() != RunStatus.ACTIVE) conflict("RUN_NOT_ACTIVE");
        return found;
    }

    private RunRecord requireFrozenDefinition(RunDefinition definition) {
        RunRecord found = findRun(definition.runId(), definition.revision(), true)
                .orElseThrow(() -> new IllegalStateException("RUN_NOT_FOUND"));
        sameCaller(definition.caller(), found.definition().caller());
        if (!found.definition().equals(definition)) conflict("RUN_DEFINITION_CHANGED");
        return found;
    }

    private ChildRecord requireAttempt(DispatchPermit permit) {
        ChildRecord child = findChild(permit.token().definition(), permit.childId(), true)
                .orElseThrow(() -> new IllegalStateException("CHILD_NOT_FOUND"));
        if (!Objects.equals(child.attemptId(), permit.attemptId()) || child.attemptVersion() != permit.attemptVersion()
                || child.purpose() != permit.purpose()) conflict("ATTEMPT_FENCED");
        Integer matching = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE run_id=? AND revision=? "
                        + "AND child_id=? AND dispatch_run_version=? AND dispatch_run_token=?", Integer.class,
                permit.token().definition().runId(), permit.token().definition().revision(), permit.childId(),
                permit.token().version(), permit.token().advanceToken());
        if (matching == null || matching != 1) conflict("ATTEMPT_RUN_TOKEN_MISMATCH");
        return child;
    }

    private static void requirePinnedAuthorityPage(WireRequest wire) {
        if (!"POST".equals(wire.method()) || !AUTHORITY_PAGE_PATH.equals(wire.path()))
            conflict("AUTHORITY_PAGE_READ_REQUIRES_PINNED_REQUEST");
        try {
            GroupMembersPage.Request request = AUTHORITY_JSON.readValue(wire.bodyJson(), GroupMembersPage.Request.class);
            if (request == null || request.afterLinkId() == null || request.afterLinkId() <= 0
                    || request.ownershipVersion() == null
                    || !request.ownershipVersion().matches("[a-f0-9]{64}"))
                conflict("AUTHORITY_PAGE_READ_REQUIRES_PINNED_REQUEST");
        } catch (java.io.IOException | IllegalArgumentException invalid) {
            throw new IllegalStateException("AUTHORITY_PAGE_READ_REQUIRES_PINNED_REQUEST");
        }
    }

    private static boolean isDeferred(ChildRecord child) {
        return child.state() == ChildState.PREPARED && child.reason() == UnresolvedReason.QUERY_CAPACITY_EXHAUSTED;
    }

    private SubmissionDeferral requireDeferral(RunToken token, ChildRecord child) {
        if (child.spec().mode() != ChildMode.ASYNC || child.purpose() != DispatchPurpose.FRESH
                || child.jobId() != null || child.artifactId() != null)
            conflict("SUBMISSION_DEFERRAL_CORRUPTED");
        SubmissionDeferral proof = findDeferral(token, child)
                .orElseThrow(() -> new IllegalStateException("SUBMISSION_DEFERRAL_MISSING"));
        if (!proof.lastAttemptId().equals(child.attemptId()) || proof.lastAttemptVersion() != child.attemptVersion())
            conflict("SUBMISSION_DEFERRAL_CORRUPTED");
        return proof;
    }

    private Optional<SubmissionDeferral> findDeferral(RunToken token, ChildRecord child) {
        return jdbc.query("SELECT request_id,wire_hash,capacity_kind,rejected_attempts,retry_not_before,"
                        + "last_attempt_id,last_attempt_version FROM campaign_submission_deferral "
                        + "WHERE run_id=? AND revision=? AND child_id=? FOR UPDATE", (rs, row) -> {
            if (!child.spec().requestId().equals(rs.getString("request_id"))
                    || !child.spec().wire().hash().equals(rs.getString("wire_hash")))
                conflict("SUBMISSION_DEFERRAL_CORRUPTED");
            try {
                var proof = new SubmissionDeferral(CapacityKind.valueOf(rs.getString("capacity_kind")),
                        rs.getInt("rejected_attempts"), rs.getLong("retry_not_before"),
                        rs.getString("last_attempt_id"), rs.getLong("last_attempt_version"));
                if (proof.rejectedAttempts() > proof.lastAttemptVersion()) conflict("SUBMISSION_DEFERRAL_CORRUPTED");
                return proof;
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException("SUBMISSION_DEFERRAL_CORRUPTED");
            }
        }, token.definition().runId(), token.definition().revision(), child.spec().childId()).stream().findFirst();
    }

    private long retryDelay(int rejectedAttempts) {
        long delay = submissionBackoff.initialDelayMillis();
        for (int i = 1; i < rejectedAttempts && delay < submissionBackoff.maxDelayMillis(); i++) {
            delay = delay > submissionBackoff.maxDelayMillis() / 2
                    ? submissionBackoff.maxDelayMillis() : delay * 2;
        }
        return delay;
    }

    private void requireNoCallbacks(String runId) {
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE run_id=? AND callback_active=TRUE",
                Integer.class, runId);
        if (active != null && active > 0) conflict("CALLBACK_STILL_ACTIVE");
    }

    private static void requireMatchingJob(ChildRecord child, String jobId) {
        if (child.jobId() != null && !child.jobId().equals(jobId)) conflict("JOB_ID_CONFLICT");
    }

    private static void requireResultMutationPurpose(DispatchPermit permit) {
        Objects.requireNonNull(permit, "Dispatch permit is required");
        if (permit.purpose() == DispatchPurpose.RELEASE) conflict("RELEASE_CANNOT_CHANGE_RESULT");
    }

    private void insertRun(RunToken token) {
        var definition = token.definition();
        jdbc.update("INSERT INTO campaign_run_ledger (run_id,revision,tenant_id,subject_name,auth_version,session_id,plan_id,"
                        + "definition_hash,definition_json,run_status,row_version,advance_token,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,?,?)",
                definition.runId(), definition.revision(), definition.caller().tenantId(), definition.caller().subject(),
                definition.caller().authVersion(), definition.sessionId(), definition.planId(), definition.definitionHash(),
                definition.definitionJson(), token.version(), token.advanceToken(), now(), now());
    }

    private void revoke(RunToken token, RunStatus status) {
        int changed = jdbc.update("UPDATE campaign_run_ledger SET run_status=?,row_version=row_version+1,advance_token=?,updated_at=? "
                        + "WHERE run_id=? AND revision=? AND row_version=? AND advance_token=? AND run_status='ACTIVE'",
                status.name(), freshId(), now(), token.definition().runId(), token.definition().revision(), token.version(), token.advanceToken());
        requireChanged(changed);
    }

    private Optional<RunRecord> findRun(String runId, int revision, boolean lock) {
        return jdbc.query("SELECT * FROM campaign_run_ledger WHERE run_id=? AND revision=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> run(rs), runId, revision).stream().findFirst();
    }

    private Optional<ActionSpec> action(RunToken token, String actionId) {
        return jdbc.query("SELECT * FROM campaign_action_ledger WHERE run_id=? AND revision=? AND action_id=?", (rs, row) -> {
            String definition = rs.getString("definition_json");
            json(definition, limits.definitionBytes(), true);
            if (!CampaignRunStore.sha256(definition).equals(rs.getString("definition_hash"))) conflict("ACTION_DEFINITION_CORRUPTED");
            return new ActionSpec(rs.getString("action_id"), rs.getString("step_id"), rs.getString("executor_kind"),
                    rs.getString("executor_name"), rs.getString("executor_version"), definition);
        }, token.definition().runId(), token.definition().revision(), actionId).stream().findFirst();
    }

    private Optional<ChildRecord> findChild(RunDefinition definition, String childId, boolean lock) {
        return jdbc.query("SELECT * FROM campaign_child_ledger WHERE run_id=? AND revision=? AND child_id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> child(rs), definition.runId(), definition.revision(), childId).stream().findFirst();
    }

    private Optional<ArtifactMetadata> findArtifact(String artifactId) {
        return jdbc.query("SELECT * FROM campaign_artifact WHERE artifact_id=?", (rs, row) -> {
            var ref = new ArtifactRef(rs.getString("artifact_id"), rs.getString("artifact_type"), rs.getString("schema_version"),
                    rs.getString("payload_hash"), rs.getString("scope_ref"), rs.getString("periods_ref"),
                    Instant.ofEpochMilli(rs.getLong("expires_at")));
            return new ArtifactMetadata(ref, new Caller(rs.getString("tenant_id"), rs.getString("subject_name"), rs.getLong("auth_version")),
                    rs.getString("run_id"), rs.getString("plan_id"), rs.getInt("revision"), rs.getString("action_id"),
                    rs.getString("child_id"), rs.getString("executor_version"), rs.getString("quality_json"), rs.getString("provenance_json"));
        }, artifactId).stream().findFirst();
    }

    private RunRecord run(ResultSet rs) throws SQLException {
        String definition = rs.getString("definition_json");
        json(definition, limits.definitionBytes(), true);
        if (!CampaignRunStore.sha256(definition).equals(rs.getString("definition_hash"))) conflict("RUN_DEFINITION_CORRUPTED");
        var owner = new Caller(rs.getString("tenant_id"), rs.getString("subject_name"), rs.getLong("auth_version"));
        return new RunRecord(new RunDefinition(owner, rs.getString("session_id"), rs.getString("run_id"), rs.getString("plan_id"),
                rs.getInt("revision"), definition), RunStatus.valueOf(rs.getString("run_status")), rs.getLong("row_version"),
                rs.getString("advance_token"));
    }

    private ChildRecord child(ResultSet rs) throws SQLException {
        String body = rs.getString("wire_body");
        json(body, limits.requestBytes(), true);
        var wire = new WireRequest(rs.getString("wire_method"), rs.getString("wire_path"), body);
        if (!wire.hash().equals(rs.getString("wire_hash"))) conflict("CHILD_REQUEST_CORRUPTED");
        var spec = new ChildSpec(rs.getString("child_id"), rs.getString("action_id"), ChildMode.valueOf(rs.getString("child_mode")),
                rs.getString("request_id"), wire);
        String purpose = rs.getString("attempt_purpose");
        String reason = rs.getString("unresolved_reason");
        return new ChildRecord(spec, ChildState.valueOf(rs.getString("child_state")), rs.getString("job_id"), rs.getString("artifact_id"),
                rs.getString("attempt_id"), rs.getLong("attempt_version"), purpose == null ? null : DispatchPurpose.valueOf(purpose),
                rs.getBoolean("callback_active"), reason == null ? null : UnresolvedReason.valueOf(reason));
    }

    private ArtifactMetadata metadata(DispatchPermit permit, ChildRecord child, ActionSpec action, ArtifactDraft draft) {
        var definition = permit.token().definition();
        return new ArtifactMetadata(new ArtifactRef(draft.artifactId(), draft.type(), draft.schemaVersion(),
                CampaignRunStore.sha256(draft.payloadJson()), draft.scopeRef(), draft.periodsRef(),
                Instant.ofEpochMilli(draft.expiresAt().toEpochMilli())),
                definition.caller(), definition.runId(), definition.planId(), definition.revision(), child.spec().actionId(),
                child.spec().childId(), action.executorVersion(), draft.qualityJson(), draft.provenanceJson());
    }

    private void validateDefinition(RunDefinition definition) {
        Objects.requireNonNull(definition);
        validateCaller(definition.caller());
        id(definition.sessionId(), "sessionId", 96); id(definition.runId(), "runId", 96); id(definition.planId(), "planId", 96);
        if (definition.revision() < 1) throw new IllegalArgumentException("Invalid revision");
        json(definition.definitionJson(), limits.definitionBytes(), true);
    }

    private void validateAction(ActionSpec action) {
        Objects.requireNonNull(action);
        id(action.actionId(), "actionId", 96); id(action.stepId(), "stepId", 96);
        if (!List.of("TOOL", "SKILL").contains(action.executorKind())) throw new IllegalArgumentException("Invalid executor kind");
        text(action.executorName(), "executorName", 128); text(action.executorVersion(), "executorVersion", 128);
        json(action.definitionJson(), limits.definitionBytes(), true);
    }

    private void validateChild(ChildSpec child) {
        Objects.requireNonNull(child); Objects.requireNonNull(child.mode()); Objects.requireNonNull(child.wire());
        id(child.childId(), "childId", 96); id(child.actionId(), "actionId", 96);
        if (child.requestId() == null || !child.requestId().matches("[A-Za-z0-9_-]{1,96}"))
            throw new IllegalArgumentException("Invalid requestId");
        if (!List.of("GET", "POST").contains(child.wire().method())) throw new IllegalArgumentException("Invalid wire method");
        text(child.wire().path(), "wire path", 2048);
        if (!child.wire().path().startsWith("/") || child.wire().path().startsWith("//"))
            throw new IllegalArgumentException("A service-relative path is required");
        json(child.wire().bodyJson(), limits.requestBytes(), true);
    }

    private void validateArtifact(ArtifactDraft artifact) {
        Objects.requireNonNull(artifact);
        id(artifact.artifactId(), "artifactId", 96); text(artifact.type(), "artifact type", 128);
        text(artifact.schemaVersion(), "schemaVersion", 128); text(artifact.scopeRef(), "scopeRef", 256);
        text(artifact.periodsRef(), "periodsRef", 256);
        if (artifact.expiresAt() == null || !clock.instant().isBefore(artifact.expiresAt()))
            throw new IllegalArgumentException("Artifact expiry must be in the future");
        json(artifact.payloadJson(), limits.artifactBytes(), false);
        json(artifact.qualityJson(), limits.artifactBytes(), true);
        json(artifact.provenanceJson(), limits.artifactBytes(), true);
    }

    private static void validateCaller(Caller caller) {
        Objects.requireNonNull(caller);
        id(caller.tenantId(), "tenantId", 96); text(caller.subject(), "subject", 128);
        if (caller.authVersion() < 1) throw new IllegalArgumentException("Current authVersion is required");
    }

    private static void sameCaller(Caller current, Caller expected) {
        if (!expected.equals(current)) throw new SecurityException("LEDGER_SUBJECT_MISMATCH");
    }

    private static void id(String value, String name, int length) {
        text(value, name, length);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*")) throw new IllegalArgumentException("Invalid " + name);
    }

    private static void text(String value, String name, int length) {
        if (value == null || value.isBlank() || value.length() > length || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid " + name);
    }

    /** Count encoded bytes without allocating another full payload; validate JSON with a streaming parser. */
    private static void json(String value, int byteLimit, boolean object) {
        if (value == null) throw new IllegalArgumentException("JSON is required");
        long bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) bytes++;
            else if (c < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                bytes += 4; i++;
            } else bytes += Character.isSurrogate(c) ? 1 : 3;
            if (bytes > byteLimit) throw new IllegalArgumentException("JSON byte limit exceeded");
        }
        try (var parser = JSON.createParser(value)) {
            JsonToken token = parser.nextToken();
            if (token == null || (object && token != JsonToken.START_OBJECT)) throw new IllegalArgumentException("Invalid JSON contract");
            parser.skipChildren();
            if (parser.nextToken() != null) throw new IllegalArgumentException("Invalid JSON contract");
        } catch (java.io.IOException malformed) {
            throw new IllegalArgumentException("Invalid JSON contract");
        }
    }

    private <T> T transaction(Supplier<T> work) {
        try { return transactions.execute(status -> work.get()); }
        catch (DataIntegrityViolationException conflict) { throw new IllegalStateException("LEDGER_IDENTITY_CONFLICT"); }
    }

    private static void conflict(String code) { throw new IllegalStateException(code); }
    private static void requireChanged(int changed) { if (changed != 1) conflict("RUN_TOKEN_FENCED"); }
    private static String freshId() { return UUID.randomUUID().toString(); }
    private long now() { return clock.millis(); }
}
