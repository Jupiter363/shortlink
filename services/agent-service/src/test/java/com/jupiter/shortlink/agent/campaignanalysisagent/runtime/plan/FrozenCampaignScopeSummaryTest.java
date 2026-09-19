package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.junit.jupiter.api.Assertions.*;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class FrozenCampaignScopeSummaryTest {
    private static final AgentPrincipal OWNER = new AgentPrincipal("1001", "analyst", 7, false);
    private static final String VERSION = "a".repeat(64);

    @Test
    void streamedPagesKeepCanonicalScopeIdentityWithoutMaterializingTheFullSetAndRejectPrefixes() {
        AtomicInteger reads = new AtomicInteger();
        var summary = FrozenCampaignScope.summarize(OWNER, "g1", generated(5501, reads));
        assertEquals(12, reads.get());
        assertEquals(5501, summary.memberCount());
        assertEquals(12, summary.pageCount());
        assertEquals(12, summary.shardCount());
        assertEquals(FrozenQueryScope.memberHash(LongStream.rangeClosed(1, 5501).boxed().toList()), summary.memberHash());
        var first = new FrozenCampaignScope.AuthorityPage(OWNER, "g1", null, VERSION,
                LongStream.rangeClosed(1, 500).boxed().toList(), 500L);
        var last = new FrozenCampaignScope.AuthorityPage(OWNER, "g1", 500L, VERSION, List.of(501L), null);
        var original = FrozenCampaignScope.freeze(OWNER, "g1", List.of(first, last));
        var reduced = FrozenCampaignScope.summarize(OWNER, "g1", List.of(first, last));
        assertEquals(original.scopeRef(), reduced.scopeRef());
        assertEquals(original.memberHash(), reduced.memberHash());
        assertEquals(original.enumerationVersion(), reduced.enumerationVersion());
        assertThrows(IllegalArgumentException.class, () -> FrozenCampaignScope.summarize(OWNER, "g1", List.of(first)));
        assertThrows(IllegalArgumentException.class, () -> FrozenCampaignScope.summarize(OWNER, "g1", List.of(first,
                new FrozenCampaignScope.AuthorityPage(OWNER, "g1", 500L, "b".repeat(64), List.of(501L), null))));
        var empty = FrozenCampaignScope.summarize(OWNER, "g1", List.of(
                new FrozenCampaignScope.AuthorityPage(OWNER, "g1", null, VERSION, List.of(), null)));
        assertEquals(0, empty.memberCount()); assertEquals(0, empty.shardCount()); assertEquals(1, empty.pageCount());
        assertEquals(FrozenQueryScope.memberHash(List.of()), empty.memberHash());
    }

    private static Iterable<FrozenCampaignScope.AuthorityPage> generated(int total, AtomicInteger reads) {
        return () -> new Iterator<>() {
            long cursor;
            public boolean hasNext() { return cursor < total; }
            public FrozenCampaignScope.AuthorityPage next() {
                long after = cursor;
                cursor = Math.min(total, cursor + 500);
                reads.incrementAndGet();
                return new FrozenCampaignScope.AuthorityPage(OWNER, "g1", after == 0 ? null : after, VERSION,
                        LongStream.rangeClosed(after + 1, cursor).boxed().toList(), cursor == total ? null : cursor);
            }
        };
    }
}
