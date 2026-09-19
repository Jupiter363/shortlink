package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Cardinality;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Port;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Signature;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.TypeRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanBinding;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry.BoundArtifact;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactAuthorizer;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactMetadata;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.BindingException.Code.*;

/**
 * Resolves only a validated frozen step's closed bindings. No execution, expression evaluation,
 * output discovery, plan mutation, goal assessment or production registration happens here.
 */
public final class StepBindings {
    @FunctionalInterface
    public interface CompletedOutputLookup {
        /** Trusted ledger lookup: only the named port of a SUCCEEDED producer in this frozen run. */
        Optional<String> outputArtifact(String stepId, String outputName);
    }

    @FunctionalInterface
    public interface CurrentInputAuthorizer {
        /** Recheck current rights to the exact frozen ScopeRef/PeriodsRef; never rewrite it. */
        boolean mayUse(Caller caller, TypeRef type, Object frozenValue);
    }

    /** Registered with an executor by trusted code, not derived from model parameters. */
    public interface StepPolicy {
        void validateInputs(PlanSpec.Step step, BoundInputs inputs);
        void validateOutputs(PlanSpec.Step step, BoundInputs inputs, Map<String, BoundArtifact> outputs);
    }

    private final ArtifactContractRegistry registry;
    private final CampaignRunStore store;
    private final Caller caller;
    private final ArtifactAuthorizer artifactAuthorizer;
    private final CurrentInputAuthorizer inputAuthorizer;
    private final StepPolicy policy;

    public StepBindings(ArtifactContractRegistry registry, CampaignRunStore store, Caller caller,
                        ArtifactAuthorizer artifactAuthorizer, CurrentInputAuthorizer inputAuthorizer,
                        StepPolicy policy) {
        this.registry = Objects.requireNonNull(registry);
        this.store = Objects.requireNonNull(store);
        this.caller = Objects.requireNonNull(caller);
        this.artifactAuthorizer = Objects.requireNonNull(artifactAuthorizer);
        this.inputAuthorizer = Objects.requireNonNull(inputAuthorizer);
        this.policy = Objects.requireNonNull(policy);
    }

    public BoundInputs resolve(PlanSpec.Step step, FrozenInputSet frozen, Signature signature,
                               CompletedOutputLookup completedOutputs) {
        Objects.requireNonNull(step);
        Objects.requireNonNull(frozen);
        Objects.requireNonNull(signature);
        Objects.requireNonNull(completedOutputs);
        requirePorts(signature.inputs(), step.inputBindings(), INVALID_BINDING);
        Map<String, Object> values = new HashMap<>();
        Map<String, BoundArtifact> artifacts = new HashMap<>();
        for (Map.Entry<String, PlanBinding> entry : step.inputBindings().entrySet()) {
            String name = entry.getKey();
            TypeRef expected = signature.inputs().get(name).type();
            PlanBinding binding = entry.getValue();
            requireClosed(binding);
            if (binding.source() == PlanBinding.Source.INPUT) {
                Port actual = frozen.inputContracts().get(binding.input());
                Object value = frozen.inputValues().get(binding.input());
                if (actual == null || !expected.equals(actual.type())) throw new BindingException(TYPE_MISMATCH);
                if (value == null) throw new BindingException(INVALID_BINDING);
                if ((expected.cardinality() == Cardinality.MANY) != (value instanceof List<?>)) {
                    throw new BindingException(TYPE_MISMATCH);
                }
                if (Set.of("ScopeRef", "PeriodsRef").contains(expected.name())) {
                    if (!(value instanceof String ref) || ref.isBlank()) throw new BindingException(INVALID_BINDING);
                    if (!inputAuthorizer.mayUse(caller, expected, value)) throw new BindingException(INPUT_ACCESS_DENIED);
                }
                values.put(name, value);
            } else {
                String artifactId;
                if (binding.source() == PlanBinding.Source.STEP_OUTPUT) {
                    if (!step.dependsOn().contains(binding.stepId())) throw new BindingException(INVALID_BINDING);
                    artifactId = completedOutputs.outputArtifact(binding.stepId(), binding.output())
                            .orElseThrow(() -> new BindingException(UPSTREAM_OUTPUT_UNAVAILABLE));
                } else {
                    // Only this opaque identity from the frozen plan; no late overrides or discovery.
                    artifactId = binding.artifactId();
                }
                BoundArtifact artifact = registry.validateArtifact(expected, artifactId, store, caller, artifactAuthorizer);
                artifacts.put(name, artifact);
                values.put(name, artifact.payload());
            }
        }
        BoundInputs result = new BoundInputs(values, artifacts);
        try {
            policy.validateInputs(step, result);
            return result;
        } catch (RuntimeException | Error failure) {
            result.close();
            throw failure;
        }
    }

