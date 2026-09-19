package com.jupiter.shortlink.agent.campaignanalysisagent.planning;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/** Server-owned, versioned metadata only. A proposal cannot register its own capabilities. */
public interface CapabilityCatalog {
    String version();
    Optional<Capability> capability(PlanSpec.ExecutorRef executor);
    Optional<Policy> policy(String policyRef, String policyVersion);
    Optional<Criterion> criterion(String criterionRef, String criterionVersion);

    enum Cardinality { ONE, MANY }

    record TypeRef(String name, int schemaMajorVersion, Cardinality cardinality) { }

    record Port(TypeRef type, boolean required) { }

    /** Validators are trusted application code, not expressions supplied in a plan. */
    record Parameters(Set<String> required, Map<String, Predicate<Object>> properties) {
        public Parameters {
            required = Set.copyOf(required);
            properties = Map.copyOf(properties);
            if (!properties.keySet().containsAll(required)) {
                throw new IllegalArgumentException("Required parameters must have registered validators");
            }
        }

        public static Parameters none() {
            return new Parameters(Set.of(), Map.of());
        }
    }

    record Signature(Map<String, Port> inputs, String outputContractRef,
                     Map<String, Port> outputs, Parameters parameters) {
        public Signature {
            inputs = ImmutablePlanValues.map(inputs);
            outputs = ImmutablePlanValues.map(outputs);
        }
    }

    /** startsExploration includes exploration in transitive Skill calls. */
    record Capability(PlanSpec.ExecutorRef executor, Signature signature, boolean startsExploration) { }

    record Policy(String policyRef, String policyVersion, Signature signature,
                  Set<PlanSpec.ExecutorRef> allowedExecutors,
                  List<PlanSpec.CriterionUse> completionCriteria, String terminationPolicyRef) {
        public Policy {
            allowedExecutors = Set.copyOf(allowedExecutors);
            completionCriteria = ImmutablePlanValues.list(completionCriteria);
        }
    }

    record Criterion(String criterionRef, String criterionVersion,
                     PlanningAssessment.RequirementKind kind, Parameters parameters,
                     Set<TypeRef> requiredEvidenceTypes) {
        public Criterion {
            requiredEvidenceTypes = Set.copyOf(requiredEvidenceTypes);
        }
    }
}
