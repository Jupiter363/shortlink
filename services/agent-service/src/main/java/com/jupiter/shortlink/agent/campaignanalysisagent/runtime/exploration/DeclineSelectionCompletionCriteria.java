package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignDeclineSelectionStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactAuthorizer;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.CompletionCriterionRegistry.*;

/** Concrete registered checks; coverage and collection quality are deliberately separate facts. */
public final class DeclineSelectionCompletionCriteria {
    private static final ObjectMapper JSON = new ObjectMapper();
    private DeclineSelectionCompletionCriteria() {}

    public static Registration coverage(String policyRef, String policyVersion, String criterionRef,
                                        CampaignDeclineSelectionStore selections, ArtifactAuthorizer authorizer) {
        Objects.requireNonNull(selections); Objects.requireNonNull(authorizer);
        return new Registration(policyRef, policyVersion, criterionRef, "1",
                CampaignRunStore.sha256("decline-coverage/v1:actual-sealed-pair:exact-scope-period:selectionComplete"),
                CapabilityCatalog.Parameters.none(), (context, parameters) -> {
            var selected = context.outputs().get("selectedEntities");
            var evidence = context.outputs().get("selectionEvidence");
            if (selected == null || evidence == null) return new Finding(State.NOT_MET, List.of(), List.of("DECLINE_PAIR_REQUIRED"));
            List<String> ids = List.of(selected.metadata().ref().artifactId(), evidence.metadata().ref().artifactId());
            var pair = selections.inspectPair(context.token().definition().caller(), ids.get(0), ids.get(1), authorizer);
            if (!selected.metadata().equals(pair.selectedEntities()) || !evidence.metadata().equals(pair.selectionEvidence())
                    || !pair.definition().scopeRef().equals(context.step().explorationPolicy().scopeRef())
                    || !pair.definition().periodsRef().equals(context.step().explorationPolicy().periodsRef()))
                return new Finding(State.NOT_MET, ids, List.of("DECLINE_PAIR_BOUNDARY_CHANGED"));
            return pair.selectionComplete() ? new Finding(State.MET, ids, List.of("DECLINE_SELECTION_COVERED"))
                    : new Finding(State.NOT_MET, ids, List.of("DECLINE_SELECTION_INCOMPLETE"));
        });
    }

    /** A policy requiring complete source collection cannot accept UNVERIFIED as COMPLETE. */
    public static Registration completeCollectionQuality(String policyRef, String policyVersion, String criterionRef) {
        return new Registration(policyRef, policyVersion, criterionRef, "1",
                CampaignRunStore.sha256("collection-quality/v1:all-output-quality-collectionCompleteness-COMPLETE:no-default"),
                CapabilityCatalog.Parameters.none(), (context, parameters) -> {
            var ids = context.outputs().values().stream().map(value -> value.metadata().ref().artifactId()).distinct().sorted().toList();
            if (ids.isEmpty()) return new Finding(State.NOT_MET, ids, List.of("COLLECTION_EVIDENCE_REQUIRED"));
            boolean unknown = false, incomplete = false;
            for (var output : context.outputs().values()) {
                String quality;
                try { quality = JSON.readTree(output.metadata().qualityJson()).path("collectionCompleteness").asText(""); }
                catch (IOException | RuntimeException invalid) { quality = ""; }
                if ("INCOMPLETE".equals(quality) || "PARTIAL".equals(quality)) incomplete = true;
                else if (!"COMPLETE".equals(quality)) unknown = true;
            }
            if (incomplete) return new Finding(State.NOT_MET, ids, List.of("COLLECTION_COMPLETENESS_NOT_MET"));
            if (unknown) return new Finding(State.UNKNOWN, ids, List.of("COLLECTION_COMPLETENESS_UNKNOWN"));
            return new Finding(State.MET, ids, List.of("COLLECTION_COMPLETENESS_VERIFIED"));
        });
    }
}
