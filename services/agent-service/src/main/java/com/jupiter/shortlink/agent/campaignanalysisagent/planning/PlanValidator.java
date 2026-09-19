package com.jupiter.shortlink.agent.campaignanalysisagent.planning;

import static com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanValidationException.Code.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Validates the entire proposal before a caller may dispatch any step. This class never executes
 * a Tool, performs authorization, evaluates expressions, or declares a goal ANSWERED.
 */
public final class PlanValidator {
    private static final Set<String> RESERVED_PARAMETERS = Set.of(
            "executor", "sql", "expression", "jsonpath", "url", "scope", "scoperef", "scopekind",
            "periodsref", "snapshot", "snapshotid", "artifactid", "jobid", "principal", "tenantid",
            "userid", "username", "gid", "groupid", "linkid", "linkids", "fullshorturl",
            "inputbindings", "inputsetref", "authorization", "token");

    private final CapabilityCatalog catalog;
    private final Function<String, Optional<CapabilityCatalog.TypeRef>> artifactTypes;

    public PlanValidator(CapabilityCatalog catalog) {
        this(catalog, ignored -> Optional.empty());
    }

    /** Artifact metadata must come from a trusted resolver; runtime read authorization is separate. */
    public PlanValidator(CapabilityCatalog catalog,
                         Function<String, Optional<CapabilityCatalog.TypeRef>> artifactTypes) {
        this.catalog = Objects.requireNonNull(catalog);
        this.artifactTypes = Objects.requireNonNull(artifactTypes);
    }

    public void validate(PlanSpec plan, FrozenInputSet inputs, PlanningAssessment assessment) {
        require(plan != null && PlanSpec.SCHEMA_VERSION.equals(plan.schemaVersion()), SCHEMA);
        require(reference(plan.planId()) && reference(plan.runId()) && plan.revision() > 0, IDENTITY);
        require(inputs != null && reference(plan.inputSetRef())
                && plan.inputSetRef().equals(inputs.inputSetRef())
                && plan.runId().equals(inputs.runId()), IDENTITY);
        require(assessment != null && plan.planId().equals(assessment.planId())
                && plan.revision() == assessment.revision(), IDENTITY);
        require(nonblank(catalog.version())
                && catalog.version().equals(assessment.capabilityCatalogVersion()), CATALOG_VERSION);
        validateInputs(inputs);

        Map<String, PlanSpec.Goal> goals = new HashMap<>();
        require(!plan.goals().isEmpty(), INVALID_GOAL);
        for (PlanSpec.Goal goal : plan.goals()) {
            require(reference(goal.goalId()) && nonblank(goal.question()) && nonblank(goal.acceptance()), INVALID_GOAL);
            require(goals.putIfAbsent(goal.goalId(), goal) == null, DUPLICATE_GOAL);
        }
        Map<String, PlanSpec.Step> steps = new HashMap<>();
        Map<String, CapabilityCatalog.Signature> signatures = new HashMap<>();
        for (PlanSpec.Step step : plan.steps()) {
            require(reference(step.stepId()), IDENTITY);
            require(steps.putIfAbsent(step.stepId(), step) == null, DUPLICATE_STEP);
            require(unique(step.goalIds()) && goals.keySet().containsAll(step.goalIds()), UNKNOWN_GOAL);
            CapabilityCatalog.Signature signature = signature(step, inputs);
            validateSignature(signature);
            require(signature.outputContractRef().equals(step.outputContractRef()), TYPE_MISMATCH);
            parameters(step.parameters(), signature.parameters());
            signatures.put(step.stepId(), signature);
        }

        Set<String> visited = new HashSet<>();
        for (PlanSpec.Step step : plan.steps()) {
            // Requiring declared topological order also rejects cycles and forward dependencies.
            require(unique(step.dependsOn()) && visited.containsAll(step.dependsOn()), INVALID_DEPENDENCY);
            Map<String, CapabilityCatalog.Port> ports = signatures.get(step.stepId()).inputs();
            require(ports.keySet().containsAll(step.inputBindings().keySet()), INVALID_BINDING);
            for (Map.Entry<String, CapabilityCatalog.Port> port : ports.entrySet()) {
                require(!port.getValue().required() || step.inputBindings().containsKey(port.getKey()), INVALID_BINDING);
            }
            for (Map.Entry<String, PlanBinding> binding : step.inputBindings().entrySet()) {
                CapabilityCatalog.TypeRef actual = bindingType(binding.getValue(), step, inputs, signatures);
                require(actual.equals(ports.get(binding.getKey()).type()), TYPE_MISMATCH);
            }
            visited.add(step.stepId());
        }
        validateAssessment(assessment, goals, steps, signatures);
    }

