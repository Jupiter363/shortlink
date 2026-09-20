package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore.CallPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignLinkComparability.Result;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverage.Period;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DeclineSelectionPage.Definition;
import java.util.List;
import java.util.Optional;

/** An authorized read index of genuine LOCAL outputs, not a calculation or task runner. */
public interface CampaignDeclineSelectionStore {
    record Receipt(Definition definition, int committedPages, String chainArtifactId, String chainPayloadHash,
                   boolean sealed, String selectedArtifactId, String evidenceArtifactId) {}
    record PageResult(List<Result> rows, String nextCursor) {
        public PageResult { rows = List.copyOf(rows); }
    }
    /** Actual paired publications, producer step and source periods, verified from the durable collection. */
    record SelectionPair(ArtifactMetadata selectedEntities, ArtifactMetadata selectionEvidence,
                         Definition definition, ArtifactMetadata scopeArtifact, List<Period> periods,
                         boolean selectionComplete, String emptyReason, long selectedCount, String producerStepId) {
        public SelectionPair { periods = List.copyOf(periods); }
    }

    Receipt append(RunToken token, String childId, ArtifactAuthorizer authorizer);
    /** Live exact parent admission; a token alone cannot publish a CALL-owned child. */
    Receipt append(CallPermit permit, String childId, ArtifactAuthorizer authorizer);
    /** Current-token read of the verified committed prefix; never requires unfinished shards. */
    Optional<Receipt> loadReceipt(RunToken token, String collectionId, ArtifactAuthorizer authorizer);
    Receipt seal(RunToken token, String collectionId, String finalChildId, ArtifactAuthorizer authorizer);
    Receipt seal(CallPermit permit, String collectionId, String finalChildId, ArtifactAuthorizer authorizer);
    SelectionPair inspectPair(Caller caller, String selectedId, String evidenceId, ArtifactAuthorizer authorizer);
    PageResult readSelectedPage(Caller caller, String selectedArtifactId, String cursor, int size, ArtifactAuthorizer authorizer);
    /** Exact selected membership in ascending link order; its cursor cannot be used by another ordering. */
    PageResult readSelectedByLinkId(Caller caller, String selectedArtifactId, String cursor, int size, ArtifactAuthorizer authorizer);
    PageResult readEvidencePage(Caller caller, String evidenceArtifactId, String cursor, int size, ArtifactAuthorizer authorizer);
}
