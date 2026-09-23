package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient.AuthorizedScope;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CampaignStatisticsQueryAuthorizerTest {
    private static final AgentPrincipal CURRENT = new AgentPrincipal("1001", "alice", 7, false);
    private static final String SCOPE = "current-group.v1:alpha";
    private static final String PERIOD = "period.v1:2026-09-01:2026-09-07";
    private final AgentAuthorityClient authority = mock(AgentAuthorityClient.class);
    private final CampaignStatisticsQueryAuthorizer gate = new CampaignStatisticsQueryAuthorizer(authority);

    @Test
    void currentGroupRequiresLiveAccountAndOwnedGroupPage() {
        when(authority.verifyCurrentPrincipal(CURRENT)).thenReturn(CURRENT);
        when(authority.resolveGroupMembersPage(CURRENT, "alpha", null, null))
                .thenReturn(new GroupMembersPage(GroupMembersPage.SCHEMA, "1001", "alice", 7,
                        "alpha", "a".repeat(64), null, List.of(), null));

        assertThat(gate.mayUse(CURRENT, SCOPE, PERIOD, request())).isTrue();
        assertThat(gate.mayUse(CURRENT, "current-group.v1:beta", PERIOD, request())).isFalse();
    }

    @Test
    void exactShortUrlRequiresOneCurrentOwnedLink() {
        when(authority.verifyCurrentPrincipal(CURRENT)).thenReturn(CURRENT);
        String url = "sho.rt/123456789";
        when(authority.resolvePage(CURRENT, "alpha", url, null, null, null))
                .thenReturn(new AuthorizedScope("1001", "version",
                        List.of(Map.of("linkId", 1L, "gid", "alpha", "fullShortUrl", "https://" + url)), null));
        Map<String, Object> query = new LinkedHashMap<>(request());
        query.put("fullShortUrl", url);
        assertThat(gate.mayUse(CURRENT, SCOPE, PERIOD, query)).isTrue();

        when(authority.resolvePage(CURRENT, "alpha", url, null, null, null))
                .thenReturn(new AuthorizedScope("1001", "version", List.of(), null));
        assertThat(gate.mayUse(CURRENT, SCOPE, PERIOD, query)).isFalse();
    }

    @Test
    void malformedOrDriftedFrozenRequestFailsBeforeAuthorityIo() {
        Map<String, Object> query = new LinkedHashMap<>(request());
        query.put("endDate", "2026-09-08");
        assertThat(gate.mayUse(CURRENT, SCOPE, PERIOD, query)).isFalse();
        assertThat(gate.mayUse(CURRENT, "current-group/v1:alpha", PERIOD, request())).isFalse();
        assertThat(gate.mayUse(CURRENT, SCOPE, "period.v1:2026-09-01:2026-09-08", request())).isFalse();
        query = new LinkedHashMap<>(request());
        query.put("scope", Map.of());
        assertThat(gate.mayUse(CURRENT, SCOPE, PERIOD, query)).isFalse();
        query = new LinkedHashMap<>(request());
        query.put("fullShortUrl", null);
        assertThat(gate.mayUse(CURRENT, SCOPE, PERIOD, query)).isFalse();
        verifyNoInteractions(authority);
    }

    @Test
    void revokedCurrentAccountFailsClosed() {
        when(authority.verifyCurrentPrincipal(CURRENT)).thenThrow(new SecurityException("revoked"));
        assertThat(gate.mayUse(CURRENT, SCOPE, PERIOD, request())).isFalse();
    }

    private static Map<String, Object> request() {
        return Map.of("requestId", "stat_" + "a".repeat(64), "gid", "alpha", "queryKind", "METRICS",
                "startDate", "2026-09-01", "endDate", "2026-09-07");
    }
}
