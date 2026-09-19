package com.jupiter.shortlink.agent.campaignanalysisagent.planning;

import java.util.Map;

/** Supplied by the trusted input resolver, never read from a mutable latest-session map. */
public record FrozenInputSet(String inputSetRef, String runId,
                             Map<String, CapabilityCatalog.Port> inputContracts,
                             Map<String, Object> inputValues) {
    public FrozenInputSet {
        inputContracts = ImmutablePlanValues.map(inputContracts);
        inputValues = ImmutablePlanValues.json(inputValues);
    }
}
