package com.jupiter.shortlink.agent.campaignanalysisagent.planning;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/** Closed binding syntax. No expression, template, SQL or JSONPath evaluation. */
public record PlanBinding(Source source, String input, String stepId, String output, String artifactId) {
    public enum Source { INPUT, STEP_OUTPUT, ARTIFACT }

    public static PlanBinding input(String name) {
        return new PlanBinding(Source.INPUT, name, null, null, null);
    }

    public static PlanBinding output(String stepId, String output) {
        return new PlanBinding(Source.STEP_OUTPUT, null, stepId, output, null);
    }

    public static PlanBinding artifact(String artifactId) {
        return new PlanBinding(Source.ARTIFACT, null, null, null, artifactId);
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unsupported plan binding field");
    }
}
