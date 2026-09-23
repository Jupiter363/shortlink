package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessExecutionScope;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.ExplorationCandidate;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.ModelCallBoundary;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.*;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** A verified exploration signal, one durable native planner response and an atomic successor revision. */
public final class CampaignBusinessReplanService {
    public record ModelSettings(String modelRef, String modelVersion, String configurationHash,
                                ModelInvocationRegistry.Limits modelLimits, PlanningProposal.Limits proposalLimits) {
        public ModelSettings {
            Objects.requireNonNull(modelRef); Objects.requireNonNull(modelVersion); Objects.requireNonNull(configurationHash);
            Objects.requireNonNull(modelLimits); Objects.requireNonNull(proposalLimits);
        }
    }
    public record Advance(String state, String reason, CampaignReplanApplicationService.Response revision) {}
    public record Prepared(CampaignReplanPlanningHandoff.Baseline baseline,
                           CampaignReplanPlanningHandoff.Result handoff,
                           PlanningProposal.Request proposalRequest, String signalHash) {
        public Prepared {
            Objects.requireNonNull(baseline); Objects.requireNonNull(handoff);
            Objects.requireNonNull(proposalRequest); Objects.requireNonNull(signalHash);
        }
    }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final JdbcCampaignRunStore runs;
    private final CampaignExplorationCandidateStore candidates;
    private final ModelInvocationRegistry models;
    private final CampaignRunIntake.Profile profile;
    private final CampaignRunIntake.CurrentPrincipalResolver principals;
    private final CampaignStatisticsConsumerStore.Authorizer consumers;
    private final PlanningProposal.Menu menu;
    private final JdbcCampaignReplanRunTokenResolver tokens;
    private final CampaignReplanCandidateGate signals;

