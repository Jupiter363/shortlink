package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanBinding;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry.BoundArtifact;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.BindingException;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.CompletionCriterionRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.ExplorationCandidate;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.ExplorationArtifactBoundary;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Typed candidate acceptance receipts. This component never settles a Step or changes a Plan. */
public final class JdbcCampaignExplorationCandidateStore implements CampaignExplorationCandidateStore {
    private static final JsonMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private static final Set<String> ASSESSED_BLOCKS = Set.of("EXPLORATION_NEEDS_INPUT", "EXPLORATION_REPLAN_REQUESTED",
            "EXPLORATION_NO_PROGRESS_REPORTED", "EXPLORATION_CANDIDATE_REJECTED");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final CampaignRunStore runs;
    private final CampaignStepStore steps;
    private final ModelInvocationRegistry models;
    private final CapabilityCatalog catalog;
    private final ArtifactContractRegistry artifacts;
    private final CompletionCriterionRegistry criteria;
    private final ArtifactAuthorizer authorizer;
    private final JdbcExplorationCallbackGate callbacks;
    private final ExplorationArtifactBoundary artifactBoundary;

    public JdbcCampaignExplorationCandidateStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
            CampaignRunStore runs, CampaignStepStore steps, ModelInvocationRegistry models, CapabilityCatalog catalog,
            ArtifactContractRegistry artifacts, CompletionCriterionRegistry criteria, ArtifactAuthorizer authorizer) {
        this(jdbc, transactions, clock, runs, steps, models, catalog, artifacts, criteria, authorizer,
                ExplorationArtifactBoundary.exact());
    }

    public JdbcCampaignExplorationCandidateStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
            CampaignRunStore runs, CampaignStepStore steps, ModelInvocationRegistry models, CapabilityCatalog catalog,
            ArtifactContractRegistry artifacts, CompletionCriterionRegistry criteria, ArtifactAuthorizer authorizer,
            ExplorationArtifactBoundary artifactBoundary) {
        this.jdbc = Objects.requireNonNull(jdbc); this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock); this.runs = Objects.requireNonNull(runs);
        this.steps = Objects.requireNonNull(steps); this.models = Objects.requireNonNull(models);
        this.catalog = Objects.requireNonNull(catalog); this.artifacts = Objects.requireNonNull(artifacts);
        this.criteria = Objects.requireNonNull(criteria); this.authorizer = Objects.requireNonNull(authorizer);
        this.artifactBoundary = Objects.requireNonNull(artifactBoundary);
        require(artifactBoundary.configurationId() != null && !artifactBoundary.configurationId().isBlank()
                && artifactBoundary.configurationId().length() <= 512, "CANDIDATE_BOUNDARY_CONFIGURATION_INVALID");
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource() || transactions.isReadOnly()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || !(runs instanceof JdbcCampaignRunStore actual) || !actual.sharesTransactionDataSource(jdbc)
                || !(steps instanceof JdbcCampaignStepStore actualSteps) || !actualSteps.sharesTransactionDataSource(jdbc))
            throw failure("CANDIDATE_REQUIRES_SHARED_TRANSACTION");
        this.callbacks = new JdbcExplorationCallbackGate(jdbc);
        jdbc.query("SELECT assessment_id FROM campaign_exploration_candidate WHERE 1=0", (rs, row) -> rs.getString(1));
    }

    @Override public String configurationId(RunDefinition definition, String stepId) {
        return policy(definition, stepId).registryId();
    }

    @Override public Assessment assess(StepPermit step, String modelChildId) {
        Objects.requireNonNull(step); Objects.requireNonNull(modelChildId);
        return tx(() -> {
            lockRun(step.runToken());
            lockStep(step.runToken(), step.stepId(), step);
            Source source = source(step.runToken(), step.stepId(), modelChildId);
            Assessment existing = find(step.runToken(), step.stepId()).orElse(null);
            if (existing != null) return revalidate(source, existing);
            Assessment assessment = evaluate(source);
            String body = encode(assessment);
            bounded(body);
            jdbc.update("INSERT INTO campaign_exploration_candidate (run_id,revision,step_id,assessment_id,model_child_id,response_hash,"
                            + "candidate_hash,registry_id,verdict,assessment_json,assessment_hash,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    step.runToken().definition().runId(), step.runToken().definition().revision(), step.stepId(), assessment.assessmentId(),
                    modelChildId, assessment.responseHash(), assessment.candidateHash(), assessment.registryId(), assessment.verdict().name(),
                    body, CampaignRunStore.sha256(body), clock.millis());
            return assessment;
        });
    }

    @Override public Optional<Assessment> assessment(RunToken token, String stepId) {
        return tx(() -> {
            lockRun(token);
            lockStep(token, stepId, null);
            Assessment existing = find(token, stepId).orElse(null);
            if (existing == null) return Optional.empty();
            return Optional.of(revalidate(source(token, stepId, existing.modelChildId()), existing));
        });
    }

    @Override public CampaignStepStore.StepRecord settleComplete(StepPermit permit) {
        Objects.requireNonNull(permit);
        return tx(() -> {
            RunToken token = permit.runToken();
            lockRun(token);
            lockStep(token, permit.stepId(), permit);
            Assessment existing = find(token, permit.stepId())
                    .orElseThrow(() -> failure("CANDIDATE_ASSESSMENT_MISSING"));
            Assessment actual = revalidate(source(token, permit.stepId(), existing.modelChildId()), existing);
            require(actual.verdict() == Verdict.COMPLETE, "CANDIDATE_NOT_COMPLETE");
            Map<String, String> outputs = new TreeMap<>();
            actual.outputs().forEach((name, artifact) -> outputs.put(name, artifact.artifactId()));
            return steps.settle(permit, CampaignStepStore.StepStatus.SUCCEEDED, outputs, null, authorizer);
        });
    }

    private Assessment revalidate(Source source, Assessment existing) {
        require(existing.modelChildId().equals(source.child().spec().childId())
                        && existing.responseHash().equals(source.responseHash())
                        && existing.registryId().equals(source.policy().registryId()), "CANDIDATE_ASSESSMENT_CHANGED");
        Assessment current = evaluate(source);
        require(current.equals(existing), "CANDIDATE_ASSESSMENT_CHANGED");
        return existing;
    }

    private Assessment evaluate(Source source) {
        ExplorationCandidate candidate;
        try { candidate = ExplorationCandidate.parse(source.response().text()); }
        catch (IllegalArgumentException invalid) {
            return result(source, CampaignRunStore.sha256(source.response().text()), Verdict.REJECTED, Map.of(), List.of(),
                    List.of("EXPLORATION_CANDIDATE_INVALID"));
        }
        String candidateHash = CampaignRunStore.sha256(candidate.encode());
        try {
            Map<String, ArtifactMetadata> visible = visible(source);
            Map<String, ArtifactMetadata> evidence = new TreeMap<>();
            for (String id : candidate.evidenceArtifactIds()) {
                ArtifactMetadata metadata = visible.get(id);
                valid(metadata != null, "CANDIDATE_EVIDENCE_NOT_VISIBLE");
                ArtifactMetadata actual = readable(source, metadata);
                validBoundary(source, actual);
                evidence.put(id, actual);
            }
            if (candidate.kind() != ExplorationCandidate.Kind.COMPLETE) {
                Verdict verdict = switch (candidate.kind()) {
                    case NEEDS_INPUT -> Verdict.NEEDS_INPUT;
                    case REQUEST_REPLAN -> Verdict.REPLAN_REQUESTED;
                    case NO_PROGRESS -> Verdict.NO_PROGRESS_REPORTED;
                    default -> throw failure("CANDIDATE_KIND_INVALID");
                };
                return result(source, candidateHash, verdict, Map.of(), List.of(), List.of("CANDIDATE_" + verdict.name()));
            }
            var ports = source.policy().registered().signature().outputs();
            valid(ports.keySet().containsAll(candidate.outputBindings().keySet()), "CANDIDATE_OUTPUT_PORT_INVALID");
            for (var entry : ports.entrySet()) valid(!entry.getValue().required() || candidate.outputBindings().containsKey(entry.getKey()),
                    "CANDIDATE_REQUIRED_OUTPUT_MISSING");
            Map<String, BoundArtifact> bound = new TreeMap<>();
            Map<String, ArtifactRef> outputs = new TreeMap<>();
            for (var entry : candidate.outputBindings().entrySet()) {
                PlanBinding binding = entry.getValue();
                valid(binding != null && binding.source() == PlanBinding.Source.ARTIFACT && binding.input() == null
                                && binding.stepId() == null && binding.output() == null && binding.artifactId() != null,
                        "CANDIDATE_OUTPUT_BINDING_INVALID");
                ArtifactMetadata frozen = visible.get(binding.artifactId());
                valid(frozen != null, "CANDIDATE_OUTPUT_NOT_VISIBLE");
                ArtifactMetadata actual = readable(source, frozen);
                validBoundary(source, actual);
                BoundArtifact artifact;
                try { artifact = artifacts.validateArtifact(ports.get(entry.getKey()).type(), binding.artifactId(), runs,
                        source.token().definition().caller(), authorizer); }
                catch (BindingException invalid) { throw new InvalidCandidate("CANDIDATE_OUTPUT_CONTRACT_MISMATCH"); }
                require(actual.equals(artifact.metadata()), "CANDIDATE_ARTIFACT_CHANGED");
                bound.put(entry.getKey(), artifact); outputs.put(entry.getKey(), actual.ref());
            }
            var context = new CompletionCriterionRegistry.Context(source.token(), source.policy().step(), candidate,
                    Map.copyOf(bound), Map.copyOf(evidence));
            var findings = criteria.evaluate(source.policy().registered(), context);
            boolean complete = !findings.isEmpty() && findings.stream().allMatch(finding -> finding.state() == CompletionCriterionRegistry.State.MET);
            List<String> reasons = complete ? List.of() : findings.stream().filter(finding -> finding.state() != CompletionCriterionRegistry.State.MET)
                    .flatMap(finding -> finding.reasonCodes().stream()).distinct().toList();
            if (!complete && reasons.isEmpty()) reasons = List.of("CANDIDATE_CRITERIA_NOT_MET");
            // Checker execution is not a cached authorization decision.
            for (ArtifactMetadata value : evidence.values()) readable(source, value);
            for (BoundArtifact value : bound.values()) readable(source, value.metadata());
            return result(source, candidateHash, complete ? Verdict.COMPLETE : Verdict.REJECTED, outputs, findings, reasons);
        } catch (InvalidCandidate invalid) {
            return result(source, candidateHash, Verdict.REJECTED, Map.of(), List.of(), List.of(invalid.code));
        }
    }

    private Assessment result(Source source, String candidateHash, Verdict verdict, Map<String, ArtifactRef> outputs,
                              List<CompletionCriterionRegistry.Result> findings, List<String> reasons) {
        RunDefinition definition = source.token().definition();
        String identity = "candidate-" + CampaignRunStore.sha256(encode(List.of(definition.caller(), definition.sessionId(), definition.runId(),
                definition.planId(), definition.revision(), source.policy().step().stepId(), source.child().spec().childId())));
        return new Assessment(identity, source.child().spec().childId(), source.responseHash(), candidateHash,
                source.policy().registryId(), verdict, outputs, findings, reasons);
    }

    private Map<String, ArtifactMetadata> visible(Source source) {
        Map<String, ArtifactMetadata> visible = new TreeMap<>();
        for (ArtifactMetadata metadata : source.child().spec().modelInvocation().inputs().values()) {
            ArtifactMetadata duplicate = visible.putIfAbsent(metadata.ref().artifactId(), metadata);
            require(duplicate == null || duplicate.equals(metadata), "CANDIDATE_VISIBLE_INPUT_CHANGED");
        }
        return visible;
    }

    private ArtifactMetadata readable(Source source, ArtifactMetadata expected) {
        ArtifactMetadata actual = runs.readArtifact(source.token().definition().caller(), expected.ref().artifactId(), authorizer).metadata();
        if (!expected.equals(actual)) throw new SecurityException("CANDIDATE_ARTIFACT_CHANGED");
        return actual;
    }

    private void validBoundary(Source source, ArtifactMetadata actual) {
        valid(artifactBoundary.permits(source.token(), source.policy().step(), actual, authorizer),
                "CANDIDATE_ARTIFACT_BOUNDARY_MISMATCH");
    }

    private record Policy(PlanSpec.Step step, CapabilityCatalog.Policy registered, String registryId) {}
    private record Source(RunToken token, Policy policy, ChildRecord child, ModelInvocationRegistry.Response response, String responseHash) {}

    private Policy policy(RunDefinition definition, String stepId) {
        FrozenCampaignRun frozen = FrozenCampaignRun.read(definition);
        List<PlanSpec.Step> matches = frozen.plan().steps().stream().filter(step -> stepId.equals(step.stepId())).toList();
        require(matches.size() == 1, "CANDIDATE_STEP_MISSING");
        PlanSpec.Step step = matches.get(0);
        require(step.executionMode() == PlanSpec.ExecutionMode.REACT && step.executor() == null && step.explorationPolicy() != null,
                "CANDIDATE_REQUIRES_REACT_STEP");
        var requested = step.explorationPolicy();
        var registered = catalog.policy(requested.policyRef(), requested.policyVersion()).orElseThrow(() -> failure("CANDIDATE_POLICY_UNREGISTERED"));
        require(requested.policyRef().equals(registered.policyRef()) && requested.policyVersion().equals(registered.policyVersion())
                        && !requested.allowedExecutors().isEmpty()
                        && new HashSet<>(requested.allowedExecutors()).size() == requested.allowedExecutors().size()
                        && registered.allowedExecutors().containsAll(requested.allowedExecutors())
                        && !requested.completionCriteria().isEmpty() && requested.completionCriteria().equals(registered.completionCriteria())
                        && registered.terminationPolicyRef() != null && !registered.terminationPolicyRef().isBlank()
                        && registered.terminationPolicyRef().equals(requested.terminationPolicyRef())
                        && registered.signature() != null && step.outputContractRef().equals(registered.signature().outputContractRef()),
                "CANDIDATE_POLICY_CHANGED");
        for (var ref : requested.allowedExecutors()) {
            var capability = catalog.capability(ref).orElseThrow(() -> failure("CANDIDATE_CAPABILITY_UNREGISTERED"));
            require(ref.equals(capability.executor()) && !capability.startsExploration(), "CANDIDATE_CAPABILITY_CHANGED");
        }
        boundary(frozen.inputs(), step, registered, "ScopeRef", requested.scopeRef());
        boundary(frozen.inputs(), step, registered, "PeriodsRef", requested.periodsRef());
        String registryId = criteria.configurationId(registered);
        require(registryId != null && !registryId.isBlank() && registryId.length() <= 512, "CANDIDATE_REGISTRY_INVALID");
        if (artifactBoundary != ExplorationArtifactBoundary.exact()) registryId = "candidate-boundary/v1:"
                + CampaignRunStore.sha256(encode(List.of(registryId, artifactBoundary.configurationId())));
        return new Policy(step, registered, registryId);
    }

    private void boundary(FrozenInputSet inputs, PlanSpec.Step step, CapabilityCatalog.Policy registered, String type, String reference) {
        require(reference != null && !reference.isBlank(), "CANDIDATE_POLICY_BOUNDARY_CHANGED");
        long matches = registered.signature().inputs().entrySet().stream()
                .filter(entry -> type.equals(entry.getValue().type().name()))
                .filter(entry -> {
                    PlanBinding binding = step.inputBindings().get(entry.getKey());
                    return binding != null && binding.source() == PlanBinding.Source.INPUT
                            && binding.stepId() == null && binding.output() == null && binding.artifactId() == null
                            && inputs.inputContracts().containsKey(binding.input())
                            && entry.getValue().type().equals(inputs.inputContracts().get(binding.input()).type())
                            && reference.equals(inputs.inputValues().get(binding.input()));
                }).count();
        require(matches == 1, "CANDIDATE_POLICY_BOUNDARY_CHANGED");
    }

    private Source source(RunToken token, String stepId, String modelChildId) {
        Policy policy = policy(token.definition(), stepId);
        callbacks.requireNoActive(token.definition().runId());
        require(jdbc.query("SELECT child_id FROM campaign_child_ledger WHERE run_id=? AND callback_active=TRUE LIMIT 1 FOR UPDATE",
                (rs, row) -> rs.getString(1), token.definition().runId()).isEmpty(), "CANDIDATE_CALLBACK_STILL_ACTIVE");
        require(jdbc.query("SELECT c.child_id FROM campaign_child_ledger c JOIN campaign_action_ledger a "
                        + "ON a.run_id=c.run_id AND a.revision=c.revision AND a.action_id=c.action_id "
                        + "WHERE c.run_id=? AND c.revision=? AND a.step_id=? AND (c.child_state<>'READY' OR c.unresolved_reason IS NOT NULL) LIMIT 1 FOR UPDATE",
                (rs, row) -> rs.getString(1), token.definition().runId(), token.definition().revision(), stepId).isEmpty(), "CANDIDATE_CHILD_NOT_READY");
        var headers = jdbc.query("SELECT session_status,reason,current_turn FROM campaign_exploration_session WHERE run_id=? AND revision=? AND step_id=? FOR UPDATE",
                (rs, row) -> {
                    require("CANDIDATE".equals(rs.getString(1)) || ("BLOCKED".equals(rs.getString(1)) && ASSESSED_BLOCKS.contains(rs.getString(2))),
                            "CANDIDATE_SESSION_NOT_FINAL");
                    return rs.getLong(3);
                }, token.definition().runId(), token.definition().revision(), stepId);
        require(headers.size() == 1, "CANDIDATE_SESSION_MISSING");
        long turnIndex = headers.get(0);
        var turns = jdbc.query("SELECT turn_index,model_child_id,invocation_hash,response_hash,decision,call_id "
                        + "FROM campaign_exploration_turn WHERE run_id=? AND revision=? AND step_id=? ORDER BY turn_index DESC LIMIT 1 FOR UPDATE",
                (rs, row) -> {
                    require(turnIndex == rs.getLong(1) && modelChildId.equals(rs.getString(2)) && "FINAL".equals(rs.getString(5))
                            && rs.getString(6) == null, "CANDIDATE_SOURCE_NOT_FINAL");
                    return List.of(rs.getString(3), rs.getString(4));
                }, token.definition().runId(), token.definition().revision(), stepId);
        require(turns.size() == 1, "CANDIDATE_TURN_MISSING");
        ChildRecord child = runs.child(token, modelChildId).orElseThrow(() -> failure("CANDIDATE_MODEL_MISSING"));
        var identity = ModelInvocationRegistry.identity(token.definition(), stepId, turnIndex);
        var invocation = child.spec().modelInvocation();
        require(child.spec().mode() == ChildMode.MODEL && invocation != null && child.state() == ChildState.READY && !child.callbackActive()
                        && identity.childId().equals(child.spec().childId()) && identity.actionId().equals(child.spec().actionId())
                        && identity.requestId().equals(child.spec().requestId()) && identity.invocationId().equals(invocation.invocationId())
                        && turnIndex == invocation.turnIndex() && turns.get(0).get(0).equals(invocation.hash()), "CANDIDATE_MODEL_CHANGED");
        var response = runs.readModelResponse(token, modelChildId, models.approve(invocation), authorizer);
        String responseHash = CampaignRunStore.sha256(ModelInvocationRegistry.encodeResponse(response));
        require(response.toolCalls().isEmpty() && responseHash.equals(turns.get(0).get(1)), "CANDIDATE_MODEL_RESPONSE_CHANGED");
        return new Source(token, policy, child, response, responseHash);
    }

    private void lockRun(RunToken token) {
        var definition = token.definition(); var owner = definition.caller();
        var rows = jdbc.query("SELECT tenant_id,subject_name,auth_version,session_id,plan_id,definition_hash,run_status,row_version,advance_token "
                        + "FROM campaign_run_ledger WHERE run_id=? AND revision=? FOR UPDATE",
                (rs, row) -> owner.tenantId().equals(rs.getString(1)) && owner.subject().equals(rs.getString(2))
                        && owner.authVersion() == rs.getLong(3) && definition.sessionId().equals(rs.getString(4))
                        && definition.planId().equals(rs.getString(5)) && definition.definitionHash().equals(rs.getString(6))
                        && "ACTIVE".equals(rs.getString(7)) && token.version() == rs.getLong(8) && token.advanceToken().equals(rs.getString(9)),
                definition.runId(), definition.revision());
        if (rows.size() != 1 || !rows.get(0)) throw new SecurityException("CANDIDATE_RUN_FENCED");
    }

    private void lockStep(RunToken token, String stepId, StepPermit permit) {
        var rows = jdbc.query("SELECT step_status,callback_active,attempt_id,attempt_version,dispatch_run_version,dispatch_run_token "
                        + "FROM campaign_step_ledger WHERE run_id=? AND revision=? AND step_id=? FOR UPDATE",
                (rs, row) -> permit == null || ("RUNNING".equals(rs.getString(1)) && rs.getBoolean(2)
                        && permit.attemptId().equals(rs.getString(3)) && permit.attemptVersion() == rs.getLong(4)
                        && token.version() == rs.getLong(5) && token.advanceToken().equals(rs.getString(6))),
                token.definition().runId(), token.definition().revision(), stepId);
        require(rows.size() == 1 && rows.get(0), "CANDIDATE_STEP_FENCED");
        var actual = steps.step(token, stepId).orElseThrow(() -> failure("CANDIDATE_STEP_MISSING"));
        Policy policy = policy(token.definition(), stepId);
        var ports = policy.registered().signature().outputs();
        var required = ports.entrySet().stream().filter(entry -> entry.getValue().required()).map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
        require(actual.spec().allowedOutputs().equals(ports.keySet()) && actual.spec().requiredOutputs().equals(required)
                        && actual.spec().dependsOn().equals(policy.step().dependsOn()), "CANDIDATE_STEP_CONTRACT_CHANGED");
        try {
            require(JSON.readTree(actual.spec().definitionJson()).equals(JSON.valueToTree(policy.step())),
                    "CANDIDATE_STEP_CHANGED");
        } catch (JsonProcessingException invalid) { throw failure("CANDIDATE_STEP_CHANGED"); }
    }

    private Optional<Assessment> find(RunToken token, String stepId) {
        return jdbc.query("SELECT * FROM campaign_exploration_candidate WHERE run_id=? AND revision=? AND step_id=? FOR UPDATE",
                (rs, row) -> decode(rs), token.definition().runId(), token.definition().revision(), stepId).stream().findFirst();
    }

    private Assessment decode(ResultSet rs) throws SQLException {
        String body = rs.getString("assessment_json"); bounded(body);
        require(CampaignRunStore.sha256(body).equals(rs.getString("assessment_hash")), "CANDIDATE_RECEIPT_CORRUPTED");
        try {
            Assessment result = JSON.readValue(body, Assessment.class);
            require(body.equals(encode(result)) && result.assessmentId().equals(rs.getString("assessment_id"))
                            && result.modelChildId().equals(rs.getString("model_child_id")) && result.responseHash().equals(rs.getString("response_hash"))
                            && result.candidateHash().equals(rs.getString("candidate_hash")) && result.registryId().equals(rs.getString("registry_id"))
                            && result.verdict().name().equals(rs.getString("verdict")), "CANDIDATE_RECEIPT_CORRUPTED");
            return result;
        } catch (JsonProcessingException | IllegalArgumentException invalid) { throw failure("CANDIDATE_RECEIPT_CORRUPTED"); }
    }

    private static String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw failure("CANDIDATE_JSON_INVALID"); }
    }
    private static void bounded(String value) {
        require(value != null && value.getBytes(StandardCharsets.UTF_8).length <= Limits.defaults().definitionBytes(), "CANDIDATE_RECEIPT_TOO_LARGE");
    }
    private static final class InvalidCandidate extends RuntimeException {
        private final String code;
        private InvalidCandidate(String code) { this.code = code; }
    }
    private static void valid(boolean condition, String code) { if (!condition) throw new InvalidCandidate(code); }
    private static void require(boolean condition, String code) { if (!condition) throw failure(code); }
    private static IllegalStateException failure(String code) { return new IllegalStateException(code); }
    private <T> T tx(Supplier<T> operation) { return transactions.execute(status -> operation.get()); }
}
