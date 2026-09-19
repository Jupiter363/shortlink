package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignScope.AuthorityPage;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class FrozenCampaignScopeTest {
    private static final AgentPrincipal OWNER = new AgentPrincipal("42", "analyst-a", 7, false);
    private static final String GID = "campaign-group";
    private static final String VERSION = "a".repeat(64);
    private static final List<Long> FIRST = LongStream.rangeClosed(1, 500).boxed().toList();

    @Test
    void complete501MemberEnumerationProducesStableDeeplyImmutable500PlusOneShards() {
        List<Long> firstMembers = new ArrayList<>(FIRST);
        List<Long> lastMembers = new ArrayList<>(List.of(501L));
        AuthorityPage first = page(null, VERSION, firstMembers, 500L);
        AuthorityPage last = page(500L, VERSION, lastMembers, null);
        List<AuthorityPage> pages = new ArrayList<>(List.of(first, last));
        FrozenCampaignScope frozen = FrozenCampaignScope.freeze(OWNER, GID, pages);
        FrozenCampaignScope repeated = FrozenCampaignScope.freeze(OWNER, GID, List.of(first, last));

        firstMembers.clear();
        lastMembers.set(0, 999L);
        pages.clear();
        List<Long> expected = LongStream.rangeClosed(1, 501).boxed().toList();
        assertEquals(OWNER, frozen.owner());
        assertEquals(GID, frozen.gid());
        assertEquals(VERSION, frozen.enumerationVersion());
        assertFalse(frozen.empty());
        assertEquals(expected, frozen.linkIds());
        assertEquals(FrozenQueryScope.memberHash(expected), frozen.memberHash());
        assertEquals(repeated.scopeRef(), frozen.scopeRef());
        assertEquals(repeated.shards(), frozen.shards());
        assertEquals(List.of(500, 1), frozen.shards().stream().map(shard -> shard.linkIds().size()).toList());
        assertEquals(expected, frozen.shards().stream().flatMap(shard -> shard.linkIds().stream()).toList());
        for (int index = 0; index < frozen.shards().size(); index++) {
            FrozenQueryScope shard = frozen.shards().get(index);
            assertEquals(frozen.scopeRef(), shard.parentScopeRef());
            assertEquals(frozen.memberHash(), shard.parentMemberHash());
            assertEquals(501, shard.parentMemberCount());
            assertEquals(VERSION, shard.enumerationVersion());
            assertEquals(index, shard.shardIndex());
            assertEquals(2, shard.shardCount());
            assertEquals(shard, FrozenQueryScope.fromMap(shard.asMap()));
        }
        assertThrows(UnsupportedOperationException.class, () -> first.linkIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> frozen.linkIds().set(0, 999L));
        assertThrows(UnsupportedOperationException.class, () -> frozen.shards().clear());
        assertThrows(UnsupportedOperationException.class, () -> frozen.shards().get(0).linkIds().clear());

        AgentPrincipal renewed = new AgentPrincipal(OWNER.tenantId(), OWNER.username(), 8, false);
        FrozenCampaignScope newAuthority = FrozenCampaignScope.freeze(renewed, GID, List.of(
                new AuthorityPage(renewed, GID, null, VERSION, FIRST, 500L),
                new AuthorityPage(renewed, GID, 500L, VERSION, List.of(501L), null)));
        assertEquals(frozen.memberHash(), newAuthority.memberHash());
        assertNotEquals(frozen.scopeRef(), newAuthority.scopeRef(), "Authority version is part of the frozen identity");
    }

    @Test
    void rejectsChangedOrIncompleteAuthorityPagesAndKeepsAuthorizedEmptyScopeQueryFree() {
        AuthorityPage first = page(null, VERSION, FIRST, 500L);
        AuthorityPage last = page(500L, VERSION, List.of(501L), null);
        AgentPrincipal other = new AgentPrincipal("43", "analyst-a", 7, false);
        List<List<AuthorityPage>> invalid = List.of(
                List.of(),
                List.of(first), // A nonterminal prefix cannot prove complete membership.
                List.of(page(1L, VERSION, List.of(2L), null)),
                List.of(first, page(499L, VERSION, List.of(501L), null)),
                List.of(page(null, VERSION, FIRST, 499L), last),
                List.of(page(null, VERSION, FIRST.subList(0, 499), 499L),
                        page(499L, VERSION, List.of(500L), null)),
                List.of(first, page(500L, "b".repeat(64), List.of(501L), null)),
                List.of(page(null, "not-an-ownership-version", List.of(1L), null)),
                List.of(first, new AuthorityPage(other, GID, 500L, VERSION, List.of(501L), null)),
                List.of(first, new AuthorityPage(OWNER, "other-group", 500L, VERSION, List.of(501L), null)),
                List.of(page(null, VERSION, List.of(2L, 1L), null)),
                List.of(page(null, VERSION, List.of(1L, 1L), null)),
                List.of(page(null, VERSION, List.of(0L), null)),
                List.of(first, page(500L, VERSION, List.of(500L), null)),
                List.of(first, page(500L, VERSION, List.of(), null)),
                List.of(page(null, VERSION, List.of(1L), null), page(null, VERSION, List.of(2L), null)),
                List.of(page(null, VERSION, LongStream.rangeClosed(1, 501).boxed().toList(), null)));
        for (List<AuthorityPage> pages : invalid) {
            assertThrows(IllegalArgumentException.class, () -> FrozenCampaignScope.freeze(OWNER, GID, pages));
        }
        assertThrows(IllegalArgumentException.class,
                () -> FrozenCampaignScope.freeze(AgentPrincipal.system("internal"), GID, List.of(first, last)));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenCampaignScope.freeze(OWNER, "invalid/gid", List.of(first, last)));

        FrozenCampaignScope empty = FrozenCampaignScope.freeze(OWNER, GID,
                List.of(page(null, VERSION, List.of(), null)));
        assertTrue(empty.empty());
        assertTrue(empty.linkIds().isEmpty());
        assertTrue(empty.shards().isEmpty(), "An authorized empty parent must never submit an empty statistics query");
        assertEquals(FrozenQueryScope.memberHash(List.of()), empty.memberHash());
        assertEquals(empty.scopeRef(), FrozenCampaignScope.freeze(OWNER, GID,
                List.of(page(null, VERSION, List.of(), null))).scopeRef());
    }

    private static AuthorityPage page(Long after, String version, List<Long> members, Long next) {
        return new AuthorityPage(OWNER, GID, after, version, members, next);
    }
}
