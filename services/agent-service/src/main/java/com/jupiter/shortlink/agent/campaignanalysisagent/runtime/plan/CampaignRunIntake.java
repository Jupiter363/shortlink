package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanValidator;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningProposal;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.NativeCampaignPlanner;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.StepBindings;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.AdmittedCampaignAdvance;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessExecutionScope;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRecoveryStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignAdvanceOutcomeStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore.Header;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignPlanningStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.ModelCallBoundary;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignRecoveryCoordinator;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReleaser;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsSubmissionReconciler;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.util.List;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Internal opt-in intake for trusted typed plans and requirements. No HTTP/Spring registration,
 * natural-language requirement extraction or scheduler. One instance shares admission across profiles.
 * Registration persists a bounded proposal; only admitted work loads and validates full inputs.
 */
public final class CampaignRunIntake implements AutoCloseable {
    @FunctionalInterface public interface CurrentPrincipalResolver {
        /** Consult current authoritative identity AND session ownership; never echo stored metadata. */
        AgentPrincipal resolve(Caller caller, String sessionId);
    }

    @FunctionalInterface public interface RuntimeFactory {
        CampaignRecoveryCoordinator.Runtime create(RuntimeContext context) throws Exception;
    }

    /** Pure domain validation before accepting/freezing a definition; never constructs a runtime or calls tools. */
    @FunctionalInterface public interface DefinitionValidator {
        void validate(RunDefinition definition);
    }

    /** Optional trusted normalization of a parsed candidate; it cannot replace subsequent contract/domain validation. */
    @FunctionalInterface public interface ProposalNormalizer {
        PlanningProposal normalize(Caller caller, PlanningProposal.Request request, PlanningProposal candidate);
    }

    /** The factory must use these exact gates and scope in its Driver and descendant adapters. */
    public record RuntimeContext(RunToken token, AgentPrincipal principal, ProcessExecutionScope scope,
                                 PersistentPlanDriver.RunAuthorizer runAuthorizer,
                                 ArtifactAuthorizer artifactAuthorizer,
                                 StepBindings.CurrentInputAuthorizer inputAuthorizer) {}

    public record Profile(String ref, String version, CapabilityCatalog catalog, ArtifactContractRegistry contracts,
                          PersistentPlanDriver.RunAuthorizer runAuthorizer, ArtifactAuthorizer artifactAuthorizer,
                          StepBindings.CurrentInputAuthorizer inputAuthorizer, RuntimeFactory runtimeFactory,
                          DefinitionValidator definitionValidator, ProposalNormalizer proposalNormalizer) {
        public Profile(String ref, String version, CapabilityCatalog catalog, ArtifactContractRegistry contracts,
                PersistentPlanDriver.RunAuthorizer runAuthorizer, ArtifactAuthorizer artifactAuthorizer,
                StepBindings.CurrentInputAuthorizer inputAuthorizer, RuntimeFactory runtimeFactory) {
            this(ref, version, catalog, contracts, runAuthorizer, artifactAuthorizer, inputAuthorizer, runtimeFactory,
                    definition -> {});
        }
        public Profile(String ref, String version, CapabilityCatalog catalog, ArtifactContractRegistry contracts,
                PersistentPlanDriver.RunAuthorizer runAuthorizer, ArtifactAuthorizer artifactAuthorizer,
                StepBindings.CurrentInputAuthorizer inputAuthorizer, RuntimeFactory runtimeFactory,
                DefinitionValidator definitionValidator) {
            this(ref, version, catalog, contracts, runAuthorizer, artifactAuthorizer, inputAuthorizer, runtimeFactory,
                    definitionValidator, (caller, request, candidate) -> candidate);
        }
        public Profile {
            if (ref == null || ref.isBlank() || ref.length() > 128
                    || version == null || version.isBlank() || version.length() > 128)
                throw new IllegalArgumentException("CAMPAIGN_PROFILE_REQUIRED");
            Objects.requireNonNull(catalog); Objects.requireNonNull(contracts);
            Objects.requireNonNull(runAuthorizer); Objects.requireNonNull(artifactAuthorizer);
            Objects.requireNonNull(inputAuthorizer); Objects.requireNonNull(runtimeFactory);
            Objects.requireNonNull(definitionValidator);
            Objects.requireNonNull(proposalNormalizer);
        }
    }

    private record ProfileKey(String ref, String version) {}