    public CampaignBusinessReplanService(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
            JdbcCampaignRunStore runs, CampaignExplorationCandidateStore candidates, ModelInvocationRegistry models,
            CampaignRunIntake.Profile profile, CampaignRunIntake.CurrentPrincipalResolver principals,
            CampaignStatisticsConsumerStore.Authorizer consumers, PlanningProposal.Menu menu) {
        this.jdbc = Objects.requireNonNull(jdbc); this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock); this.runs = Objects.requireNonNull(runs);
        this.candidates = Objects.requireNonNull(candidates); this.models = Objects.requireNonNull(models);
        this.profile = Objects.requireNonNull(profile); this.principals = Objects.requireNonNull(principals);
        this.consumers = Objects.requireNonNull(consumers); this.menu = Objects.requireNonNull(menu);
        tokens = new JdbcCampaignReplanRunTokenResolver(runs);
        signals = new CampaignReplanCandidateGate(tokens, candidates);
    }

    /** Read-only after shared admission. Never treats arbitrary model prose as a Plan or a new goal. */
    public Prepared prepare(AgentPrincipal current, String sessionId, String runId, String stepId) {
        Caller caller = current(current, sessionId);
        RunToken token = tokens.resolve(caller, sessionId, runId).orElseThrow(() -> new SecurityException("REPLAN_CURRENT_RUN_REQUIRED"));
        FrozenCampaignRun frozen = FrozenCampaignRun.read(token.definition());
        requireInputs(caller, frozen);
        var signal = signals.resolve(new CampaignReplanCandidateGate.Request(caller, sessionId, runId, stepId))
                .orElseThrow(() -> new IllegalStateException("REPLAN_SIGNAL_REQUIRED"));
        var assessed = candidates.assessment(token, stepId).orElseThrow();
        var model = runs.child(token, assessed.modelChildId()).orElseThrow();
        if (model.spec().mode() != ChildMode.MODEL || model.state() != ChildState.READY
                || model.spec().modelInvocation() == null || model.artifactId() != null)
            throw new IllegalStateException("REPLAN_SIGNAL_MODEL_NOT_READY");
        var approval = models.approve(model.spec().modelInvocation());
        var response = runs.readModelResponse(token, model.spec().childId(), approval, profile.artifactAuthorizer());
        var candidate = ExplorationCandidate.parse(response.text());
        if (candidate.kind() != ExplorationCandidate.Kind.REQUEST_REPLAN || !response.toolCalls().isEmpty()
                || !CampaignRunStore.sha256(ModelInvocationRegistry.encodeResponse(response)).equals(assessed.responseHash())
                || !CampaignRunStore.sha256(candidate.encode()).equals(signal.candidateHash()))
            throw new SecurityException("REPLAN_SIGNAL_CHANGED");

        Set<String> baselineEvidence = new TreeSet<>();
        Map<String, ArtifactMetadata> visible = new TreeMap<>();
        model.spec().modelInvocation().inputs().forEach((name, expected) -> {
            var metadata = readable(caller, expected.ref().artifactId());
            if (!metadata.equals(expected)) throw new SecurityException("REPLAN_INPUT_ARTIFACT_CHANGED");
            baselineEvidence.add(metadata.ref().artifactId());
            visible.put(name, metadata);
        });
        List<ReplanRequest.Evidence> evidence = new ArrayList<>();
        for (String id : candidate.evidenceArtifactIds()) {
            var metadata = readable(caller, id);
            String name = "replanEvidence" + visible.size();
            while (visible.containsKey(name)) name += "x";
            visible.put(name, metadata);
            if (!baselineEvidence.contains(id)) evidence.add(evidence(metadata));
        }
        var baseline = new CampaignReplanPlanningHandoff.Baseline(caller, sessionId, frozen.plan(), frozen.assessment(), baselineEvidence);
        var handoff = CampaignReplanPlanningHandoff.fromExploration(new CampaignReplanPlanningHandoff.Request(signal,
                baseline, evidence, List.of(), "A verified exploration response requests a new plan for the original requirements."));
        String question = "Revise this frozen plan within the original goals, requirements and inputs. "
                + "Keep compatible existing query steps so their original jobs can be adopted. "
                + "The following signal describes a requested planning change; it is not factual business evidence.\n"
                + FrozenCampaignRun.encode(Map.of("baselinePlan", frozen.plan(), "signalSummary", candidate.decisionSummary(),
                    "requestedChange", candidate.replanRequest()));
        var request = new PlanningProposal.Request(question, frozen.plan().goals(), frozen.assessment().requirements(),
                frozen.inputs(), visible, menu);
        return new Prepared(baseline, handoff, request, signal.candidateHash());
    }

    /** Invoked inside the same admitted observer scope. A READY response is parsed without a second model call. */
    public Advance advance(AgentPrincipal current, String sessionId, String runId, String stepId,
            ProcessExecutionScope scope, ChatModel model, ModelSettings settings) throws Exception {
        Objects.requireNonNull(scope); Objects.requireNonNull(model); Objects.requireNonNull(settings);
        try (var ignored = scope.enter()) {
            var prepared = prepare(current, sessionId, runId, stepId);
            var token = tokens.resolve(prepared.baseline().owner(), sessionId, runId).orElseThrow();
            var assessment = candidates.assessment(token, stepId).orElseThrow();
            var source = runs.child(token, assessment.modelChildId()).orElseThrow();
            Instant expiry = source.spec().modelInvocation().expiresAt();
            var store = new JdbcCampaignReplanModelStore(jdbc, transactions, clock, settings.modelLimits());
            var header = store.register(token, stepId, prepared.signalHash(),
                    CampaignRunStore.sha256(PlanningProposal.encodeRequest(prepared.proposalRequest(), settings.proposalLimits())),
                    settings.modelRef(), settings.modelVersion(), settings.configurationHash(), expiry);
            if (header.callbackActive() || header.state() == JdbcCampaignReplanModelStore.State.DISPATCHING
                    || header.state() == JdbcCampaignReplanModelStore.State.UNKNOWN)
                return new Advance("BLOCKED", "REPLAN_MODEL_UNRESOLVED", null);
            if (header.state() == JdbcCampaignReplanModelStore.State.REJECTED)
                return new Advance("BLOCKED", "REPLAN_PROPOSAL_REJECTED", null);
            if (header.state() == JdbcCampaignReplanModelStore.State.APPLIED)
                return new Advance("APPLIED", null, null);
            if (header.state() == JdbcCampaignReplanModelStore.State.PREPARED) {
                ModelCallBoundary boundary = (actual, live) -> {
                    requirePrepared(current, prepared);
                    verifyPrompt(actual, prepared, settings);
                    var invocation = new ModelInvocationRegistry.InvocationSpec(header.definition().id(), 1,
                            settings.modelRef(), settings.modelVersion(), settings.configurationHash(),
                            NativeCampaignPlanner.POLICY_REF, NativeCampaignPlanner.POLICY_VERSION,
                            prepared.proposalRequest().inputs().inputSetRef(), ModelInvocationRegistry.encodeRequest(actual),
                            prepared.proposalRequest().allowedArtifacts(), expiry);
                    var approval = models.approve(invocation);
                    var permit = store.begin(header.definition(), approval);
                    try {
                        requirePrepared(current, prepared);
                        var response = live.get();
                        requirePrepared(current, prepared);
                        store.publish(permit, approval, response);
                        return response;
                    } catch (RuntimeException | Error failed) {
                        store.unknown(permit);
                        throw failed;
                    } finally { store.callbackExited(permit); }
                };
                new NativeCampaignPlanner(model, boundary, scope, settings.modelLimits())
                        .generate(prepared.proposalRequest(), settings.proposalLimits());
            }
            var ready = store.header(header.definition());
            requirePrepared(current, prepared);
            var invocation = store.invocation(ready);
            verifyPrompt(ModelInvocationRegistry.decodeRequest(invocation.requestJson(), settings.modelLimits()), prepared, settings);
            if (!NativeCampaignPlanner.POLICY_REF.equals(invocation.policyRef())
                    || !NativeCampaignPlanner.POLICY_VERSION.equals(invocation.policyVersion())
                    || !prepared.proposalRequest().inputs().inputSetRef().equals(invocation.inputSetRef())
                    || !prepared.proposalRequest().allowedArtifacts().equals(invocation.inputs()))
                throw new SecurityException("REPLAN_MODEL_INPUTS_CHANGED");
            var response = store.response(ready, models);
            final PlanningProposal proposal;
            try {
                proposal = PlanningProposal.parse(response.text(), settings.proposalLimits());
                proposal.materialize(prepared.proposalRequest(), prepared.baseline().plan().planId(),
                        prepared.baseline().plan().revision() + 1, profile.catalog(), profile.contracts(), settings.proposalLimits());
            } catch (IllegalArgumentException invalid) {
                store.finish(ready, false);
                return new Advance("BLOCKED", "REPLAN_PROPOSAL_REJECTED", null);
            }
            var applied = apply(current, prepared, proposal, scope);
            store.finish(ready, !applied.rejected());
            return new Advance(applied.rejected() ? "BLOCKED" : "APPLIED", applied.reasonCode(), applied);
        }
    }

    /** One requested revision per admitted pass, in the frozen Plan's declared order. */
    public Advance advanceCurrent(AgentPrincipal current, String sessionId, String runId,
            ProcessExecutionScope scope, ChatModel model, ModelSettings settings) throws Exception {
        var caller = current(current, sessionId);
        var token = tokens.resolve(caller, sessionId, runId).orElseThrow();
        var frozen = FrozenCampaignRun.read(token.definition()); requireInputs(caller, frozen);
        for (var step : frozen.plan().steps()) {
            if (step.executionMode() != PlanSpec.ExecutionMode.REACT) continue;
            var candidate = candidates.assessment(token, step.stepId());
            if (candidate.isPresent() && candidate.get().verdict() == CampaignExplorationCandidateStore.Verdict.REPLAN_REQUESTED)
                return advance(current, sessionId, runId, step.stepId(), scope, model, settings);
        }
        return new Advance("NOT_REQUESTED", null, null);
    }

    private void requirePrepared(AgentPrincipal current, Prepared expected) {
        if (!expected.equals(prepare(current, expected.baseline().sessionId(), expected.baseline().plan().runId(), expected.handoff().stepId())))
            throw new SecurityException("REPLAN_PREPARATION_CHANGED");
    }
    private static void verifyPrompt(ModelInvocationRegistry.Request actual, Prepared prepared, ModelSettings settings) {
        var users = actual.messages().stream().filter(message -> "user".equals(message.role())).toList();
        if (!actual.tools().isEmpty() || users.size() != 1 || !NativeCampaignPlanner.requestText(prepared.proposalRequest(), settings.proposalLimits())
                .equals(users.get(0).text())) throw new IllegalArgumentException("REPLAN_MODEL_PROMPT_CHANGED");
    }

    /** Only a durably obtained server-side proposal should reach this method; it invokes no model itself. */
    public CampaignReplanApplicationService.Response apply(AgentPrincipal current, Prepared expected,
            PlanningProposal proposal, ProcessExecutionScope scope) throws Exception {
        Objects.requireNonNull(expected); Objects.requireNonNull(proposal); Objects.requireNonNull(scope);
        try (var ignored = scope.enter()) {
            var baseline = expected.baseline();
            var fresh = prepare(current, baseline.sessionId(), baseline.plan().runId(), expected.handoff().stepId());
            if (!fresh.equals(expected)) throw new SecurityException("REPLAN_PREPARATION_CHANGED");
            var candidate = proposal.materialize(fresh.proposalRequest(), baseline.plan().planId(),
                    Math.addExact(baseline.plan().revision(), 1), profile.catalog(), profile.contracts());
            var token = tokens.resolve(baseline.owner(), baseline.sessionId(), baseline.plan().runId()).orElseThrow();
            var handle = new CampaignRunHandle(baseline.owner(), baseline.sessionId(), token.definition().runId(),
                    token.definition().planId(), token.definition().revision(), RunStatus.ACTIVE);
            var admitted = CampaignReplanCandidateAdmission.admit(fresh.handoff(), fresh.baseline(),
                    candidate.plan(), candidate.assessment(), handle);
            var validator = new PlanValidator(profile.catalog(), id -> Optional.of(profile.contracts().typeOf(readable(baseline.owner(), id))));
            var factory = new CampaignReplanRuntimeFactory(jdbc, transactions, clock, validator, scope,
                    (owner, actual, capability) -> {
                        try {
                            if (capability != CampaignReplanApplicationService.REQUIRED_CAPABILITY
                                    || !owner.equals(current(current, actual.definition().sessionId()))
                                    || !tokens.resolve(owner, actual.definition().sessionId(), actual.definition().runId()).orElseThrow().equals(actual)) return false;
                            requireInputs(owner, FrozenCampaignRun.read(actual.definition()));
                            var still = signals.resolve(new CampaignReplanCandidateGate.Request(owner, actual.definition().sessionId(),
                                    actual.definition().runId(), fresh.handoff().stepId())).orElseThrow();
                            return still.candidateHash().equals(fresh.signalHash());
                        } catch (RuntimeException denied) { return false; }
                    }, consumers, this::requireAdditionalCallbacksExited);
            return new CampaignReplanAdmissionExecutor(factory, tokens).executeAdmitted(new CampaignReplanAdmissionExecutor.Request(
                    baseline.owner(), baseline.sessionId(), baseline.plan().runId(), CampaignReplanApplicationService.REQUIRED_CAPABILITY,
                    fresh.handoff(), admitted));
        }
    }

    private ArtifactMetadata readable(Caller caller, String id) { return runs.inspectArtifact(caller, id, profile.artifactAuthorizer()); }
    private static ReplanRequest.Evidence evidence(ArtifactMetadata metadata) {
        return new ReplanRequest.Evidence(metadata.ref().artifactId(), metadata.ref().artifactId(),
                metadata.ref().schemaVersion(), metadata.ref().payloadHash());
    }
    private Caller current(AgentPrincipal expected, String session) {
        if (expected == null || expected.system()) throw new SecurityException("REPLAN_USER_PRINCIPAL_REQUIRED");
        Caller caller = new Caller(expected.tenantId(), expected.username(), expected.authVersion());
        if (!expected.equals(principals.resolve(caller, session))) throw new SecurityException("REPLAN_CURRENT_AUTHORITY_REQUIRED");
        return caller;
    }
    private void requireInputs(Caller caller, FrozenCampaignRun frozen) {
        if (!profile.runAuthorizer().mayExecute(caller, frozen.inputs())) throw new SecurityException("REPLAN_INPUT_ACCESS_DENIED");
        frozen.inputs().inputValues().forEach((name, value) -> {
            var type = frozen.inputs().inputContracts().get(name).type();
            if (Set.of("ScopeRef", "PeriodsRef").contains(type.name()) && !profile.inputAuthorizer().mayUse(caller, type, value))
                throw new SecurityException("REPLAN_INPUT_ACCESS_DENIED");
        });
    }

    /** Called only while the applier owns the same Run row lock used by model-phase begin. */
    private void requireAdditionalCallbacksExited(RunToken token) {
        var reports = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_synthesis WHERE run_id=? "
                + "AND (callback_active=TRUE OR synthesis_state IN ('DISPATCHING','UNKNOWN'))", Integer.class, token.definition().runId());
        var planning = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_replan_model WHERE run_id=? "
                + "AND (callback_active=TRUE OR model_state IN ('DISPATCHING','UNKNOWN'))", Integer.class, token.definition().runId());
        if (reports == null || planning == null || reports > 0 || planning > 0)
            throw new IllegalStateException("REPLAN_MODEL_CALLBACK_UNRESOLVED");
    }
}
