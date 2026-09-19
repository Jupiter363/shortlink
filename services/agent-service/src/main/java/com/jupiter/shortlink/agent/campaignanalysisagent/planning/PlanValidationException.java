package com.jupiter.shortlink.agent.campaignanalysisagent.planning;

/** Safe classification; messages never include planner parameter values. */
public final class PlanValidationException extends IllegalArgumentException {
    public enum Code {
        SCHEMA, IDENTITY, INVALID_GOAL, DUPLICATE_GOAL, DUPLICATE_STEP, UNKNOWN_GOAL,
        INVALID_MODE, CATALOG_VERSION, UNREGISTERED_EXECUTOR, UNREGISTERED_POLICY,
        POLICY_MISMATCH, INVALID_PARAMETERS, INVALID_BINDING, INVALID_DEPENDENCY,
        TYPE_MISMATCH, UNKNOWN_ARTIFACT, INVALID_REQUIREMENT, UNKNOWN_CRITERION,
        INVALID_COVERAGE, MISSING_DELIVERY
    }

    private final Code code;

    public PlanValidationException(Code code) {
        super("Invalid campaign plan: " + code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
