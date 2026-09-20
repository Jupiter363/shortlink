package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanValidator;
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
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore.Header;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignRecoveryCoordinator;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReleaser;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsSubmissionReconciler;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Internal opt-in intake for server-resolved typed plans. No HTTP/Spring registration, text planner,
 * model loop or scheduler. One instance shares admission across its registered runtime profiles.
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

    /** The factory must use these exact gates and scope in its Driver and descendant adapters. */
    public record RuntimeContext(RunToken token, AgentPrincipal principal, ProcessExecutionScope scope,
                                 PersistentPlanDriver.RunAuthorizer runAuthorizer,
                                 ArtifactAuthorizer artifactAuthorizer,
                                 StepBindings.CurrentInputAuthorizer inputAuthorizer) {}

    public record Profile(String ref, String version, CapabilityCatalog catalog, ArtifactContractRegistry contracts,
                          PersistentPlanDriver.RunAuthorizer runAuthorizer, ArtifactAuthorizer artifactAuthorizer,
                          StepBindings.CurrentInputAuthorizer inputAuthorizer, RuntimeFactory runtimeFactory) {
        public Profile {
            if (ref == null || ref.isBlank() || ref.length() > 128
                    || version == null || version.isBlank() || version.length() > 128)
                throw new IllegalArgumentException("CAMPAIGN_PROFILE_REQUIRED");
            Objects.requireNonNull(catalog); Objects.requireNonNull(contracts);
            Objects.requireNonNull(runAuthorizer); Objects.requireNonNull(artifactAuthorizer);
            Objects.requireNonNull(inputAuthorizer); Objects.requireNonNull(runtimeFactory);
        }
    }

    private record ProfileKey(String ref, String version) {}
    private final JdbcCampaignRunIntakeStore requests;
    private final CampaignRunStore runs;
    private final CampaignRecoveryStore recovery;
    private final StatisticsSubmissionReconciler submissions;
    private final StatisticsJobResultReceiver receiver;
    private final StatisticsJobResultReleaser releaser;
    private final Map<ProfileKey, Profile> profiles;
    private final CurrentPrincipalResolver principals;
    private final AdmittedCampaignAdvance admitted;

    public CampaignRunIntake(JdbcCampaignRunIntakeStore requests, CampaignRunStore runs,
            CampaignRecoveryStore recovery, StatisticsSubmissionReconciler submissions,
            StatisticsJobResultReceiver receiver, StatisticsJobResultReleaser releaser,
            List<Profile> profiles, CurrentPrincipalResolver principals,
            ProcessCapacityExecutor.Limits limits, Executor executor) {
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
        receipt(current, reference);
        return admitted.submit(reference);
    }

    public ProcessCapacityExecutor.Snapshot snapshot() { return admitted.snapshot(); }
    @Override public void close() { admitted.close(); }

    private AdmittedCampaignAdvance.Operation loadAdmitted(WorkRef reference, ProcessExecutionScope scope) {
        Header header = requests.header(reference);
        AgentPrincipal principal = currentPrincipal(header);
        Profile profile = profile(header.profileRef(), header.profileVersion());
        RunDefinition definition = requests.definition(header);
        FrozenCampaignRun frozen = FrozenCampaignRun.read(definition);
        PersistentPlanDriver.RunAuthorizer runGate = (caller, inputs) -> header.caller().equals(caller)
                && principalCurrent(header) && profile.runAuthorizer().mayExecute(caller, inputs)
                && inputsAuthorized(profile, caller, inputs);
        ArtifactAuthorizer artifactGate = (caller, artifact) -> header.caller().equals(caller)
                && principalCurrent(header) && profile.artifactAuthorizer().mayRead(caller, artifact);
        StepBindings.CurrentInputAuthorizer inputGate = (caller, type, value) -> header.caller().equals(caller)
                && principalCurrent(header) && profile.inputAuthorizer().mayUse(caller, type, value);
        if (!runGate.mayExecute(header.caller(), frozen.inputs())) throw new SecurityException("CAMPAIGN_INPUT_ACCESS_DENIED");
        var validator = new PlanValidator(profile.catalog(), id -> Optional.of(profile.contracts().typeOf(
                runs.inspectArtifact(header.caller(), id, artifactGate))));
        validator.validate(frozen.plan(), frozen.inputs(), frozen.assessment());
        if (!runGate.mayExecute(header.caller(), frozen.inputs())) throw new SecurityException("CAMPAIGN_INPUT_ACCESS_DENIED");
        requests.freeze(header, definition);
        var run = runs.loadRun(header.caller(), header.runId()).orElseThrow(
                () -> new IllegalStateException("CAMPAIGN_FROZEN_RUN_MISSING"));
        if (run.status() != RunStatus.ACTIVE) throw new IllegalStateException("CAMPAIGN_RUN_" + run.status().name());
        if (!sameDefinition(header, run.definition())) throw new IllegalStateException("CAMPAIGN_REQUEST_REVISION_CHANGED");

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
        };
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