    /** Approved planning instructions/menu and provider configuration for one business profile. */
    public record PlanningProfile(String profileRef, String profileVersion, PlanningProposal.Menu menu,
                                  ChatModel model, ModelInvocationRegistry models, String modelRef,
                                  String modelVersion, String configurationHash,
                                  ModelInvocationRegistry.Limits modelLimits, PlanningProposal.Limits proposalLimits) {
        public PlanningProfile {
            Objects.requireNonNull(profileRef); Objects.requireNonNull(profileVersion); Objects.requireNonNull(menu);
            Objects.requireNonNull(model); Objects.requireNonNull(models); Objects.requireNonNull(modelRef);
            Objects.requireNonNull(modelVersion); Objects.requireNonNull(configurationHash);
            Objects.requireNonNull(modelLimits); Objects.requireNonNull(proposalLimits);
        }
    }
    private final JdbcCampaignRunIntakeStore requests;
    private final CampaignRunStore runs;
    private final CampaignRecoveryStore recovery;
    private final StatisticsSubmissionReconciler submissions;
    private final StatisticsJobResultReceiver receiver;
    private final StatisticsJobResultReleaser releaser;
    private final Map<ProfileKey, Profile> profiles;
    private final CurrentPrincipalResolver principals;
    private final AdmittedCampaignAdvance admitted;
    private final JdbcCampaignPlanningStore planning;
    private final Map<ProfileKey, PlanningProfile> planners;
    private final JdbcCampaignAdvanceOutcomeStore outcomes;
    private volatile PreparationHandler preparation;
    @FunctionalInterface public interface AfterAdvance {
        void accept(Caller caller, String runId, ProcessExecutionScope scope);
    }
    @FunctionalInterface public interface RevisionResolver {
        RunDefinition resolve(RunDefinition initial, RunRecord current);
    }
    private volatile AfterAdvance afterAdvance = (caller,runId,scope) -> {};
    private volatile RevisionResolver revisions;

    public void observeAdvances(AfterAdvance observer) {
        afterAdvance=Objects.requireNonNull(observer);
    }

    /** Trusted composition only; proposals cannot replace the original intake identity. */
    public synchronized void installRevisionResolver(RevisionResolver resolver) {
        if (revisions != null) throw new IllegalStateException("CAMPAIGN_REVISION_RESOLVER_ALREADY_INSTALLED");
        revisions = Objects.requireNonNull(resolver);
    }

    /** Public requirement extraction shares this intake's real callback lifetime and capacity. */
    public interface PreparationHandler {
        boolean recognizes(WorkRef reference);
        void authorize(AgentPrincipal principal, WorkRef reference);
        AdmittedCampaignAdvance.Operation load(WorkRef reference, ProcessExecutionScope scope);
        default void lockForCommit(Caller caller, String sessionId, String runId) {}
    }

    public synchronized void installPreparation(PreparationHandler handler) {
        if (preparation != null) throw new IllegalStateException("CAMPAIGN_PREPARATION_ALREADY_INSTALLED");
        Objects.requireNonNull(handler);
        requests.installCommitGuard(handler::lockForCommit);
        preparation = handler;
    }

    public CampaignRunIntake(JdbcCampaignRunIntakeStore requests, CampaignRunStore runs,
            CampaignRecoveryStore recovery, StatisticsSubmissionReconciler submissions,
            StatisticsJobResultReceiver receiver, StatisticsJobResultReleaser releaser,
            List<Profile> profiles, CurrentPrincipalResolver principals,
            ProcessCapacityExecutor.Limits limits, Executor executor) {
        this(requests, runs, recovery, submissions, receiver, releaser, profiles, principals, limits, executor, null, List.of());
    }

    public CampaignRunIntake(JdbcCampaignRunIntakeStore requests, CampaignRunStore runs,
            CampaignRecoveryStore recovery, StatisticsSubmissionReconciler submissions,
            StatisticsJobResultReceiver receiver, StatisticsJobResultReleaser releaser,
            List<Profile> profiles, CurrentPrincipalResolver principals,
            ProcessCapacityExecutor.Limits limits, Executor executor, JdbcCampaignPlanningStore planning,
            List<PlanningProfile> planners) {
        this(requests, runs, recovery, submissions, receiver, releaser, profiles, principals,
                limits, executor, planning, planners, null);
    }

