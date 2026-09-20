package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactAuthorizer;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactMetadata;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import java.util.Map;

/** Server-owned, versioned data projection; it cannot publish evidence or change message roles. */
public interface ExplorationArtifactProjection {
    /** Includes the implementation/schema version and all limits that affect projected content. */
    String configurationId();

    /**
     * Revalidate the exact immutable Artifact and current access before reading any payload/page.
     * Empty means reference-only for unsupported types. Invalid supported evidence must fail closed.
     */
    Map<String, Object> project(Caller current, ArtifactMetadata expected, ArtifactAuthorizer authorizer);

    static ExplorationArtifactProjection references() { return ReferenceOnly.INSTANCE; }

    enum ReferenceOnly implements ExplorationArtifactProjection {
        INSTANCE;
        @Override public String configurationId() { return "artifact-references/v1"; }
        @Override public Map<String, Object> project(Caller current, ArtifactMetadata expected,
                ArtifactAuthorizer authorizer) { return Map.of(); }
    }
}
