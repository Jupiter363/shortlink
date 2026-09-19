package com.jupiter.shortlink.agent.campaignanalysisagent.planning;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.List;
import java.util.Map;

/** One immutable candidate plan. This record contains no execution or authorization state. */
public record PlanSpec(String schemaVersion, String planId, int revision, String runId,
                       String inputSetRef, List<Goal> goals, List<Step> steps) {
    public static final String SCHEMA_VERSION = "campaign-plan/v1";

    public PlanSpec {
        goals = ImmutablePlanValues.list(goals);
        steps = ImmutablePlanValues.list(steps);
    }

    public enum ExecutionMode { FIXED, REACT }
    public enum ExecutorKind { TOOL, SKILL }

    public record Goal(String goalId, String question, boolean required, String acceptance) { }

    public record ExecutorRef(ExecutorKind kind, String name, String version) { }

    public record CriterionUse(String criterionRef, Map<String, Object> parameters) {
        public CriterionUse {
            parameters = ImmutablePlanValues.json(parameters);
        }
    }

    public record ExplorationPolicy(String policyRef, String policyVersion,
                                    List<ExecutorRef> allowedExecutors, String scopeRef, String periodsRef,
                                    List<CriterionUse> completionCriteria, String terminationPolicyRef) {
        public ExplorationPolicy {
            allowedExecutors = ImmutablePlanValues.list(allowedExecutors);
            completionCriteria = ImmutablePlanValues.list(completionCriteria);
        }

        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unsupported exploration policy field");
        }
    }

    public record Step(String stepId, List<String> goalIds, ExecutionMode executionMode,
                       ExecutorRef executor, ExplorationPolicy explorationPolicy,
                       List<String> dependsOn, Map<String, PlanBinding> inputBindings,
                       Map<String, Object> parameters, String outputContractRef) {
        public Step {
            goalIds = ImmutablePlanValues.list(goalIds);
            executionMode = executionMode == null ? ExecutionMode.FIXED : executionMode;
            dependsOn = ImmutablePlanValues.list(dependsOn);
            inputBindings = ImmutablePlanValues.map(inputBindings);
            parameters = ImmutablePlanValues.json(parameters);
        }

        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unsupported plan step field");
        }
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unsupported plan field");
    }
}
