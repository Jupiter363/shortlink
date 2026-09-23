package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient.AuthorizedScope;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CampaignDependencyAnalysisQueryAuthorizerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), ZoneOffset.UTC);
    private static final AgentPrincipal OWNER = new AgentPrincipal("1001", "alice", 7, false);
    private static final String GID = "group-a";
    private static final String VERSION = "a".repeat(64);
    private static final List<Map<String, Object>> FILTERS = List.of(
            Map.of("dimension", "province", "operator", "IN", "values", List.of("广东")));

    @Test
    void authorizesOriginalBaselineAndSelectedDimensionShardAgainstExactLiveIds() throws Exception {
        var f = new Fixture();
        assertThat(f.gate.mayUse(OWNER, f.original.parentScopeRef(), "baseline", f.links())).isTrue();
        assertThat(f.gate.mayUse(OWNER, f.selected.parentScopeRef(), "target", f.dimensions())).isTrue();
        verify(f.authority).resolvePage(OWNER, GID, null, List.of(1L, 2L), null, VERSION);
        verify(f.authority).resolvePage(OWNER, GID, null, List.of(2L), null, VERSION);
    }

    @Test
    void changedPeriodFilterScopeMembersOrOpenRequestShapeFailBeforeAuthorityIo() throws Exception {
        var f = new Fixture();
        assertThat(f.gate.mayUse(OWNER, f.original.parentScopeRef(), "target", f.links())).isFalse();
        assertThat(f.gate.mayUse(OWNER, f.original.parentScopeRef(), "period-pair.v1:forged", f.links())).isFalse();
        assertThat(f.gate.mayUse(OWNER, "scope-" + "b".repeat(64), "baseline", f.links())).isFalse();
        Map<String, Object> date = f.links();
        date.put("endDate", "2026-09-03");
        assertThat(f.gate.mayUse(OWNER, f.original.parentScopeRef(), "baseline", date)).isFalse();
        Map<String, Object> dimension = f.dimensions();
        dimension.put("filters", List.of());
        assertThat(f.gate.mayUse(OWNER, f.selected.parentScopeRef(), "target", dimension)).isFalse();
        Map<String, Object> widened = f.dimensions();
        widened.put("dimensions", List.of("province", "device", "browser"));
        assertThat(f.gate.mayUse(OWNER, f.selected.parentScopeRef(), "target", widened)).isFalse();
        Map<String, Object> extra = f.links();
        extra.put("fullShortUrl", "example.test/foreign");
        assertThat(f.gate.mayUse(OWNER, f.original.parentScopeRef(), "baseline", extra)).isFalse();
        Map<String, Object> alteredScope = new LinkedHashMap<>(f.original.asMap());
        alteredScope.put("linkIds", List.of(1L, 3L));
        Map<String, Object> alteredIds = f.links();
        alteredIds.put("scope", alteredScope);
        assertThat(f.gate.mayUse(OWNER, f.original.parentScopeRef(), "baseline", alteredIds)).isFalse();
        verifyNoInteractions(f.authority);
    }

    @Test
    void foreignPrincipalRevocationAndChangedCurrentMembershipFailClosed() throws Exception {
        var f = new Fixture();
        assertThat(f.gate.mayUse(new AgentPrincipal("1001", "alice", 8, false),
                f.original.parentScopeRef(), "baseline", f.links())).isFalse();
        assertThat(f.gate.mayUse(new AgentPrincipal("1002", "bob", 7, false),
                f.original.parentScopeRef(), "baseline", f.links())).isFalse();
        verifyNoInteractions(f.authority);
        for (String failure : List.of("revoked", "version", "tenant", "group", "ids", "fractional", "cursor", "unavailable")) {
            f.failure.set(failure);
            assertThat(f.gate.mayUse(OWNER, f.original.parentScopeRef(), "baseline", f.links()))
                    .as(failure).isFalse();
        }
    }

    private static final class Fixture {
        final AgentAuthorityClient authority = mock(AgentAuthorityClient.class);
        final AtomicReference<String> failure = new AtomicReference<>("");
        final FrozenQueryScope original;
        final FrozenQueryScope selected;
        final CampaignDependencyAnalysisQueryAuthorizer gate;

        Fixture() throws Exception {
            var plans = new CampaignDependencyAnalysisPlanFactory(
                    new ClassPathResource("campaign-skills").getFile().toPath(), CLOCK);
            var prepared = plans.prepare(new Caller(OWNER.tenantId(), OWNER.username(), OWNER.authVersion()),
                    "dependency-session", "query-invocation", new CampaignDependencyAnalysisPlanFactory.Request(
                            GID, "2026-09-01", "2026-09-01", "2026-09-02", "2026-09-02",
                            "PV", List.of("province", "device"), FILTERS), CLOCK.instant().plusSeconds(3600));
            gate = new CampaignDependencyAnalysisQueryAuthorizer(authority, plans, prepared.definition());
            original = FrozenCampaignScope.freeze(OWNER, GID, List.of(new FrozenCampaignScope.AuthorityPage(
                    OWNER, GID, null, VERSION, List.of(1L, 2L), null))).shards().get(0);
            String selectedRef = "selected-scope-" + "c".repeat(64);
            String memberHash = FrozenQueryScope.memberHash(List.of(2L));
            selected = new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET", selectedRef, memberHash,
                    1, VERSION, FrozenQueryScope.shardIdFor(selectedRef, 0, memberHash), 0, 1, memberHash, List.of(2L));
            when(authority.verifyCurrentPrincipal(any(AgentPrincipal.class))).thenAnswer(call -> {
                if (failure.get().equals("revoked")) throw new SecurityException("revoked");
                return call.getArgument(0);
            });
            when(authority.resolveGroupMembersPage(any(AgentPrincipal.class), anyString(), isNull(), isNull()))
                    .thenReturn(new GroupMembersPage(GroupMembersPage.SCHEMA, OWNER.tenantId(), OWNER.username(),
                            OWNER.authVersion(), GID, VERSION, null, List.of(1L, 2L), null));
            when(authority.resolvePage(any(AgentPrincipal.class), anyString(), isNull(), anyList(), isNull(), anyString()))
                    .thenAnswer(call -> {
                        if (failure.get().equals("unavailable")) throw new IllegalStateException("unavailable");
                        List<Long> ids = call.getArgument(3);
                        List<Map<String, Object>> links = new ArrayList<>();
                        for (long id : ids) {
                            Object resolvedId = failure.get().equals("fractional") ? Double.valueOf(id + 0.5)
                                    : (Object) Long.valueOf(failure.get().equals("ids") ? id + 10 : id);
                            links.add(Map.of("gid", failure.get().equals("group") ? "foreign-group" : GID,
                                    "linkId", resolvedId));
                        }
                        return new AuthorizedScope(failure.get().equals("tenant") ? "1002" : OWNER.tenantId(),
                                failure.get().equals("version") ? "b".repeat(64) : VERSION,
                                links, failure.get().equals("cursor") ? 2L : null);
                    });
        }

        Map<String, Object> links() {
            return query(original, "LINK_METRICS", "decline_stat_", "2026-09-01");
        }

        Map<String, Object> dimensions() {
            Map<String, Object> result = query(selected, "DIMENSION_BREAKDOWN", "dimension_stat_", "2026-09-02");
            result.put("dimensions", List.of("province", "device"));
            result.put("filters", FILTERS);
            return result;
        }

        private Map<String, Object> query(FrozenQueryScope scope, String kind, String prefix, String date) {
            return new LinkedHashMap<>(Map.of("requestId", prefix + "d".repeat(64), "gid", GID,
                    "queryKind", kind, "startDate", date, "endDate", date, "scope", scope.asMap()));
        }
    }
}
