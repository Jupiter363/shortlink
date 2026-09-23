package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Cardinality;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.TypeRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CampaignStatisticsCurrentInputAuthorizerTest {
    private static final Caller OWNER = new Caller("1001", "alice", 7);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "alice", 7, false);

    @Test
    void currentOwnedGroupAndValidPeriodPassThroughLiveAuthority() {
        AgentAuthorityClient authority = mock(AgentAuthorityClient.class);
        when(authority.verifyCurrentPrincipal(PRINCIPAL)).thenReturn(PRINCIPAL);
        when(authority.resolveGroupMembersPage(PRINCIPAL, "alpha", null, null)).thenReturn(
                new GroupMembersPage(GroupMembersPage.SCHEMA, "1001", "alice", 7, "alpha",
                        "a".repeat(64), null, List.of(1L), null));
        var authorizer = new CampaignStatisticsCurrentInputAuthorizer(authority);
        assertThat(authorizer.mayUse(OWNER, FrozenStatisticsJobQuery.SCOPE_TYPE,
                "current-group.v1:alpha")).isTrue();
        assertThat(authorizer.mayUse(OWNER, FrozenStatisticsJobQuery.PERIODS_TYPE,
                "period.v1:2026-09-01:2026-09-14")).isTrue();
        verify(authority).resolveGroupMembersPage(PRINCIPAL, "alpha", null, null);
        assertThat(CampaignStatisticsCurrentInputAuthorizer.parseGroupId("current-group.v1:alpha"))
                .isEqualTo("alpha");
        assertThat(CampaignStatisticsCurrentInputAuthorizer.parsePeriod("period.v1:2026-09-01:2026-09-14"))
                .isEqualTo(new CampaignStatisticsCurrentInputAuthorizer.DateRange(
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 14)));
    }

    @Test
    void malformedReferencesAndWrongTypesNeverReachAuthority() {
        AgentAuthorityClient authority = mock(AgentAuthorityClient.class);
        var authorizer = new CampaignStatisticsCurrentInputAuthorizer(authority);
        for (String ref : List.of("current-group.v1:", "current-group.v1:alpha:other",
                "current-group/v2:alpha", "current-group.v1:alpha/other"))
            assertThat(authorizer.mayUse(OWNER, FrozenStatisticsJobQuery.SCOPE_TYPE, ref)).isFalse();
        for (String ref : List.of("period.v1:2026-02-30:2026-03-01",
                "period.v1:2026-09-14:2026-09-01", "period.v1:2026-01-01:2026-07-01",
                "period.v1:1969-12-31:1970-01-01", "period/v2:2026-09-01:2026-09-02"))
            assertThat(authorizer.mayUse(OWNER, FrozenStatisticsJobQuery.PERIODS_TYPE, ref)).isFalse();
        assertThat(authorizer.mayUse(OWNER, new TypeRef("Other", 1, Cardinality.ONE),
                "current-group.v1:alpha")).isFalse();
        assertThat(authorizer.mayUse(OWNER, FrozenStatisticsJobQuery.SCOPE_TYPE, 1L)).isFalse();
        assertThat(authorizer.mayUse(new Caller("1001", "alice", 0), FrozenStatisticsJobQuery.PERIODS_TYPE,
                "period.v1:2026-09-01:2026-09-02")).isFalse();
        assertThatThrownBy(() -> CampaignStatisticsCurrentInputAuthorizer.parsePeriod(
                "period.v1:2026-02-30:2026-03-01")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(authority);
    }

    @Test
    void changedPrincipalAndMismatchedTenantPageFailClosed() {
        AgentAuthorityClient authority = mock(AgentAuthorityClient.class);
        var authorizer = new CampaignStatisticsCurrentInputAuthorizer(authority);
        when(authority.verifyCurrentPrincipal(PRINCIPAL)).thenThrow(new SecurityException("retired"));
        assertThat(authorizer.mayUse(OWNER, FrozenStatisticsJobQuery.SCOPE_TYPE,
                "current-group.v1:alpha")).isFalse();
        assertThat(authorizer.mayUse(OWNER, FrozenStatisticsJobQuery.PERIODS_TYPE,
                "period.v1:2026-09-01:2026-09-02")).isFalse();

        doReturn(new AgentPrincipal("1001", "alice", 8, false))
                .when(authority).verifyCurrentPrincipal(PRINCIPAL);
        assertThat(authorizer.mayUse(OWNER, FrozenStatisticsJobQuery.SCOPE_TYPE,
                "current-group.v1:alpha")).isFalse();

        doReturn(PRINCIPAL).when(authority).verifyCurrentPrincipal(PRINCIPAL);
        when(authority.resolveGroupMembersPage(PRINCIPAL, "alpha", null, null)).thenReturn(
                new GroupMembersPage(GroupMembersPage.SCHEMA, "1002", "alice", 7, "alpha",
                        "a".repeat(64), null, List.of(1L), null));
        assertThat(authorizer.mayUse(OWNER, FrozenStatisticsJobQuery.SCOPE_TYPE,
                "current-group.v1:alpha")).isFalse();
    }
}
