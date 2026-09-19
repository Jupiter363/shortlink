package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignLinkComparability.Result;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DeclineSelectionPage.Definition;
import java.util.List;

/** An authorized read index of genuine LOCAL outputs, not a calculation or task runner. */
public interface CampaignDeclineSelectionStore {
    record Receipt(Definition definition, int committedPages, String chainArtifactId, String chainPayloadHash,
                   boolean sealed, String selectedArtifactId, String evidenceArtifactId) {}
    record PageResult(List<Result> rows, String nextCursor) {
        public PageResult { rows = List.copyOf(rows); }
    }

    Receipt append(RunToken token, String childId, ArtifactAuthorizer authorizer);
    Receipt seal(RunToken token, String collectionId, String finalChildId, ArtifactAuthorizer authorizer);
    PageResult readSelectedPage(Caller caller, String selectedArtifactId, String cursor, int size, ArtifactAuthorizer authorizer);
    PageResult readEvidencePage(Caller caller, String evidenceArtifactId, String cursor, int size, ArtifactAuthorizer authorizer);
}