    public CampaignRunIntake(JdbcCampaignRunIntakeStore requests, CampaignRunStore runs,
            CampaignRecoveryStore recovery, StatisticsSubmissionReconciler submissions,
            StatisticsJobResultReceiver receiver, StatisticsJobResultReleaser releaser,
            List<Profile> profiles, CurrentPrincipalResolver principals,
            ProcessCapacityExecutor.Limits limits, Executor executor, JdbcCampaignPlanningStore planning,
            List<PlanningProfile> planners, JdbcCampaignAdvanceOutcomeStore outcomes) {
        this.requests = Objects.requireNonNull(requests);
        this.runs = Objects.requireNonNull(runs);
        if (!requests.usesRunStore(runs)) throw new IllegalArgumentException("CAMPAIGN_INTAKE_STORE_CHANGED");
        this.recovery = Objects.requireNonNull(recovery);
        this.submissions = Objects.requireNonNull(submissions);
        this.receiver = receiver;
        this.releaser = releaser;
        this.profiles = profiles.stream().collect(Collectors.toUnmodifiableMap(
                profile -> new ProfileKey(profile.ref(), profile.version()), profile -> profile));
        this.principals = Objects.requireNonNull(principals);
        this.planning = planning;
        this.outcomes = outcomes;
        if (planning != null && !planning.usesIntakeStore(requests))
            throw new IllegalArgumentException("CAMPAIGN_PLANNING_STORE_CHANGED");
        this.planners = planners.stream().collect(Collectors.toUnmodifiableMap(
                planner -> new ProfileKey(planner.profileRef(), planner.profileVersion()), planner -> planner));
        if (!this.profiles.keySet().containsAll(this.planners.keySet()) || (!this.planners.isEmpty() && planning == null))
            throw new IllegalArgumentException("CAMPAIGN_PLANNING_PROFILE_UNAVAILABLE");
        this.admitted = new AdmittedCampaignAdvance(limits, executor, this::loadAdmitted);
    }

    /** Proposal identifiers come from JdbcCampaignRunIntakeStore.identity, not model output. */
    public WorkRef register(AgentPrincipal current, String sessionId, String requestKey,
                            String profileRef, String profileVersion, RunDefinition proposal) {
        requireTopLevel();
        Caller caller = caller(current);
        requirePrincipal(caller, sessionId, current);
        profile(profileRef, profileVersion);
        Header receipt = requests.register(caller, sessionId, requestKey, profileRef, profileVersion, proposal);
        return new WorkRef(receipt.runId(), receipt.requestId());
    }

    /** Metadata only; possession of a WorkRef never authorizes its contents or execution. */
    public Header receipt(AgentPrincipal current, WorkRef reference) {
        Header receipt = requests.header(reference);
        requirePrincipal(receipt.caller(), receipt.sessionId(), current);
        return receipt;
    }

    /** Enqueue only after the registration transaction committed; a Future is not a Goal verdict. */
    public Future<Void> submit(AgentPrincipal current, WorkRef reference) {
        requireTopLevel();
        if (preparation != null && preparation.recognizes(reference)) preparation.authorize(current, reference);
        else if (planningReference(reference)) planningReceipt(current, reference);
        else receipt(current, reference);
        return admitted.submit(reference);
    }

    /** Trusted due-work scanner only; loadAdmitted still re-resolves current owner and session. */
    public Future<Void> submitBackground(WorkRef reference) {
        requireTopLevel();
        return admitted.submit(Objects.requireNonNull(reference));
    }

    /** Continue a just-registered plan inside the already admitted scope, without nested queues. */
    public void advancePrepared(WorkRef reference, ProcessExecutionScope scope) throws Exception {
        if (scope == null || scope.isClosed() || scope.activeCount() < 1)
            throw new IllegalStateException("CAMPAIGN_ADMITTED_SCOPE_REQUIRED");
        loadAdmitted(reference, scope).run();
    }

    /** Trusted typed requirements are immutable. This entry does not perform natural-language goal extraction. */
    public WorkRef registerPlanning(AgentPrincipal current, String sessionId, String requestKey,
            String profileRef, String profileVersion, PlanningProposal.Request request, Instant expiresAt) {
        requireTopLevel();
        Caller caller = caller(current);
        requirePrincipal(caller, sessionId, current);
        PlanningProfile planner = planner(profileRef, profileVersion);
        var identity = JdbcCampaignRunIntakeStore.identity(caller, sessionId, requestKey);
        if (!identity.runId().equals(request.inputs().runId())
                || !planner.menu().configurationId().equals(request.menu().configurationId()))
            throw new IllegalArgumentException("CAMPAIGN_PLANNING_INPUT_CHANGED");
        var header = planning.register(caller, sessionId, requestKey, profileRef, profileVersion,
                planner.modelRef(), planner.modelVersion(), planner.configurationHash(),
                PlanningProposal.encodeRequest(request, planner.proposalLimits()), expiresAt);
        return new WorkRef(header.runId(), header.requestId());
    }