    private void validateInputs(FrozenInputSet inputs) {
        require(inputs.inputContracts().keySet().containsAll(inputs.inputValues().keySet()), INVALID_BINDING);
        for (Map.Entry<String, CapabilityCatalog.Port> entry : inputs.inputContracts().entrySet()) {
            require(nonblank(entry.getKey()), INVALID_BINDING);
            validType(entry.getValue().type());
            require(!entry.getValue().required() || inputs.inputValues().containsKey(entry.getKey()), INVALID_BINDING);
            if (!inputs.inputValues().containsKey(entry.getKey())) continue;
            Object value = inputs.inputValues().get(entry.getKey());
            require(value != null, INVALID_BINDING);
            if (entry.getValue().type().cardinality() == CapabilityCatalog.Cardinality.MANY) {
                require(value instanceof List<?>, TYPE_MISMATCH);
            } else {
                require(!(value instanceof List<?>), TYPE_MISMATCH);
            }
            if (Set.of("ScopeRef", "PeriodsRef").contains(entry.getValue().type().name())) {
                require(value instanceof String ref && reference(ref), INVALID_BINDING);
            }
        }
    }

    private CapabilityCatalog.Signature signature(PlanSpec.Step step, FrozenInputSet inputs) {
        if (step.executionMode() == PlanSpec.ExecutionMode.FIXED) {
            require(step.executor() != null && step.explorationPolicy() == null, INVALID_MODE);
            return capability(step.executor()).signature();
        }
        require(step.executionMode() == PlanSpec.ExecutionMode.REACT
                && step.executor() == null && step.explorationPolicy() != null, INVALID_MODE);
        PlanSpec.ExplorationPolicy proposal = step.explorationPolicy();
        require(nonblank(proposal.policyRef()) && nonblank(proposal.policyVersion()), UNREGISTERED_POLICY);
        CapabilityCatalog.Policy registered = catalog.policy(proposal.policyRef(), proposal.policyVersion())
                .orElseThrow(() -> new PlanValidationException(UNREGISTERED_POLICY));
        require(proposal.policyRef().equals(registered.policyRef())
                && proposal.policyVersion().equals(registered.policyVersion()), UNREGISTERED_POLICY);
        require(!proposal.allowedExecutors().isEmpty() && unique(proposal.allowedExecutors())
                && registered.allowedExecutors().containsAll(proposal.allowedExecutors()), POLICY_MISMATCH);
        for (PlanSpec.ExecutorRef executor : proposal.allowedExecutors()) capability(executor);
        require(!proposal.completionCriteria().isEmpty()
                && proposal.completionCriteria().equals(registered.completionCriteria())
                && nonblank(registered.terminationPolicyRef())
                && registered.terminationPolicyRef().equals(proposal.terminationPolicyRef()), POLICY_MISMATCH);
        for (PlanSpec.CriterionUse criterion : proposal.completionCriteria()) {
            require(nonblank(criterion.criterionRef()), POLICY_MISMATCH);
            safeParameters(criterion.parameters());
        }
        frozenBoundary("ScopeRef", proposal.scopeRef(), step, inputs, registered.signature());
        frozenBoundary("PeriodsRef", proposal.periodsRef(), step, inputs, registered.signature());
        return registered.signature();
    }

    private CapabilityCatalog.Capability capability(PlanSpec.ExecutorRef ref) {
        require(ref != null && ref.kind() != null && nonblank(ref.name()) && nonblank(ref.version()), UNREGISTERED_EXECUTOR);
        CapabilityCatalog.Capability definition = catalog.capability(ref)
                .orElseThrow(() -> new PlanValidationException(UNREGISTERED_EXECUTOR));
        require(ref.equals(definition.executor()), UNREGISTERED_EXECUTOR);
        require(!definition.startsExploration(), POLICY_MISMATCH);
        return definition;
    }

