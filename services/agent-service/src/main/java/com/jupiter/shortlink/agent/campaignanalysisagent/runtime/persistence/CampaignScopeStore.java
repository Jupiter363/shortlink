package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignScope;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.time.Instant;

/** Durable current-membership enumeration; every authority page belongs to a real SYNC child. */
public interface CampaignScopeStore {
    enum State { COLLECTING, PUBLISHED, INVALID }
    record Definition(String collectionId, ActionSpec action, String gid, Instant expiresAt) {}
    record Collection(Definition definition, State state, String enumerationVersion, Long nextCursor,
                      int pageCount, long memberCount, String artifactId, String failureCode) {}

    Collection prepare(RunToken token, Definition definition);
    Collection load(RunToken token, String collectionId);

    /** Verify the exact durable prefix and its readable READY artifacts; does not authorize retrying another child. */
    Collection verifyContinuation(RunToken token, Definition expected, ArtifactAuthorizer authorizer);

    /** Changed authority versions durably invalidate the collection and return INVALID, rather than rolling it back. */
    Collection commitPage(DispatchPermit permit, String collectionId, GroupMembersPage page);
    Collection invalidate(RunToken token, String collectionId, String code);

    /**
     * Verify a published scope and all original page receipts, including an authorized empty scope.
     * Current access to the final ScopeArtifact covers only its fully verified bounded source pages.
     */
    FrozenCampaignScope.Summary inspectPublished(Caller current, String artifactId, ArtifactAuthorizer authorizer);

    /** Reads one 500-member shard only after validating the full immutable collection and current authorization. */
    FrozenQueryScope shard(Caller current, String artifactId, int shardIndex, ArtifactAuthorizer authorizer);
}
