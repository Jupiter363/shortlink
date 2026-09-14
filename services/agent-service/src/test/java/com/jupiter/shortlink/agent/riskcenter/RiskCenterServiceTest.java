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
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.*;

class RiskCenterServiceTest {
    @Test
    void groupWithoutEvaluatedProfileIsExplicitlyUnknownAfterAuthorization() {
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
        var overview = service.getGroupOverview(principal, "g1");
        assertThat(overview.gid()).isEqualTo("g1");
        assertThat(overview.profileStatus()).isEqualTo("NOT_EVALUATED");
        assertThat(overview.totalShortLinksScanned()).isNull();
        assertThat(overview.lowRiskCount()).isNull();
        assertThat(overview.mediumRiskCount()).isNull();
        assertThat(overview.highRiskCount()).isNull();
        assertThat(overview.avgRiskScore()).isNull();
        assertThat(overview.maxRiskScore()).isNull();
        assertThat(overview.groupRiskScore()).isNull();
        assertThat(overview.groupRiskLevel()).isEqualTo("UNKNOWN");
        assertThat(overview.disabledCount()).isNull();
        assertThat(overview.agentSummary()).isNull();
        assertThat(overview.watchingCount()).isZero();
        assertThat(overview.groupReasonCodes()).isEmpty();
        assertThat(overview.topRiskShortLinks()).isEmpty();
        assertThat(overview.riskTrend7d()).isEmpty();
        assertThat(overview.manualReview()).isEmpty();
        var order = inOrder(authority, groups);
        order.verify(authority).resolve(principal, "g1", null, null);
        order.verify(groups).findAuthorized(scope, "g1", null);
    }

    @Test
    void unavailableProfileDatabaseIsNotReportedAsUnevaluated() {
        var fixture = new FailureFixture();
        var failure = new DataAccessResourceFailureException("profile database unavailable");
        when(fixture.groups.findAuthorized(fixture.scope, "g1", null)).thenThrow(failure);
        assertThatThrownBy(() -> fixture.service.getGroupOverview(fixture.principal, "g1"))
                .isSameAs(failure);
        verifyNoInteractions(fixture.reviews, fixture.profiles);
    }

    @Test
    void unavailableManualReviewDatabaseIsNotReportedAsAnEmptyReview() {
        var fixture = new FailureFixture();
        var failure = new DataAccessResourceFailureException("review database unavailable");
        when(fixture.reviews.latestGroupState(fixture.scope, "g1")).thenThrow(failure);
        assertThatThrownBy(() -> fixture.service.getGroupOverview(fixture.principal, "g1"))
                .isSameAs(failure);
    }

    @Test
    void storedProfileOutsideAuthorizedScopeRemainsForbidden() {
        var fixture = new FailureFixture();
        var failure = new SecurityException("profile membership changed");
        when(fixture.groups.findAuthorized(fixture.scope, "g1", null)).thenThrow(failure);
        assertThatThrownBy(() -> fixture.service.getGroupOverview(fixture.principal, "g1"))
                .isSameAs(failure);
        verifyNoInteractions(fixture.reviews, fixture.profiles);
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
                        () -> service.getGroupOverview(
                                new AgentPrincipal("1001", "trusted-user", 1, false), "g1"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(
                        () ->
                                service.listGroupShortLinkCards(
                                        new AgentPrincipal("1001", "trusted-user", 1, false), "g1"))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(events, snapshots, reviews, profiles, groups);
    }

    private static final class FailureFixture {
        private final AgentAuthorityClient authority = mock(AgentAuthorityClient.class);
        private final JdbcGroupRiskProfileRepository groups = mock(JdbcGroupRiskProfileRepository.class);
        private final JdbcRiskReviewRepository reviews = mock(JdbcRiskReviewRepository.class);
        private final JdbcShortLinkRiskProfileRepository profiles = mock(JdbcShortLinkRiskProfileRepository.class);
        private final AuthorizedScope scope = new AuthorizedScope("1001", "1", List.of());
        private final AgentPrincipal principal = new AgentPrincipal("1001", "trusted-user", 1, false);
        private final RiskCenterService service = new RiskCenterService(
                mock(JdbcRiskEventRepository.class), mock(JdbcRiskSnapshotRepository.class),
                reviews, profiles, groups, mock(RiskPolicyService.class), authority);

        private FailureFixture() {
            when(authority.resolve(principal, "g1", null, null)).thenReturn(scope);
            when(groups.findAuthorized(scope, "g1", null)).thenReturn(Optional.empty());
        }
    }
}
