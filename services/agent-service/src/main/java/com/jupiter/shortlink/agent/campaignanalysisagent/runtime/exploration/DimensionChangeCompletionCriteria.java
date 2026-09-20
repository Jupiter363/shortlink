package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactAuthorizer;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DimensionChangeResultReader;
import java.util.List;
import java.util.Objects;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.CompletionCriterionRegistry.*;

/** Observed distribution coverage only; source quality and causal claims need their own criteria. */
public final class DimensionChangeCompletionCriteria {
    private DimensionChangeCompletionCriteria() {}
    public static Registration coverage(String policyRef, String policyVersion, String criterionRef,
                                        DimensionChangeResultReader reader, ArtifactAuthorizer authorizer) {
        Objects.requireNonNull(reader); Objects.requireNonNull(authorizer);
        return new Registration(policyRef, policyVersion, criterionRef, "1", CampaignRunStore.sha256(
                "dimension-coverage/v1:" + DimensionChangeResultReader.VERSION + ":actual-final-local:full-page-chain:source-selection-complete"),
                CapabilityCatalog.Parameters.none(), (context, parameters) -> {
            var output = context.outputs().get("dimensionChanges");
            if (output == null) return new Finding(State.NOT_MET, List.of(), List.of("DIMENSION_RESULT_REQUIRED"));
            var verified = reader.read(context.token(), output.metadata(), authorizer, 0);
            List<String> ids = List.of(output.metadata().ref().artifactId());
            if (!context.step().explorationPolicy().scopeRef().equals(verified.sourceScopeRef())
                    || !context.step().explorationPolicy().periodsRef().equals(output.metadata().ref().periodsRef()))
                return new Finding(State.NOT_MET, ids, List.of("DIMENSION_SOURCE_BOUNDARY_CHANGED"));
            if (!verified.manifest().path("selectionComplete").booleanValue()
                    || "INSUFFICIENT_EVIDENCE".equals(verified.manifest().path("evidenceDisposition").asText()))
                return new Finding(State.NOT_MET, ids, List.of("DIMENSION_SELECTION_INCOMPLETE"));
            return new Finding(State.MET, ids, List.of("DIMENSION_OBSERVED_COVERAGE_VERIFIED"));
        });
    }
}