    public JdbcCampaignPlanningStore.Header planningReceipt(AgentPrincipal current, WorkRef reference) {
        if (planning == null) throw new IllegalArgumentException("CAMPAIGN_PLANNING_UNAVAILABLE");
        var header = planning.header(reference);
        requirePrincipal(header.caller(), header.sessionId(), current);
        return header;
    }

    public ProcessCapacityExecutor.Snapshot snapshot() { return admitted.snapshot(); }
    @Override public void close() { admitted.close(); }

    private AdmittedCampaignAdvance.Operation loadAdmitted(WorkRef reference, ProcessExecutionScope scope) {
        if (preparation != null && preparation.recognizes(reference)) return preparation.load(reference, scope);
        if (planningReference(reference)) return loadPlanning(reference, scope);
        if (outcomes == null) return loadTyped(reference, scope);
        // Observation starts before principal/plan validation, which can fail before a Run exists.
        // A RUNNING observation conveys no process liveness or execution authority.
        var attempt = outcomes.begin(requests.header(reference));
        AdmittedCampaignAdvance.Operation operation;
        try {
            operation = loadTyped(reference, scope);
        } catch (RuntimeException | Error failure) {
            recordFailure(attempt, failure);
            throw failure;
        }
        return () -> {
            try {
                operation.run();
                outcomes.succeeded(attempt);
            } catch (Exception | Error failure) {
                recordFailure(attempt, failure);
                throw failure;
            }
        };
    }

    private void recordFailure(JdbcCampaignAdvanceOutcomeStore.Attempt attempt, Throwable failure) {
        String reason = failure instanceof SecurityException ? "ACCESS_DENIED"
                : failure instanceof IllegalArgumentException ? "INVALID_PLAN"
                : failure.getMessage() != null && failure.getMessage().startsWith("CAMPAIGN_ADVANCE_STOPPED:")
                    ? "ADVANCE_BLOCKED" : "RUNTIME_UNAVAILABLE";
        try {
            outcomes.failed(attempt, reason);
        } catch (RuntimeException | Error persistenceFailure) {
            if (failure != persistenceFailure) failure.addSuppressed(persistenceFailure);
        }
    }