    private void frozenBoundary(String type, String reference, PlanSpec.Step step, FrozenInputSet inputs,
                                CapabilityCatalog.Signature signature) {
        require(reference(reference), POLICY_MISMATCH);
        long matches = signature.inputs().entrySet().stream()
                .filter(entry -> type.equals(entry.getValue().type().name()))
                .filter(entry -> {
                    PlanBinding binding = step.inputBindings().get(entry.getKey());
                    return binding != null && binding.source() == PlanBinding.Source.INPUT
                            && reference.equals(inputs.inputValues().get(binding.input()));
                }).count();
        require(matches == 1, POLICY_MISMATCH);
    }

    private CapabilityCatalog.TypeRef bindingType(PlanBinding binding, PlanSpec.Step step,
            FrozenInputSet inputs, Map<String, CapabilityCatalog.Signature> signatures) {
        require(binding != null && binding.source() != null, INVALID_BINDING);
        return switch (binding.source()) {
            case INPUT -> {
                require(nonblank(binding.input()) && binding.stepId() == null && binding.output() == null
                        && binding.artifactId() == null && inputs.inputValues().containsKey(binding.input()), INVALID_BINDING);
                yield inputs.inputContracts().get(binding.input()).type();
            }
            case STEP_OUTPUT -> {
                require(binding.input() == null && reference(binding.stepId()) && nonblank(binding.output())
                        && binding.artifactId() == null, INVALID_BINDING);
                require(step.dependsOn().contains(binding.stepId()), INVALID_DEPENDENCY);
                yield outputType(binding.stepId(), binding.output(), signatures);
            }
            case ARTIFACT -> {
                require(binding.input() == null && binding.stepId() == null && binding.output() == null
                        && reference(binding.artifactId()), INVALID_BINDING);
                CapabilityCatalog.TypeRef type = artifactTypes.apply(binding.artifactId())
                        .orElseThrow(() -> new PlanValidationException(UNKNOWN_ARTIFACT));
                validType(type);
                yield type;
            }
        };
    }

    private void validateAssessment(PlanningAssessment assessment, Map<String, PlanSpec.Goal> goals,
                                   Map<String, PlanSpec.Step> steps,
                                   Map<String, CapabilityCatalog.Signature> signatures) {
        Map<String, PlanningAssessment.Requirement> requirements = new HashMap<>();
        for (PlanningAssessment.Requirement requirement : assessment.requirements()) {
            require(reference(requirement.requirementId()) && goals.containsKey(requirement.goalId())
                    && requirement.kind() != null && nonblank(requirement.criterionRef())
                    && nonblank(requirement.criterionVersion()), INVALID_REQUIREMENT);
            require(requirements.putIfAbsent(requirement.requirementId(), requirement) == null, INVALID_REQUIREMENT);
        }
        Set<String> gaps = new HashSet<>();
        for (PlanningAssessment.Gap gap : assessment.gaps()) {
            require(requirements.containsKey(gap.requirementId()) && gap.reason() != null
                    && nonblank(gap.explanation()) && gaps.add(gap.requirementId()), INVALID_COVERAGE);
        }
        Map<String, PlanningAssessment.CoverageBinding> coverage = new HashMap<>();
        for (PlanningAssessment.CoverageBinding binding : assessment.coverageBindings()) {
            require(requirements.containsKey(binding.requirementId())
                    && coverage.putIfAbsent(binding.requirementId(), binding) == null
                    && !binding.evidenceOutputs().isEmpty() && unique(binding.evidenceOutputs()), INVALID_COVERAGE);
        }
        for (PlanningAssessment.Requirement requirement : requirements.values()) {
            safeParameters(requirement.parameters());
            Optional<CapabilityCatalog.Criterion> registered = catalog.criterion(
                    requirement.criterionRef(), requirement.criterionVersion());
            require(registered.isPresent() || gaps.contains(requirement.requirementId()), UNKNOWN_CRITERION);
            PlanningAssessment.CoverageBinding binding = coverage.get(requirement.requirementId());
            require(!requirement.required() || binding != null || gaps.contains(requirement.requirementId()), INVALID_COVERAGE);
            Set<CapabilityCatalog.TypeRef> evidenceTypes = new HashSet<>();
            if (binding != null) {
                for (PlanningAssessment.EvidenceOutput output : binding.evidenceOutputs()) {
                    evidenceTypes.add(outputType(output.stepId(), output.output(), signatures));
                    require(steps.get(output.stepId()).goalIds().contains(requirement.goalId()), INVALID_COVERAGE);
                }
            }
            if (registered.isEmpty()) continue; // The explicit gap prevents a claim of complete coverage.
            CapabilityCatalog.Criterion criterion = registered.get();
            require(requirement.criterionRef().equals(criterion.criterionRef())
                    && requirement.criterionVersion().equals(criterion.criterionVersion())
                    && requirement.kind() == criterion.kind(), INVALID_REQUIREMENT);
            parameters(requirement.parameters(), criterion.parameters());
            if (binding != null) {
                require(criterion.requiredEvidenceTypes().containsAll(evidenceTypes), TYPE_MISMATCH);
                require(gaps.contains(requirement.requirementId())
                        || evidenceTypes.containsAll(criterion.requiredEvidenceTypes()), INVALID_COVERAGE);
            }
        }
        for (String goalId : goals.keySet()) {
            require(requirements.values().stream().anyMatch(requirement -> goalId.equals(requirement.goalId())
                    && requirement.required() && requirement.kind() == PlanningAssessment.RequirementKind.DELIVERY), MISSING_DELIVERY);
        }
    }

