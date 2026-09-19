package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import java.util.Objects;

/** Server-frozen definition, stored once with the run; never reconstructed from latest chat state. */
public record FrozenCampaignRun(String schemaVersion, String runnerVersion, String topologyVersion,
                                PlanSpec plan, FrozenInputSet inputs, PlanningAssessment assessment) {
    public static final String SCHEMA = "campaign-run/v1";
    public static final String RUNNER = "jdbc-step/v1";
    public static final String TOPOLOGY = "frozen-scan/v1";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    public FrozenCampaignRun {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(inputs);
        Objects.requireNonNull(assessment);
        if (!SCHEMA.equals(schemaVersion) || !RUNNER.equals(runnerVersion) || !TOPOLOGY.equals(topologyVersion))
            throw new IllegalArgumentException("RUN_VERSION_UNSUPPORTED");
    }

    public static FrozenCampaignRun freeze(PlanSpec plan, FrozenInputSet inputs, PlanningAssessment assessment) {
        return new FrozenCampaignRun(SCHEMA, RUNNER, TOPOLOGY, plan, inputs, assessment);
    }

    public RunDefinition definition(Caller caller, String sessionId) {
        return new RunDefinition(caller, sessionId, plan.runId(), plan.planId(), plan.revision(), encode(this));
    }

    public static FrozenCampaignRun read(RunDefinition definition) {
        try {
            FrozenCampaignRun frozen = JSON.readValue(definition.definitionJson(), FrozenCampaignRun.class);
            if (!definition.runId().equals(frozen.plan().runId())
                    || !definition.planId().equals(frozen.plan().planId())
                    || definition.revision() != frozen.plan().revision())
                throw new IllegalArgumentException("RUN_PLAN_IDENTITY_MISMATCH");
            return frozen;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("RUN_DEFINITION_INVALID", exception);
        }
    }

    static String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("RUN_DEFINITION_INVALID", exception); }
    }
}
