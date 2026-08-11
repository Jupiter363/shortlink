package com.nageoffer.shortlink.admin.authority.identity;

import com.nageoffer.shortlink.admin.authority.identity.config.AgentIdentityConfiguration;
import com.nageoffer.shortlink.admin.authority.identity.exception.AgentIdentityException;
import com.nageoffer.shortlink.admin.authority.identity.model.AgentSessionBootstrapRequest;
import com.nageoffer.shortlink.admin.authority.identity.model.AgentSessionTokenResponse;
import com.nageoffer.shortlink.admin.authority.identity.service.AgentSessionLifecycleService;
import com.nageoffer.shortlink.admin.authority.identity.service.AgentTokenService;
import com.nageoffer.shortlink.admin.authority.session.AgentSessionGrant;
import com.nageoffer.shortlink.admin.authority.session.AgentSessionGrantException;
import com.nageoffer.shortlink.admin.authority.session.AgentSessionGrantStatus;
import com.nageoffer.shortlink.admin.authority.session.AgentSessionGrantStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentSessionLifecycleServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");

    private static final String SESSION_ID = "as-s-lifecycle";

    private static final String USERNAME = "trusted-user";

    private static final String USER_ID = "1001";

    private static final Set<String> SCOPES = Set.of(
            "agent:run",
            "agent:session:read",
            "capability:group:read",
            "capability:stats:read"
    );

    private AgentIdentityConfiguration configuration;

    private AgentTokenService tokenService;

    private AgentSessionGrantStore grantStore;

    private AgentSessionLifecycleService service;

    @BeforeEach
    void setUp() {
        configuration = new AgentIdentityConfiguration();
        configuration.setSessionGrantEnabled(true);
        tokenService = mock(AgentTokenService.class);
        grantStore = mock(AgentSessionGrantStore.class);
        service = new AgentSessionLifecycleService(
                configuration,
                tokenService,
                grantStore,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void bootstrapGeneratesAuthoritySessionAndPersistsOnlyTokenIdentity() {
        Instant sessionExpiresAt = NOW.plusSeconds(8 * 60 * 60);
        AgentTokenService.IssuedDelegationToken issued = issued(
                "runtime-token",
                "adt-bootstrap",
                NOW.plusSeconds(300),
                1
        );
        when(grantStore.newSessionId()).thenReturn(SESSION_ID);
        when(tokenService.issueDelegationToken(
                eq(USER_ID),
                eq(USERNAME),
                eq(SESSION_ID),
                eq(1L),
                eq(sessionExpiresAt),
                anySet()
        )).thenReturn(issued);
        when(grantStore.bootstrap(any())).thenAnswer(invocation -> {
            AgentSessionGrantStore.BootstrapCommand command = invocation.getArgument(0);
            return grant(command, AgentSessionGrantStatus.ACTIVE, NOW, NOW);
        });

        AgentSessionTokenResponse response = service.bootstrap(
                new AgentSessionBootstrapRequest(
                        "campaign-analysis",
                        new AgentSessionBootstrapRequest.ClientContext("zh-CN", "Asia/Shanghai")
                ),
                USER_ID,
                USERNAME
        );

        ArgumentCaptor<AgentSessionGrantStore.BootstrapCommand> command =
                ArgumentCaptor.forClass(AgentSessionGrantStore.BootstrapCommand.class);
        verify(grantStore).bootstrap(command.capture());
        assertThat(command.getValue().sessionId()).isEqualTo(SESSION_ID);
        assertThat(command.getValue().ownerUsername()).isEqualTo(USERNAME);
        assertThat(command.getValue().tokenId()).isEqualTo("adt-bootstrap");
        assertThat(command.getValue().scopes()).containsExactlyInAnyOrderElementsOf(SCOPES);
        assertThat(response.sessionId()).isEqualTo(SESSION_ID);
        assertThat(response.runtimeToken()).isEqualTo("runtime-token");
        assertThat(response.runtimeUrl()).endsWith(SESSION_ID);
        assertThat(response.grantVersion()).isEqualTo(1L);
        assertThat(response.sessionExpiresAt()).isEqualTo(sessionExpiresAt);
    }

    @Test
    void refreshSignsNextVersionThenUsesLatestJtiCompareAndSwap() {
        AgentSessionGrant current = grant(
                SESSION_ID,
                3,
                "adt-current",
                NOW.plusSeconds(180),
                NOW.plusSeconds(3600),
                AgentSessionGrantStatus.ACTIVE
        );
        AgentTokenService.IssuedDelegationToken issued = issued(
                "runtime-token-v4",
                "adt-next",
                NOW.plusSeconds(300),
                4
        );
        AgentSessionGrant refreshed = grant(
                SESSION_ID,
                4,
                "adt-next",
                issued.expiresAt(),
                current.expiresAt(),
                AgentSessionGrantStatus.ACTIVE
        );
        when(grantStore.requireOwnedActive(any())).thenReturn(current);
        when(tokenService.issueDelegationToken(
                USER_ID,
                USERNAME,
                SESSION_ID,
                4L,
                current.expiresAt(),
                SCOPES
        )).thenReturn(issued);
        when(grantStore.refresh(any())).thenReturn(
                new AgentSessionGrantStore.RefreshResult(refreshed, "adt-current")
        );

        AgentSessionTokenResponse response = service.refresh(
                SESSION_ID,
                USER_ID,
                USERNAME,
                Map.of()
        );

        ArgumentCaptor<AgentSessionGrantStore.RefreshCommand> command =
                ArgumentCaptor.forClass(AgentSessionGrantStore.RefreshCommand.class);
        verify(grantStore).refresh(command.capture());
        assertThat(command.getValue().expectedGrantVersion()).isEqualTo(3L);
        assertThat(command.getValue().expectedLatestTokenId()).isEqualTo("adt-current");
        assertThat(command.getValue().nextGrantVersion()).isEqualTo(4L);
        assertThat(command.getValue().nextTokenId()).isEqualTo("adt-next");
        assertThat(response.grantVersion()).isEqualTo(4L);
        assertThat(response.runtimeToken()).isEqualTo("runtime-token-v4");
    }

    @Test
    void compatibilityChatRejectsAgentTypeMismatchBeforeRotatingToken() {
        when(grantStore.requireOwnedActive(any())).thenReturn(grant(
                SESSION_ID,
                1,
                "adt-current",
                NOW.plusSeconds(180),
                NOW.plusSeconds(3600),
                AgentSessionGrantStatus.ACTIVE
        ));

        assertThatThrownBy(() -> service.refreshForCompatibilityChat(
                SESSION_ID,
                USER_ID,
                USERNAME,
                "security-risk"
        )).isInstanceOf(AgentSessionGrantException.class)
                .hasMessage("Agent type does not match the Session Grant.");

        verify(tokenService, never()).issueDelegationToken(
                any(), any(), any(), eq(2L), any(), anySet()
        );
        verify(grantStore, never()).refresh(any());
    }

    @Test
    void refreshAndRevokeRejectNonEmptyBodies() {
        assertThatThrownBy(() -> service.refresh(
                SESSION_ID,
                USER_ID,
                USERNAME,
                Map.of("ownerUserId", "attacker")
        )).isInstanceOf(AgentSessionGrantException.class);
        assertThatThrownBy(() -> service.revoke(
                SESSION_ID,
                USER_ID,
                USERNAME,
                Map.of("reason", "override")
        )).isInstanceOf(AgentSessionGrantException.class);

        verify(grantStore, never()).requireOwnedActive(any());
        verify(grantStore, never()).revoke(any());
    }

    @Test
    void disabledSessionGrantFailsBeforeDatabaseAccess() {
        configuration.setSessionGrantEnabled(false);

        assertThatThrownBy(() -> service.isActive(SESSION_ID, 1, "adt-token"))
                .isInstanceOf(AgentIdentityException.class)
                .hasMessage("Agent Session Grant is not enabled or configured.");

        verify(grantStore, never()).isActive(any(), eq(1L), any());
    }

    private AgentTokenService.IssuedDelegationToken issued(
            String value,
            String tokenId,
            Instant expiresAt,
            long version
    ) {
        return new AgentTokenService.IssuedDelegationToken(value, tokenId, expiresAt, version, SCOPES);
    }

    private AgentSessionGrant grant(
            AgentSessionGrantStore.BootstrapCommand command,
            AgentSessionGrantStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new AgentSessionGrant(
                1L,
                command.sessionId(),
                command.tenantId(),
                command.ownerUserId(),
                command.ownerUsername(),
                command.agentType(),
                command.scopes(),
                status,
                command.grantVersion(),
                command.tokenId(),
                command.tokenExpiresAt(),
                command.grantExpiresAt(),
                createdAt,
                updatedAt
        );
    }

    private AgentSessionGrant grant(
            String sessionId,
            long version,
            String tokenId,
            Instant tokenExpiresAt,
            Instant sessionExpiresAt,
            AgentSessionGrantStatus status
    ) {
        return new AgentSessionGrant(
                1L,
                sessionId,
                "tenant-default",
                USER_ID,
                USERNAME,
                "campaign-analysis",
                SCOPES,
                status,
                version,
                tokenId,
                tokenExpiresAt,
                sessionExpiresAt,
                NOW,
                NOW
        );
    }
}
