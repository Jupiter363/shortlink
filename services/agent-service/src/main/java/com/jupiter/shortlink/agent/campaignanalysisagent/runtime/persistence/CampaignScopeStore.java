package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
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

    /** Changed authority versions durably invalidate the collection and return INVALID, rather than rolling it back. */
    Collection commitPage(DispatchPermit permit, String collectionId, GroupMembersPage page);
    Collection invalidate(RunToken token, String collectionId, String code);

    /** Reads one 500-member shard only after validating the full immutable collection and current authorization. */
    FrozenQueryScope shard(Caller current, String artifactId, int shardIndex, ArtifactAuthorizer authorizer);
}
