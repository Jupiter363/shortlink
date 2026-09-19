package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactAuthorizer;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactMetadata;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildMode;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildState;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.UnresolvedReason;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local.LocalCalculationRegistry.Approval;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Standalone durable step adapter; no scheduler, native checkpoint, or Spring registration.
 * Every mutation locks the existing run row first, then the step. Callback exit is a real caller
 * signal, never inferred from a timeout or from reconstructing this store.
 */
public final class JdbcCampaignStepStore implements CampaignStepStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final CampaignRunStore.Limits limits;
    private final JdbcCampaignRunStore runs;

    public JdbcCampaignStepStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this(jdbc, transactions, clock, CampaignRunStore.Limits.defaults());
    }

    public JdbcCampaignStepStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
                                CampaignRunStore.Limits limits) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        this.limits = Objects.requireNonNull(limits);
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly())
            throw new IllegalArgumentException("Step and run ledgers require one writable REQUIRED DataSource transaction");
        this.runs = new JdbcCampaignRunStore(jdbc, transactions, clock, limits);
    }

    @Override
    public ProgressSnapshot snapshot(Caller caller, String runId) {
        Objects.requireNonNull(caller, "Current caller is required");
        id(runId, 96);
        return transaction(() -> {
            SnapshotRun locked = jdbc.query("SELECT revision,run_status,row_version,advance_token FROM campaign_run_ledger "
                            + "WHERE run_id=? ORDER BY revision DESC LIMIT 1 FOR UPDATE",
                    (rs, row) -> new SnapshotRun(rs.getInt("revision"), rs.getString("run_status"),
                            rs.getLong("row_version"), rs.getString("advance_token")), runId)
                    .stream().findFirst().orElseThrow(() -> new IllegalStateException("RUN_NOT_FOUND"));
            var run = runs.loadRun(caller, runId).orElseThrow(() -> new IllegalStateException("RUN_NOT_FOUND"));
            // A concurrent revision may have committed while the first locking read was waiting.
            // Refuse a mixed result; do not turn this read into a polling or writer-acquisition loop.
            if (run.definition().revision() != locked.revision() || run.version() != locked.version()
                    || !run.status().name().equals(locked.status()) || !run.advanceToken().equals(locked.advanceToken()))
                fail("PROGRESS_SNAPSHOT_CHANGED");
            // Current locking reads also avoid an enclosing REPEATABLE_READ transaction's older step view.
            var steps = jdbc.query("SELECT * FROM campaign_step_ledger WHERE run_id=? AND revision=? "
                            + "ORDER BY ordinal_index FOR UPDATE", (rs, row) -> stored(rs).record(), runId, locked.revision());
            return new ProgressSnapshot(run, steps);
        });
    }

    @Override
    public void initialize(RunToken token, List<StepSpec> specifications) {
        List<StepSpec> frozen = List.copyOf(specifications);
        validatePlan(frozen);
        transaction(() -> {
            requireRun(token, true);
            List<StepRecord> existing = findSteps(token);
            if (!existing.isEmpty()) {
                if (!existing.stream().map(StepRecord::spec).toList().equals(frozen)) fail("STEP_DEFINITIONS_CHANGED");
                return null;
            }
            for (int i = 0; i < frozen.size(); i++) {
                StepSpec spec = frozen.get(i);
                jdbc.update("INSERT INTO campaign_step_ledger (run_id,revision,step_id,ordinal_index,definition_json,"
                                + "depends_on_json,allowed_outputs_json,required_outputs_json,specification_hash,step_status,"
                                + "row_version,attempt_version,callback_active,outputs_json,created_at,updated_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,'PENDING',0,0,FALSE,'{}',?,?)",
                        token.definition().runId(), token.definition().revision(), spec.stepId(), i, spec.definitionJson(),
                        encode(spec.dependsOn()), encode(spec.allowedOutputs().stream().sorted().toList()),
                        encode(spec.requiredOutputs().stream().sorted().toList()), specificationHash(spec), now(), now());
            }
            return null;
        });
    }

    @Override
    public RunToken acquireRun(RunToken token) {
        return transaction(() -> {
            requireRun(token, true);
            requireNoCallbacks(token.definition().runId());
            return runs.advance(token);
        });
    }

    @Override
    public Optional<StepRecord> step(RunToken token, String stepId) {
        id(stepId, 96);
        return transaction(() -> {
            requireRun(token, true);
            return findStep(token, stepId, false).map(StoredStep::record);
        });
    }

    @Override
    public List<StepRecord> steps(RunToken token) {
        return transaction(() -> {
            requireRun(token, true);
            return findSteps(token);
        });
    }

    @Override
    public boolean mayAdvance(RunToken token) {
        return transaction(() -> {
            try {
                requireRun(token, true);
                requireNoCallbacks(token.definition().runId());
                return true;
            } catch (IllegalStateException | SecurityException denied) {
                return false;
            }
        });
    }

    @Override
    public boolean mayExecute(StepPermit permit) {
        return transaction(() -> {
            try {
                requireRun(permit.runToken(), true);
                StepRecord step = requireAttempt(permit).record();
                return step.status() == StepStatus.RUNNING && step.callbackActive();
            } catch (IllegalStateException | SecurityException denied) {
                return false;
            }
        });
    }

    @Override
    public StepPermit beginStep(RunToken token, String stepId) {
        id(stepId, 96);
        return transaction(() -> {
            requireRun(token, true);
            requireNoCallbacks(token.definition().runId());
            StoredStep stored = requireStep(token, stepId);
            StepRecord step = stored.record();
            if (step.status() != StepStatus.PENDING && step.status() != StepStatus.READY) fail("STEP_NOT_EXECUTABLE");
            for (String dependency : step.spec().dependsOn()) {
                if (requireStep(token, dependency).record().status() != StepStatus.SUCCEEDED)
                    fail("STEP_DEPENDENCY_NOT_SUCCEEDED");
            }
            var permit = new StepPermit(token, stepId, UUID.randomUUID().toString(), Math.addExact(stored.attemptVersion(), 1));
            jdbc.update("UPDATE campaign_step_ledger SET step_status='RUNNING',row_version=row_version+1,attempt_id=?,"
                            + "attempt_version=?,dispatch_run_version=?,dispatch_run_token=?,callback_active=TRUE,reason=NULL,"
                            + "updated_at=? WHERE run_id=? AND revision=? AND step_id=?",
                    permit.attemptId(), permit.attemptVersion(), token.version(), token.advanceToken(), now(),
                    token.definition().runId(), token.definition().revision(), stepId);
            return permit;
        });
    }

    @Override
    public StepRecord settle(StepPermit permit, StepStatus target, Map<String, String> outputIds,
                             String reason, ArtifactAuthorizer authorizer) {
        if (target != StepStatus.WAITING && target != StepStatus.BLOCKED && target != StepStatus.FAILED
                && target != StepStatus.SUCCEEDED) throw new IllegalArgumentException("Invalid settled step status");
        Objects.requireNonNull(authorizer, "Current artifact authorization is required");
        Map<String, String> outputs = Map.copyOf(outputIds);
        outputs.forEach((name, artifactId) -> { id(name, 128); id(artifactId, 96); });
        reason(reason);
        return transaction(() -> {
            RunToken token = permit.runToken();
            requireRun(token, true);
            StepRecord step = requireAttempt(permit).record();
            if (!step.callbackActive()) fail("STEP_CALLBACK_NOT_ACTIVE");
            if (!step.spec().allowedOutputs().containsAll(outputs.keySet())) fail("STEP_OUTPUT_NOT_ALLOWED");
            if (target == StepStatus.SUCCEEDED && !outputs.keySet().containsAll(step.spec().requiredOutputs()))
                fail("STEP_REQUIRED_OUTPUT_MISSING");
            if (step.status() != StepStatus.RUNNING
                    && (step.status() != target || !step.outputs().equals(outputs) || !Objects.equals(step.reason(), reason)))
                fail("STEP_ALREADY_SETTLED");
            List<ChildReceipt> children = childReceipts(token, permit.stepId());
            if (target == StepStatus.WAITING && children.stream().noneMatch(child ->
                    child.state().equals("WAITING") && child.jobId() != null && !child.jobId().isBlank()))
                fail("STEP_WAITING_JOB_REQUIRED");
            if (target == StepStatus.SUCCEEDED && children.stream().anyMatch(child -> !child.state().equals("READY")))
                fail("STEP_CHILD_RESULT_INCOMPLETE");
            for (String artifactId : new HashSet<>(outputs.values())) {
                var artifact = runs.inspectArtifact(token.definition().caller(), artifactId, authorizer);
                if (!readyArtifact(artifact, artifactId)) fail("STEP_OUTPUT_ARTIFACT_NOT_READY");
            }
            if (step.status() != StepStatus.RUNNING) return step;
            jdbc.update("UPDATE campaign_step_ledger SET step_status=?,outputs_json=?,reason=?,row_version=row_version+1,"
                            + "updated_at=? WHERE run_id=? AND revision=? AND step_id=?",
                    target.name(), encode(new TreeMap<>(outputs)), reason, now(), token.definition().runId(),
                    token.definition().revision(), permit.stepId());
            return requireStep(token, permit.stepId()).record();
        });
    }

    @Override
    public void callbackExited(StepPermit permit) {
        transaction(() -> {
            requireRun(permit.runToken(), false);
            StepRecord step = requireAttempt(permit).record();
            if (!step.callbackActive()) return null;
            boolean unknown = step.status() == StepStatus.RUNNING;
            jdbc.update("UPDATE campaign_step_ledger SET callback_active=FALSE,step_status=?,reason=?,row_version=row_version+1,"
                            + "updated_at=? WHERE run_id=? AND revision=? AND step_id=?",
                    unknown ? StepStatus.BLOCKED.name() : step.status().name(), unknown ? "STEP_RESULT_UNKNOWN" : step.reason(),
                    now(), permit.runToken().definition().runId(), permit.runToken().definition().revision(), permit.stepId());
            return null;
        });
    }

    @Override
    public StepRecord refreshWaiting(RunToken token, String stepId) {
        id(stepId, 96);
        return transaction(() -> {
            requireRun(token, true);
            StepRecord step = requireStep(token, stepId).record();
            if (step.status() != StepStatus.WAITING
                    && !(step.status() == StepStatus.BLOCKED && "STEP_RESULT_UNKNOWN".equals(step.reason()))) return step;
            if (hasCallbacks(token.definition().runId())) return step;
            List<ChildReceipt> children = childReceipts(token, stepId);
            if (children.isEmpty() || children.stream().anyMatch(child -> !child.state().equals("READY"))) return step;
            jdbc.update("UPDATE campaign_step_ledger SET step_status='READY',reason=NULL,row_version=row_version+1,updated_at=? "
                            + "WHERE run_id=? AND revision=? AND step_id=?",
                    now(), token.definition().runId(), token.definition().revision(), stepId);
            return requireStep(token, stepId).record();
        });
    }

    @Override
    public StepRecord refreshCapacityDeferred(RunToken token, String stepId) {
        id(stepId, 96);
        return transaction(() -> {
            requireRun(token, true);
            StepRecord step = requireStep(token, stepId).record();
            if (step.status() != StepStatus.WAITING && !(step.status() == StepStatus.BLOCKED
                    && ("REMOTE_CAPACITY".equals(step.reason()) || "STEP_RESULT_UNKNOWN".equals(step.reason()))))
                return step;
            if (hasCallbacks(token.definition().runId())) return step;
            List<ChildReceipt> children = childReceipts(token, stepId);
            boolean provenRejection = false;
            for (ChildReceipt child : children) {
                if (child.state().equals("READY")) continue;
                if (!child.state().equals("PREPARED")) return step;
                if ("QUERY_CAPACITY_EXHAUSTED".equals(child.reason())) {
                    var proof = runs.submissionDeferral(token, child.childId())
                            .orElseThrow(() -> new IllegalStateException("SUBMISSION_DEFERRAL_MISSING"));
                    if (proof.retryNotBeforeMillis() > now()) return step;
                    provenRejection = true;
                } else if (child.reason() != null) {
                    return step;
                }
            }
            if (!provenRejection) return step;
            for (String dependency : step.spec().dependsOn()) {
                if (requireStep(token, dependency).record().status() != StepStatus.SUCCEEDED) return step;
            }
            jdbc.update("UPDATE campaign_step_ledger SET step_status='READY',reason=NULL,row_version=row_version+1,updated_at=? "
                            + "WHERE run_id=? AND revision=? AND step_id=?",
                    now(), token.definition().runId(), token.definition().revision(), stepId);
            return requireStep(token, stepId).record();
        });
    }

    @Override
    public StepRecord refreshLocalReplay(RunToken token, String stepId, Map<String, Approval> supplied,
                                         ArtifactAuthorizer authorizer) {
        id(stepId, 96);
        Map<String, Approval> approvals = Map.copyOf(supplied);
        Objects.requireNonNull(authorizer);
        return transaction(() -> {
            requireRun(token, true);
            StepRecord step = requireStep(token, stepId).record();
            if (step.status() != StepStatus.BLOCKED || !"STEP_RESULT_UNKNOWN".equals(step.reason())) return step;
            if (hasCallbacks(token.definition().runId())) return step;
            boolean unknownLocal = false;
            for (ChildReceipt receipt : childReceipts(token, stepId)) {
                if ("READY".equals(receipt.state())) continue;
                var child = runs.child(token, receipt.childId()).orElseThrow();
                if (child.spec().mode() != ChildMode.LOCAL || child.state() != ChildState.UNRESOLVED
                        || child.reason() != UnresolvedReason.LOCAL_RESULT_UNKNOWN) return step;
                Approval approved = approvals.get(receipt.childId());
                if (approved == null) return step;
                if (!approved.invocation().equals(child.spec().localInvocation())) fail("LOCAL_INVOCATION_CHANGED");
                if (!clock.instant().isBefore(approved.invocation().expiresAt()))
                    throw new SecurityException("LOCAL_INVOCATION_EXPIRED");
                for (ArtifactMetadata input : approved.invocation().inputs().values()) {
                    var actual = runs.readArtifact(token.definition().caller(), input.ref().artifactId(), authorizer);
                    if (!input.equals(actual.metadata())) fail("LOCAL_INPUT_CHANGED");
                    if (approved.invocation().expiresAt().isAfter(input.ref().expiresAt())) fail("LOCAL_EXPIRY_EXCEEDS_INPUT");
                }
                unknownLocal = true;
            }
            if (!unknownLocal) return step;
            for (String dependency : step.spec().dependsOn()) {
                if (requireStep(token, dependency).record().status() != StepStatus.SUCCEEDED) return step;
            }
            jdbc.update("UPDATE campaign_step_ledger SET step_status='READY',reason=NULL,row_version=row_version+1,updated_at=? "
                            + "WHERE run_id=? AND revision=? AND step_id=?",
                    now(), token.definition().runId(), token.definition().revision(), stepId);
            return requireStep(token, stepId).record();
        });
    }

    /** LOCAL outputs have a named association; remote single-output children keep their old proof. */
    private boolean readyArtifact(ArtifactMetadata artifact, String artifactId) {
        var children = jdbc.queryForList("SELECT child_mode,artifact_id FROM campaign_child_ledger "
                        + "WHERE run_id=? AND revision=? AND child_id=? AND child_state='READY'",
                artifact.runId(), artifact.revision(), artifact.childId());
        if (children.size() != 1) return false;
        if (!"LOCAL".equals(children.get(0).get("child_mode")))
            return artifactId.equals(children.get(0).get("artifact_id"));
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_local_output WHERE run_id=? AND revision=? "
                        + "AND child_id=? AND artifact_id=?", Integer.class,
                artifact.runId(), artifact.revision(), artifact.childId(), artifactId);
        return count != null && count == 1;
    }

    private void requireRun(RunToken token, boolean current) {
        Objects.requireNonNull(token);
        var definition = Objects.requireNonNull(token.definition());
        var found = jdbc.query("SELECT tenant_id,subject_name,auth_version,session_id,plan_id,definition_hash,"
                        + "run_status,row_version,advance_token FROM campaign_run_ledger WHERE run_id=? AND revision=? FOR UPDATE",
                (rs, row) -> new LockedRun(rs.getString("tenant_id"), rs.getString("subject_name"), rs.getLong("auth_version"),
                        rs.getString("session_id"), rs.getString("plan_id"), rs.getString("definition_hash"),
                        rs.getString("run_status"), rs.getLong("row_version"), rs.getString("advance_token")),
                definition.runId(), definition.revision()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("RUN_NOT_FOUND"));
        if (!Objects.equals(found.tenant(), definition.caller().tenantId())
                || !Objects.equals(found.subject(), definition.caller().subject())
                || found.authVersion() != definition.caller().authVersion()) throw new SecurityException("LEDGER_SUBJECT_MISMATCH");
        if (!Objects.equals(found.session(), definition.sessionId()) || !Objects.equals(found.plan(), definition.planId())
                || !Objects.equals(found.definitionHash(), definition.definitionHash())) fail("RUN_DEFINITION_CHANGED");
        if (current && (!found.status().equals("ACTIVE") || found.version() != token.version()
                || !found.advanceToken().equals(token.advanceToken()))) fail("RUN_TOKEN_FENCED");
    }

    private StoredStep requireAttempt(StepPermit permit) {
        StoredStep step = requireStep(permit.runToken(), permit.stepId());
        if (!Objects.equals(step.record().attemptId(), permit.attemptId()) || step.attemptVersion() != permit.attemptVersion()
                || step.dispatchRunVersion() != permit.runToken().version()
                || !Objects.equals(step.dispatchRunToken(), permit.runToken().advanceToken())) fail("STEP_ATTEMPT_FENCED");
        return step;
    }

    private StoredStep requireStep(RunToken token, String stepId) {
        return findStep(token, stepId, true).orElseThrow(() -> new IllegalStateException("STEP_NOT_FOUND"));
    }

    private Optional<StoredStep> findStep(RunToken token, String stepId, boolean lock) {
        return jdbc.query("SELECT * FROM campaign_step_ledger WHERE run_id=? AND revision=? AND step_id=?"
                        + (lock ? " FOR UPDATE" : ""), (rs, row) -> stored(rs), token.definition().runId(),
                token.definition().revision(), stepId).stream().findFirst();
    }

    private List<StepRecord> findSteps(RunToken token) {
        return jdbc.query("SELECT * FROM campaign_step_ledger WHERE run_id=? AND revision=? ORDER BY ordinal_index",
                (rs, row) -> stored(rs).record(), token.definition().runId(), token.definition().revision());
    }

    private List<ChildReceipt> childReceipts(RunToken token, String stepId) {
        return jdbc.query("SELECT c.child_id,c.child_state,c.job_id,c.unresolved_reason FROM campaign_child_ledger c JOIN campaign_action_ledger a "
                        + "ON a.run_id=c.run_id AND a.revision=c.revision AND a.action_id=c.action_id "
                        + "WHERE c.run_id=? AND c.revision=? AND a.step_id=? ORDER BY c.child_id FOR UPDATE",
                (rs, row) -> new ChildReceipt(rs.getString("child_id"), rs.getString("child_state"),
                        rs.getString("job_id"), rs.getString("unresolved_reason")),
                token.definition().runId(), token.definition().revision(), stepId);
    }

    private boolean hasCallbacks(String runId) {
        Integer steps = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_step_ledger WHERE run_id=? AND callback_active=TRUE",
                Integer.class, runId);
        Integer children = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE run_id=? AND callback_active=TRUE",
                Integer.class, runId);
        return (steps != null && steps > 0) || (children != null && children > 0);
    }

    private void requireNoCallbacks(String runId) { if (hasCallbacks(runId)) fail("CALLBACK_STILL_ACTIVE"); }

    private StoredStep stored(ResultSet rs) throws SQLException {
        StepSpec spec = new StepSpec(rs.getString("step_id"), rs.getString("definition_json"),
                decode(rs.getString("depends_on_json"), STRING_LIST),
                Set.copyOf(decode(rs.getString("allowed_outputs_json"), STRING_LIST)),
                Set.copyOf(decode(rs.getString("required_outputs_json"), STRING_LIST)));
        validateSpec(spec);
        if (!specificationHash(spec).equals(rs.getString("specification_hash"))) fail("STEP_DEFINITION_CORRUPTED");
        StepRecord record = new StepRecord(spec, StepStatus.valueOf(rs.getString("step_status")), rs.getLong("row_version"),
                rs.getString("attempt_id"), rs.getBoolean("callback_active"), rs.getString("reason"),
                decode(rs.getString("outputs_json"), STRING_MAP));
        return new StoredStep(record, rs.getLong("attempt_version"), rs.getLong("dispatch_run_version"),
                rs.getString("dispatch_run_token"));
    }

    private void validatePlan(List<StepSpec> specifications) {
        if (specifications.isEmpty()) throw new IllegalArgumentException("A nonempty frozen step set is required");
        Map<String, StepSpec> indexed = new LinkedHashMap<>();
        for (StepSpec spec : specifications) {
            validateSpec(spec);
            if (indexed.putIfAbsent(spec.stepId(), spec) != null) throw new IllegalArgumentException("Duplicate step ID");
        }
        Map<String, Integer> remaining = new HashMap<>();
        Map<String, List<String>> dependants = new HashMap<>();
        for (StepSpec spec : specifications) {
            remaining.put(spec.stepId(), spec.dependsOn().size());
            for (String dependency : spec.dependsOn()) {
                if (!indexed.containsKey(dependency)) throw new IllegalArgumentException("Unknown step dependency");
                dependants.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(spec.stepId());
            }
        }
        List<String> ready = new ArrayList<>();
        remaining.forEach((step, count) -> { if (count == 0) ready.add(step); });
        for (int i = 0; i < ready.size(); i++) {
            for (String dependant : dependants.getOrDefault(ready.get(i), List.of())) {
                if (remaining.compute(dependant, (key, count) -> count - 1) == 0) ready.add(dependant);
            }
        }
        if (ready.size() != specifications.size()) throw new IllegalArgumentException("Cyclic step dependencies");
    }

    private void validateSpec(StepSpec spec) {
        Objects.requireNonNull(spec);
        id(spec.stepId(), 96);
        checkBytes(spec.definitionJson());
        try (var parser = JSON.getFactory().createParser(spec.definitionJson())) {
            if (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.START_OBJECT)
                throw new IllegalArgumentException("Step definition must be an object");
            parser.skipChildren();
            if (parser.nextToken() != null) throw new IllegalArgumentException("Invalid step definition");
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid step definition"); }
        spec.dependsOn().forEach(dependency -> id(dependency, 96));
        if (new HashSet<>(spec.dependsOn()).size() != spec.dependsOn().size())
            throw new IllegalArgumentException("Duplicate step dependency");
        spec.allowedOutputs().forEach(output -> id(output, 128));
        spec.requiredOutputs().forEach(output -> id(output, 128));
        if (!spec.allowedOutputs().containsAll(spec.requiredOutputs()))
            throw new IllegalArgumentException("Required outputs must be allowed outputs");
    }

    private String specificationHash(StepSpec spec) {
        return CampaignRunStore.sha256(encode(List.of(spec.stepId(), CampaignRunStore.sha256(spec.definitionJson()),
                spec.dependsOn(), spec.allowedOutputs().stream().sorted().toList(),
                spec.requiredOutputs().stream().sorted().toList())));
    }

    private String encode(Object value) {
        try {
            String encoded = JSON.writeValueAsString(value);
            checkBytes(encoded);
            return encoded;
        }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("Invalid step contract"); }
    }

    private <T> T decode(String value, TypeReference<T> type) {
        checkBytes(value);
        try { return JSON.readValue(value, type); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("STEP_RECORD_CORRUPTED"); }
    }

    private void checkBytes(String value) {
        if (value == null) throw new IllegalArgumentException("Step JSON is required");
        long bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) bytes++;
            else if (c < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                bytes += 4; i++;
            } else bytes += Character.isSurrogate(c) ? 1 : 3;
            if (bytes > limits.definitionBytes()) throw new IllegalArgumentException("Step JSON byte limit exceeded");
        }
    }

    private static void id(String value, int maximum) {
        if (value == null || value.length() > maximum || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*"))
            throw new IllegalArgumentException("Invalid step or artifact identifier");
    }

    private static void reason(String value) {
        if (value != null && (value.isBlank() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl)))
            throw new IllegalArgumentException("A bounded step reason is required");
    }

    private <T> T transaction(Supplier<T> work) {
        try { return transactions.execute(status -> work.get()); }
        catch (DataIntegrityViolationException conflict) { throw new IllegalStateException("STEP_IDENTITY_CONFLICT"); }
    }

    private static void fail(String reason) { throw new IllegalStateException(reason); }
    private long now() { return clock.millis(); }
    private record LockedRun(String tenant, String subject, long authVersion, String session, String plan,
                             String definitionHash, String status, long version, String advanceToken) {}
    private record StoredStep(StepRecord record, long attemptVersion, long dispatchRunVersion, String dispatchRunToken) {}
    private record ChildReceipt(String childId, String state, String jobId, String reason) {}
    private record SnapshotRun(int revision, String status, long version, String advanceToken) {}
}
