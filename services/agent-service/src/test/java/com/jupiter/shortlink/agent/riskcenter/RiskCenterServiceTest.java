package com.jupiter.shortlink.agent.riskcenter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient.AuthorizedScope;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.riskcenter.repository.*;
import com.jupiter.shortlink.agent.riskcenter.service.RiskCenterService;
import com.jupiter.shortlink.agent.riskpolicy.service.RiskPolicyService;
import com.jupiter.shortlink.agent.riskprofile.repository.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class RiskCenterServiceTest {
    @Test
    void groupWithoutEvaluatedProfileIsUnavailableAfterAuthorization() {
        var authority = mock(AgentAuthorityClient.class);
        var groups = mock(JdbcGroupRiskProfileRepository.class);
        var scope = new AuthorizedScope("1001", "1", List.of());
        var principal = new AgentPrincipal("1001", "trusted-user", 1, false);
        when(authority.resolve(principal, "g1", null, null)).thenReturn(scope);
        when(groups.findAuthorized(scope, "g1", null)).thenReturn(Optional.empty());
        var service =
                new RiskCenterService(
                        mock(JdbcRiskEventRepository.class),
                        mock(JdbcRiskSnapshotRepository.class),
                        mock(JdbcRiskReviewRepository.class),
                        mock(JdbcShortLinkRiskProfileRepository.class),
                        groups,
                        mock(RiskPolicyService.class),
                        authority);
        assertThatThrownBy(() -> service.getGroupOverview(principal, "g1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Group risk profile is not available");
        var order = inOrder(authority, groups);
        order.verify(authority).resolve(principal, "g1", null, null);
        order.verify(groups).findAuthorized(scope, "g1", null);
    }

    @Test
    void revokedScopeFailsBeforeAnyStoredEvidenceRead() {
        var authority = mock(AgentAuthorityClient.class);
        var profiles = mock(JdbcShortLinkRiskProfileRepository.class);
        var events = mock(JdbcRiskEventRepository.class);
        var snapshots = mock(JdbcRiskSnapshotRepository.class);
        var reviews = mock(JdbcRiskReviewRepository.class);
        var groups = mock(JdbcGroupRiskProfileRepository.class);
        when(authority.resolve(any(), any(), any(), any()))
                .thenThrow(new SecurityException("revoked"));
        var service =
                new RiskCenterService(
                        events,
                        snapshots,
                        reviews,
                        profiles,
                        groups,
                        mock(RiskPolicyService.class),
                        authority);
        assertThatThrownBy(
                        () ->
                                service.listGroupShortLinkCards(
                                        new AgentPrincipal("1001", "trusted-user", 1, false), "g1"))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(events, snapshots, reviews, profiles, groups);
    }
}