    private AdmittedCampaignAdvance.Operation loadTyped(WorkRef reference, ProcessExecutionScope scope) {
        Header header = requests.header(reference);
        AgentPrincipal principal = currentPrincipal(header);
        Profile profile = profile(header.profileRef(), header.profileVersion());
        RunDefinition initial = requests.definition(header);
        FrozenCampaignRun initialFrozen = FrozenCampaignRun.read(initial);
        PersistentPlanDriver.RunAuthorizer runGate = (caller, inputs) -> header.caller().equals(caller)
                && principalCurrent(header) && profile.runAuthorizer().mayExecute(caller, inputs)
                && inputsAuthorized(profile, caller, inputs);
        ArtifactAuthorizer artifactGate = (caller, artifact) -> header.caller().equals(caller)
                && principalCurrent(header) && profile.artifactAuthorizer().mayRead(caller, artifact);
        StepBindings.CurrentInputAuthorizer inputGate = (caller, type, value) -> header.caller().equals(caller)
                && principalCurrent(header) && profile.inputAuthorizer().mayUse(caller, type, value);
        if (!runGate.mayExecute(header.caller(), initialFrozen.inputs())) throw new SecurityException("CAMPAIGN_INPUT_ACCESS_DENIED");
        var validator = new PlanValidator(profile.catalog(), id -> Optional.of(profile.contracts().typeOf(
                runs.inspectArtifact(header.caller(), id, artifactGate))));
        if (header.state() == JdbcCampaignRunIntakeStore.State.PENDING) {
            validator.validate(initialFrozen.plan(), initialFrozen.inputs(), initialFrozen.assessment());
            profile.definitionValidator().validate(initial);
        }
        requests.freeze(header, initial);
        var run = runs.loadRun(header.caller(), header.runId()).orElseThrow(
                () -> new IllegalStateException("CAMPAIGN_FROZEN_RUN_MISSING"));
        if (run.status() != RunStatus.ACTIVE) throw new IllegalStateException("CAMPAIGN_RUN_" + run.status().name());
        RunDefinition definition;
        if (sameDefinition(header, run.definition())) definition = initial;
        else {
            var resolver = revisions;
            if (resolver == null) throw new IllegalStateException("CAMPAIGN_REQUEST_REVISION_CHANGED");
            definition = Objects.requireNonNull(resolver.resolve(initial, run));
            if (!definition.equals(run.definition()) || !header.caller().equals(definition.caller())
                    || !header.sessionId().equals(definition.sessionId()) || !header.runId().equals(definition.runId())
                    || !header.planId().equals(definition.planId())) throw new SecurityException("CAMPAIGN_REVISION_BINDING_CHANGED");
        }
        FrozenCampaignRun frozen = FrozenCampaignRun.read(definition);
        if (!initialFrozen.inputs().equals(frozen.inputs())) throw new SecurityException("CAMPAIGN_REVISION_INPUTS_CHANGED");
        if (header.state() != JdbcCampaignRunIntakeStore.State.PENDING || !initial.equals(definition))
            validator.validate(frozen.plan(), frozen.inputs(), frozen.assessment());
        if (!runGate.mayExecute(header.caller(), frozen.inputs())) throw new SecurityException("CAMPAIGN_INPUT_ACCESS_DENIED");

        var coordinator = new CampaignRecoveryCoordinator(recovery, runs, submissions, (token, current) -> {
            if (!definition.equals(token.definition()) || !principal.equals(current)
                    || !runGate.mayExecute(header.caller(), frozen.inputs()))
                throw new SecurityException("CAMPAIGN_RUNTIME_ACCESS_DENIED");
            RuntimeContext context = new RuntimeContext(token, current, scope, runGate, artifactGate, inputGate);
            var runtime = Objects.requireNonNull(profile.runtimeFactory().create(context));
            if (!runtime.driver().matchesIntake(context) || !runtime.graph().belongsTo(runtime.driver(), scope))
                throw new IllegalArgumentException("CAMPAIGN_RUNTIME_BINDING_CHANGED");
            return runtime;
        }, (actual, current) -> definition.equals(actual) && principal.equals(current)
                && runGate.mayExecute(header.caller(), frozen.inputs()), receiver, releaser);
        // Coordinator owns acquisition and proven takeover for BOTH the first pass and recovery.
        // Never acquire here first, and never recover by ordinary re-submission of remote jobs.
        return () -> {
            var result = coordinator.resume(run.token(), principal);
            if (result.outcome() != CampaignRecoveryCoordinator.Outcome.SCANNED)
                throw new IllegalStateException("CAMPAIGN_ADVANCE_STOPPED:" + result.reason());
            afterAdvance.accept(header.caller(),header.runId(),scope);
        };
    }

