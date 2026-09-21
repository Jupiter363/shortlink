package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCandidateStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, server-owned registrations used by one frozen campaign plan.
 *
 * <p>This is deliberately a plain Java value boundary.  A plan can select an already registered
 * executor, policy and contract, but it cannot add one.  Saver instances are supplied only at
 * the explicit compile boundary through {@link SaverBinding}; this registry never looks up a
 * Spring bean or creates a checkpoint store.</p>
 */
public final class CampaignPlanRuntimeRegistry {
    /** Versions describe one trusted executor/catalog/contract/runtime composition. */
    public record Versions(String catalogVersion, String contractSetVersion, String executorSetVersion,
                           String runnerVersion, String topologyVersion) {
        public Versions {
            reference(catalogVersion, "PLAN_RUNTIME_CATALOG_VERSION_REQUIRED");
            reference(contractSetVersion, "PLAN_RUNTIME_CONTRACT_VERSION_REQUIRED");
            reference(executorSetVersion, "PLAN_RUNTIME_EXECUTOR_VERSION_REQUIRED");
            reference(runnerVersion, "PLAN_RUNTIME_RUNNER_VERSION_REQUIRED");
            reference(topologyVersion, "PLAN_RUNTIME_TOPOLOGY_VERSION_REQUIRED");
        }
    }

    /** Stable server-owned key for the checkpoint implementation selected by a caller. */
    public record SaverKey(String ref, String version) {
        public SaverKey {
            reference(ref, "PLAN_RUNTIME_SAVER_REF_REQUIRED");
            reference(version, "PLAN_RUNTIME_SAVER_VERSION_REQUIRED");
        }
    }

    /** Explicit saver handoff; no default provider or implicit Spring lookup is permitted. */
    public record SaverBinding(SaverKey key, BaseCheckpointSaver saver) {
        public SaverBinding {
            Objects.requireNonNull(key, "PLAN_RUNTIME_SAVER_KEY_REQUIRED");
            Objects.requireNonNull(saver, "PLAN_RUNTIME_SAVER_REQUIRED");
        }
    }

    private record PolicyKey(String ref, String version) { }

    private final Versions versions;
    private final CapabilityCatalog catalog;
    private final ArtifactContractRegistry contracts;
    private final Map<PlanSpec.ExecutorRef, PersistentPlanDriver.FixedExecutor> fixedExecutors;
    private final Map<PolicyKey, PersistentPlanDriver.ReactExecutor> reactExecutors;
    private final CampaignExplorationCandidateStore candidates;
    private final SaverKey saverKey;

    public CampaignPlanRuntimeRegistry(Versions versions,
                                       CapabilityCatalog catalog,
                                       ArtifactContractRegistry contracts,
                                       List<PersistentPlanDriver.FixedExecutor> fixedExecutors,
                                       List<PersistentPlanDriver.ReactExecutor> reactExecutors,
                                       CampaignExplorationCandidateStore candidates,
                                       SaverKey saverKey) {
        this.versions = Objects.requireNonNull(versions, "PLAN_RUNTIME_VERSIONS_REQUIRED");
        this.catalog = Objects.requireNonNull(catalog, "PLAN_RUNTIME_CATALOG_REQUIRED");
        this.contracts = Objects.requireNonNull(contracts, "PLAN_RUNTIME_CONTRACTS_REQUIRED");
        if (!versions.catalogVersion().equals(catalog.version())) {
            throw new IllegalArgumentException("PLAN_RUNTIME_CATALOG_VERSION_MISMATCH");
        }
        this.fixedExecutors = fixedMap(fixedExecutors);
        this.reactExecutors = reactMap(reactExecutors);
        this.candidates = candidates;
        this.saverKey = Objects.requireNonNull(saverKey, "PLAN_RUNTIME_SAVER_KEY_REQUIRED");
    }

    public Versions versions() { return versions; }
    public CapabilityCatalog catalog() { return catalog; }
    public ArtifactContractRegistry contracts() { return contracts; }
    public CampaignExplorationCandidateStore candidates() { return candidates; }
    public SaverKey saverKey() { return saverKey; }

    /** Returns a defensive, deterministic view suitable for the existing driver constructor. */
    public List<PersistentPlanDriver.FixedExecutor> fixedExecutors() {
        return List.copyOf(fixedExecutors.values());
    }