    /** Validate all named outputs before publishing the step's success; never implies Goal ANSWERED. */
    public Map<String, ArtifactMetadata> validateOutputs(PlanSpec.Step step, Signature signature,
                                                        BoundInputs inputs, Map<String, String> outputIds) {
        Objects.requireNonNull(step);
        Objects.requireNonNull(signature);
        Objects.requireNonNull(inputs).values(); // Reject use after invocation closure.
        if (!Objects.equals(step.outputContractRef(), signature.outputContractRef())) {
            throw new BindingException(OUTPUT_CONTRACT_MISMATCH);
        }
        requirePorts(signature.outputs(), outputIds, OUTPUT_CONTRACT_MISMATCH);
        Map<String, BoundArtifact> outputs = new HashMap<>();
        Map<String, ArtifactMetadata> metadata = new HashMap<>();
        for (Map.Entry<String, String> output : outputIds.entrySet()) {
            BoundArtifact artifact = registry.validateArtifact(signature.outputs().get(output.getKey()).type(),
                    output.getValue(), store, caller, artifactAuthorizer);
            outputs.put(output.getKey(), artifact);
            metadata.put(output.getKey(), artifact.metadata());
        }
        policy.validateOutputs(step, inputs, Map.copyOf(outputs));
        return Map.copyOf(metadata);
    }

    /** Each real I/O gate calls this again; no cached decision or repeated payload materialization. */
    public void reauthorize(Signature signature, BoundInputs inputs) {
        Objects.requireNonNull(signature);
        Map<String, Object> values = Objects.requireNonNull(inputs).values();
        requirePorts(signature.inputs(), values, INVALID_BINDING);
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            TypeRef expected = signature.inputs().get(entry.getKey()).type();
            BoundArtifact artifact = inputs.artifact(entry.getKey());
            if (artifact != null) {
                ArtifactMetadata current = store.inspectArtifact(caller, artifact.metadata().ref().artifactId(), artifactAuthorizer);
                if (!expected.equals(registry.typeOf(current))) throw new BindingException(TYPE_MISMATCH);
                if (!artifact.metadata().equals(current)) throw new BindingException(ARTIFACT_CONTRACT_MISMATCH);
            } else if (Set.of("ScopeRef", "PeriodsRef").contains(expected.name())
                    && !inputAuthorizer.mayUse(caller, expected, entry.getValue())) {
                throw new BindingException(INPUT_ACCESS_DENIED);
            }
        }
    }

    private static void requirePorts(Map<String, Port> ports, Map<String, ?> values, BindingException.Code code) {
        if (values == null || !ports.keySet().containsAll(values.keySet())) throw new BindingException(code);
        for (Map.Entry<String, Port> port : ports.entrySet()) {
            if (port.getValue().required() && !values.containsKey(port.getKey())) throw new BindingException(code);
        }
    }

    private static void requireClosed(PlanBinding binding) {
        if (binding == null || binding.source() == null) throw new BindingException(INVALID_BINDING);
        boolean valid = switch (binding.source()) {
            case INPUT -> reference(binding.input()) && binding.stepId() == null
                    && binding.output() == null && binding.artifactId() == null;
            case STEP_OUTPUT -> binding.input() == null && reference(binding.stepId())
                    && reference(binding.output()) && binding.artifactId() == null;
            case ARTIFACT -> binding.input() == null && binding.stepId() == null
                    && binding.output() == null && reference(binding.artifactId());
        };
        if (!valid) throw new BindingException(INVALID_BINDING);
    }

    private static boolean reference(String value) { return value != null && !value.isBlank(); }
}