    private AdmittedCampaignAdvance.Operation loadPlanning(WorkRef reference, ProcessExecutionScope scope) {
        if (planning == null) throw new IllegalArgumentException("CAMPAIGN_PLANNING_UNAVAILABLE");
        var header = planning.header(reference);
        requirePlanningPrincipal(header);
        // Once accepted, the frozen business plan owns continuation. Its original planning
        // response may expire or its provider be retired while a business job is still waiting.
        if (header.state() == JdbcCampaignPlanningStore.State.ACCEPTED) {
            var typedRef = new WorkRef(header.runId(), header.intakeRequestId());
            var typed = requests.header(typedRef);
            if (header.callbackActive() || !header.caller().equals(typed.caller())
                    || !header.sessionId().equals(typed.sessionId()) || !header.requestKey().equals(typed.requestKey())
                    || !header.profileRef().equals(typed.profileRef()) || !header.profileVersion().equals(typed.profileVersion())
                    || !header.runId().equals(typed.runId()) || !header.planId().equals(typed.planId())
                    || typed.revision() != 1 || !Objects.equals(header.definitionHash(), typed.definitionHash()))
                throw new IllegalStateException("CAMPAIGN_PLANNING_ACCEPTED_BINDING_CHANGED");
            return loadTyped(typedRef, scope);
        }
        PlanningProfile planner = planner(header.profileRef(), header.profileVersion());
        Profile business = profile(header.profileRef(), header.profileVersion());
        if (!planner.modelRef().equals(header.modelRef()) || !planner.modelVersion().equals(header.modelVersion())
                || !planner.configurationHash().equals(header.configurationHash()))
            throw new IllegalArgumentException("CAMPAIGN_PLANNING_CONFIGURATION_CHANGED");
        var request = PlanningProposal.decodeRequest(planning.request(header), planner.proposalLimits());
        if (!header.runId().equals(request.inputs().runId())
                || !planner.menu().configurationId().equals(request.menu().configurationId()))
            throw new IllegalArgumentException("CAMPAIGN_PLANNING_INPUT_CHANGED");
        requirePlanningInputs(header, request, business);
        if (header.callbackActive() || header.state() == JdbcCampaignPlanningStore.State.DISPATCHING
                || header.state() == JdbcCampaignPlanningStore.State.UNKNOWN)
            throw new IllegalStateException("PLANNING_MODEL_UNRESOLVED");
        if (header.state() == JdbcCampaignPlanningStore.State.REJECTED)
            throw new IllegalStateException("PLANNING_UNRESOLVED");

        return () -> {
            if (header.state() == JdbcCampaignPlanningStore.State.PREPARED) {
                ModelCallBoundary boundary = (actual, live) -> {
                    requirePlanningInputs(header, request, business);
                    verifyPlanningPrompt(actual, request, planner);
                    var invocation = new ModelInvocationRegistry.InvocationSpec(header.requestId(), 1,
                            planner.modelRef(), planner.modelVersion(), planner.configurationHash(),
                            NativeCampaignPlanner.POLICY_REF, NativeCampaignPlanner.POLICY_VERSION,
                            request.inputs().inputSetRef(), ModelInvocationRegistry.encodeRequest(actual),
                            request.allowedArtifacts(), header.expiresAt());
                    var approval = planner.models().approve(invocation);
                    var permit = planning.begin(header, approval);
                    try {
                        requirePlanningInputs(header, request, business);
                        if (!planning.mayDispatch(permit)) throw new IllegalStateException("PLANNING_DISPATCH_REVOKED");
                        var response = live.get();
                        approval.validateResponse(response);
                        planning.publish(permit, approval, response);
                        return response;
                    } catch (RuntimeException | Error failed) {
                        planning.unknown(permit);
                        throw failed;
                    } finally { planning.callbackExited(permit); }
                };
                new NativeCampaignPlanner(planner.model(), boundary, scope, planner.modelLimits())
                        .generate(request, planner.proposalLimits());
            }
            var ready = planning.header(reference);
            requirePlanningInputs(ready, request, business);
            var invocation = planning.invocation(ready);
            if (!NativeCampaignPlanner.POLICY_REF.equals(invocation.policyRef())
                    || !NativeCampaignPlanner.POLICY_VERSION.equals(invocation.policyVersion())
                    || !request.inputs().inputSetRef().equals(invocation.inputSetRef())
                    || !request.allowedArtifacts().equals(invocation.inputs()))
                throw new IllegalStateException("CAMPAIGN_PLANNING_INVOCATION_CHANGED");
            verifyPlanningPrompt(ModelInvocationRegistry.decodeRequest(invocation.requestJson(), planner.modelLimits()), request, planner);
            var response = planning.response(ready, planner.models().approve(invocation));
            FrozenCampaignRun frozen;
            try {
                var candidate = PlanningProposal.parse(response.text(), planner.proposalLimits());
                var normalized = business.proposalNormalizer().normalize(ready.caller(), request, candidate);
                if (normalized == null) throw new IllegalArgumentException("CAMPAIGN_NORMALIZED_PROPOSAL_REQUIRED");
                // The provider receipt/hash remains unchanged. Only the separately validated
                // normalized definition is eligible for acceptance and immutable Run registration.
                frozen = normalized.materialize(
                        request, ready.planId(), 1, business.catalog(), business.contracts(), planner.proposalLimits());
                // Generic port/type validation cannot establish business goal dependencies. Reject
                // a domain-invalid candidate while its durable model response is still unaccepted.
                business.definitionValidator().validate(frozen.definition(ready.caller(), ready.sessionId()));
            } catch (IllegalArgumentException invalid) {
                planning.reject(ready);
                throw new IllegalStateException("PLANNING_UNRESOLVED", invalid);
            }
            requirePlanningInputs(ready, request, business);
            var typed = planning.accept(ready, frozen.definition(ready.caller(), ready.sessionId()));
            // Continue inside this same admitted scope. No second pool, nested submit or wait.
            loadTyped(new WorkRef(typed.runId(), typed.requestId()), scope).run();
        };
    }

