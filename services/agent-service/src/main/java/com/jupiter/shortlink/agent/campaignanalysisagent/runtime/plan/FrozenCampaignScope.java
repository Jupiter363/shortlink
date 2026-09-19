package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * A complete, immutable membership enumeration supplied by a trusted authority collector.
 * This pure freezer performs no HTTP or ACL lookup: matching page identities are not proof that
 * model-supplied members are authorized. Callers must obtain every page from the authority under
 * the current principal, and must still reauthorize each shard before querying. Both comparison
 * periods reuse this same frozen scope; an authorized empty parent never creates a query shard.
 */
public final class FrozenCampaignScope {
    /** Trusted authority response, not a model input or a replacement for an ownership check. */
    public record AuthorityPage(AgentPrincipal owner, String gid, Long afterLinkId,
                                String ownershipVersion, List<Long> linkIds, Long nextCursor) {
        public AuthorityPage {
            require(linkIds != null);
            linkIds = Collections.unmodifiableList(new ArrayList<>(linkIds));
        }
    }

    private final AgentPrincipal owner;
    private final String gid;
    private final String scopeRef;
    private final String memberHash;
    private final String enumerationVersion;
    private final List<Long> linkIds;
    private final List<FrozenQueryScope> shards;

    private FrozenCampaignScope(AgentPrincipal owner, String gid, String enumerationVersion, List<Long> members) {
        this.owner = owner;
        this.gid = gid;
        this.enumerationVersion = enumerationVersion;
        this.linkIds = List.copyOf(members);
        this.memberHash = FrozenQueryScope.memberHash(linkIds);
        this.scopeRef = scopeReference(owner, gid, enumerationVersion, memberHash);
        int shardCount = linkIds.size() / FrozenQueryScope.SHARD_SIZE
                + (linkIds.size() % FrozenQueryScope.SHARD_SIZE == 0 ? 0 : 1);
        List<FrozenQueryScope> frozenShards = new ArrayList<>(shardCount);
        for (int index = 0; index < shardCount; index++) {
            int start = index * FrozenQueryScope.SHARD_SIZE;
            int length = Math.min(FrozenQueryScope.SHARD_SIZE, linkIds.size() - start);
            List<Long> selected = linkIds.subList(start, start + length);
            String selectedHash = FrozenQueryScope.memberHash(selected);
            frozenShards.add(new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET", scopeRef,
                    memberHash, linkIds.size(), enumerationVersion,
                    FrozenQueryScope.shardIdFor(scopeRef, index, selectedHash), index, shardCount,
                    selectedHash, selected));
        }
        this.shards = List.copyOf(frozenShards);
    }

    /** Reject an incomplete or changed enumeration instead of treating the received prefix as ALL. */
    public static FrozenCampaignScope freeze(AgentPrincipal owner, String gid, List<AuthorityPage> pages) {
        require(owner != null && !owner.system());
        require(gid != null && gid.matches("[A-Za-z0-9_-]{1,64}"));
        require(pages != null && !pages.isEmpty());
        List<Long> members = new ArrayList<>();
        String version = null;
        Long expectedAfter = null;
        long previous = 0;
        for (int index = 0; index < pages.size(); index++) {
            AuthorityPage page = pages.get(index);
            require(page != null && owner.equals(page.owner()) && gid.equals(page.gid()));
            require(page.ownershipVersion() != null && page.ownershipVersion().matches("[a-f0-9]{64}"));
            if (version == null) version = page.ownershipVersion();
            require(version.equals(page.ownershipVersion()) && Objects.equals(expectedAfter, page.afterLinkId()));
            require(page.linkIds().size() <= FrozenQueryScope.SHARD_SIZE);
            // Only the first, terminal authority page can represent an empty parent.
            require(!page.linkIds().isEmpty() || (index == 0 && page.nextCursor() == null));
            for (Long member : page.linkIds()) {
                require(member != null && member > previous);
                previous = member;
                members.add(member);
            }
            if (page.nextCursor() == null) {
                require(index == pages.size() - 1);
            } else {
                require(page.linkIds().size() == FrozenQueryScope.SHARD_SIZE
                        && page.nextCursor().longValue() == previous && index < pages.size() - 1);
            }
            expectedAfter = page.nextCursor();
        }
        return new FrozenCampaignScope(owner, gid, version, members);
    }

    public AgentPrincipal owner() { return owner; }
    public String gid() { return gid; }
    public String scopeRef() { return scopeRef; }
    public String memberHash() { return memberHash; }
    public String enumerationVersion() { return enumerationVersion; }
    public List<Long> linkIds() { return linkIds; }
    public List<FrozenQueryScope> shards() { return shards; }
    public boolean empty() { return linkIds.isEmpty(); }

    private static String scopeReference(AgentPrincipal owner, String gid, String version, String members) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("campaign-frozen-scope/v1\n".getBytes(StandardCharsets.UTF_8));
            for (String value : List.of(owner.tenantId(), owner.username(), Long.toString(owner.authVersion()),
                    gid, version, members)) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
                digest.update(bytes);
            }
            return "scope-" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("CAMPAIGN_SCOPE_INVALID");
    }
}