    /** Returns a defensive, deterministic view suitable for the existing driver constructor. */
    public List<PersistentPlanDriver.ReactExecutor> reactExecutors() {
        return List.copyOf(reactExecutors.values());
    }

    /**
     * Checks all plan-selected registrations without constructing a driver or touching a ledger.
     * Full signature and binding validation remains delegated to PlanValidator/PersistentPlanDriver.
     */
    public void validate(FrozenCampaignRun frozen) {
        Objects.requireNonNull(frozen, "PLAN_RUNTIME_FROZEN_REQUIRED");
        if (!versions.runnerVersion().equals(frozen.runnerVersion())
                || !versions.topologyVersion().equals(frozen.topologyVersion())) {
            throw new IllegalArgumentException("PLAN_RUNTIME_FROZEN_VERSION_MISMATCH");
        }
        if (!versions.catalogVersion().equals(frozen.assessment().capabilityCatalogVersion())
                || !catalog.version().equals(frozen.assessment().capabilityCatalogVersion())) {
            throw new IllegalArgumentException("PLAN_RUNTIME_CATALOG_VERSION_MISMATCH");
        }
        for (PlanSpec.Step step : frozen.plan().steps()) {
            if (step.executionMode() == PlanSpec.ExecutionMode.FIXED) {
                if (step.executor() == null || !fixedExecutors.containsKey(step.executor())
                        || catalog.capability(step.executor()).isEmpty()) {
                    throw new IllegalArgumentException("PLAN_RUNTIME_EXECUTOR_UNAVAILABLE");
                }
            } else {
                if (step.explorationPolicy() == null) {
                    throw new IllegalArgumentException("PLAN_RUNTIME_POLICY_REQUIRED");
                }
                PolicyKey key = new PolicyKey(step.explorationPolicy().policyRef(),
                        step.explorationPolicy().policyVersion());
                if (candidates == null || !reactExecutors.containsKey(key)
                        || catalog.policy(key.ref(), key.version()).isEmpty()) {
                    throw new IllegalArgumentException("PLAN_RUNTIME_REACT_UNAVAILABLE");
                }
            }
        }
    }

    /** Validates an explicit saver handoff and returns the trusted instance. */
    public BaseCheckpointSaver requireSaver(SaverBinding binding) {
        Objects.requireNonNull(binding, "PLAN_RUNTIME_SAVER_BINDING_REQUIRED");
        if (!saverKey.equals(binding.key())) {
            throw new IllegalArgumentException("PLAN_RUNTIME_SAVER_MISMATCH");
        }
        return binding.saver();
    }

    private static Map<PlanSpec.ExecutorRef, PersistentPlanDriver.FixedExecutor> fixedMap(
            List<PersistentPlanDriver.FixedExecutor> registrations) {
        Objects.requireNonNull(registrations, "PLAN_RUNTIME_EXECUTORS_REQUIRED");
        Map<PlanSpec.ExecutorRef, PersistentPlanDriver.FixedExecutor> accepted = new LinkedHashMap<>();
        for (PersistentPlanDriver.FixedExecutor registration : List.copyOf(registrations)) {
            Objects.requireNonNull(registration, "PLAN_RUNTIME_EXECUTOR_REQUIRED");
            if (accepted.putIfAbsent(registration.ref(), registration) != null) {
                throw new IllegalArgumentException("PLAN_RUNTIME_EXECUTOR_DUPLICATE");
            }
        }
        return Map.copyOf(accepted);
    }

    private static Map<PolicyKey, PersistentPlanDriver.ReactExecutor> reactMap(
            List<PersistentPlanDriver.ReactExecutor> registrations) {
        Objects.requireNonNull(registrations, "PLAN_RUNTIME_REACT_EXECUTORS_REQUIRED");
        Map<PolicyKey, PersistentPlanDriver.ReactExecutor> accepted = new LinkedHashMap<>();
        for (PersistentPlanDriver.ReactExecutor registration : List.copyOf(registrations)) {
            Objects.requireNonNull(registration, "PLAN_RUNTIME_REACT_EXECUTOR_REQUIRED");
            PolicyKey key = new PolicyKey(registration.policyRef(), registration.policyVersion());
            if (accepted.putIfAbsent(key, registration) != null) {
                throw new IllegalArgumentException("PLAN_RUNTIME_REACT_EXECUTOR_DUPLICATE");
            }
        }
        return Map.copyOf(accepted);
    }

    private static void reference(String value, String code) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(code);
        }
    }
}