    private void requirePlanningInputs(JdbcCampaignPlanningStore.Header header, PlanningProposal.Request request, Profile profile) {
        requirePlanningPrincipal(header);
        if (!profile.runAuthorizer().mayExecute(header.caller(), request.inputs())
                || !inputsAuthorized(profile, header.caller(), request.inputs()))
            throw new SecurityException("CAMPAIGN_INPUT_ACCESS_DENIED");
        for (var metadata : request.allowedArtifacts().values()) {
            var current = runs.inspectArtifact(header.caller(), metadata.ref().artifactId(), profile.artifactAuthorizer());
            if (!metadata.equals(current)) throw new SecurityException("CAMPAIGN_PLANNING_ARTIFACT_CHANGED");
        }
    }

    private void requirePlanningPrincipal(JdbcCampaignPlanningStore.Header header) {
        if (!header.caller().equals(caller(principals.resolve(header.caller(), header.sessionId()))))
            throw new SecurityException("CAMPAIGN_PRINCIPAL_CHANGED");
    }

    private PlanningProfile planner(String ref, String version) {
        PlanningProfile planner = planners.get(new ProfileKey(ref, version));
        if (planning == null || planner == null) throw new IllegalArgumentException("CAMPAIGN_PLANNING_PROFILE_UNAVAILABLE");
        return planner;
    }

    private static boolean planningReference(WorkRef reference) { return reference.workId().startsWith("planning-"); }

    private static void verifyPlanningPrompt(ModelInvocationRegistry.Request actual, PlanningProposal.Request request,
            PlanningProfile planner) {
        var users = actual.messages().stream().filter(message -> "user".equals(message.role())).toList();
        if (!actual.tools().isEmpty() || users.size() != 1 || !NativeCampaignPlanner.requestText(request, planner.proposalLimits())
                .equals(users.get(0).text())) throw new IllegalArgumentException("CAMPAIGN_PLANNING_PROMPT_CHANGED");
    }

    private boolean inputsAuthorized(Profile profile, Caller caller, FrozenInputSet inputs) {
        return inputs.inputValues().entrySet().stream().allMatch(entry -> {
            var contract = inputs.inputContracts().get(entry.getKey());
            return contract != null && (!Set.of("ScopeRef", "PeriodsRef").contains(contract.type().name())
                    || profile.inputAuthorizer().mayUse(caller, contract.type(), entry.getValue()));
        });
    }

    private static boolean sameDefinition(Header header, RunDefinition definition) {
        return header.caller().equals(definition.caller()) && header.sessionId().equals(definition.sessionId())
                && header.runId().equals(definition.runId()) && header.planId().equals(definition.planId())
                && header.revision() == definition.revision() && header.definitionHash().equals(definition.definitionHash());
    }

    private Profile profile(String ref, String version) {
        Profile profile = profiles.get(new ProfileKey(ref, version));
        if (profile == null) throw new IllegalArgumentException("CAMPAIGN_PROFILE_UNAVAILABLE");
        return profile;
    }

    private AgentPrincipal currentPrincipal(Header header) {
        AgentPrincipal current = principals.resolve(header.caller(), header.sessionId());
        if (!header.caller().equals(caller(current))) throw new SecurityException("CAMPAIGN_PRINCIPAL_CHANGED");
        return current;
    }

    private boolean principalCurrent(Header header) {
        try { return header.caller().equals(caller(principals.resolve(header.caller(), header.sessionId()))); }
        catch (SecurityException | IllegalArgumentException revoked) { return false; }
    }

    private void requirePrincipal(Caller expected, String sessionId, AgentPrincipal current) {
        if (!expected.equals(caller(current)) || !current.equals(principals.resolve(expected, sessionId)))
            throw new SecurityException("CAMPAIGN_PRINCIPAL_CHANGED");
    }

    private static Caller caller(AgentPrincipal principal) {
        if (principal == null || principal.system()) throw new SecurityException("CAMPAIGN_USER_PRINCIPAL_REQUIRED");
        return new Caller(principal.tenantId(), principal.username(), principal.authVersion());
    }

    private static void requireTopLevel() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("CAMPAIGN_INTAKE_REQUIRES_TOP_LEVEL");
    }
}