    private CapabilityCatalog.TypeRef outputType(String stepId, String output,
                                                 Map<String, CapabilityCatalog.Signature> signatures) {
        CapabilityCatalog.Signature signature = signatures.get(stepId);
        require(signature != null && signature.outputs().containsKey(output), INVALID_BINDING);
        return signature.outputs().get(output).type();
    }

    private void validateSignature(CapabilityCatalog.Signature signature) {
        require(signature != null && nonblank(signature.outputContractRef()) && signature.parameters() != null, TYPE_MISMATCH);
        for (Map<String, CapabilityCatalog.Port> ports : List.of(signature.inputs(), signature.outputs())) {
            for (Map.Entry<String, CapabilityCatalog.Port> port : ports.entrySet()) {
                require(nonblank(port.getKey()), TYPE_MISMATCH);
                validType(port.getValue().type());
            }
        }
    }

    private void parameters(Map<String, Object> values, CapabilityCatalog.Parameters rules) {
        safeParameters(values);
        require(rules != null && values.keySet().containsAll(rules.required())
                && rules.properties().keySet().containsAll(values.keySet()), INVALID_PARAMETERS);
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            boolean accepted;
            try {
                accepted = rules.properties().get(entry.getKey()).test(entry.getValue());
            } catch (RuntimeException ignored) {
                accepted = false;
            }
            require(accepted, INVALID_PARAMETERS);
        }
    }

    private void safeParameters(Object value) {
        safeParameters(value, 0);
    }

    private void safeParameters(Object value, int depth) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            require(depth < ImmutablePlanValues.MAX_JSON_NESTING, INVALID_PARAMETERS);
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String normalized = entry.getKey().toString().replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
                require(!RESERVED_PARAMETERS.contains(normalized), INVALID_PARAMETERS);
                safeParameters(entry.getValue(), depth + 1);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) safeParameters(item, depth + 1);
        }
    }

    private void validType(CapabilityCatalog.TypeRef type) {
        require(type != null && nonblank(type.name()) && type.schemaMajorVersion() > 0
                && type.cardinality() != null, TYPE_MISMATCH);
    }

    private static boolean unique(List<?> values) { return new HashSet<>(values).size() == values.size(); }
    private static boolean nonblank(String value) { return value != null && !value.isBlank(); }
    private static boolean reference(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*"); }
    private static void require(boolean condition, PlanValidationException.Code code) {
        if (!condition) throw new PlanValidationException(code);
    }
}
