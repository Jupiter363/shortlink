package com.nageoffer.shortlink.admin.authority.identity.service;

import com.nageoffer.shortlink.admin.authority.identity.config.AgentIdentityConfiguration;
import com.nageoffer.shortlink.admin.authority.identity.exception.AgentIdentityException;
import com.nageoffer.shortlink.admin.authority.identity.model.AgentSessionBootstrapRequest;
import com.nageoffer.shortlink.admin.authority.identity.model.AgentSessionTokenResponse;
import com.nageoffer.shortlink.admin.authority.identity.model.AgentTokenPrincipal;
import com.nageoffer.shortlink.admin.authority.session.AgentSessionGrant;
import com.nageoffer.shortlink.admin.authority.session.AgentSessionGrantException;
import com.nageoffer.shortlink.admin.authority.session.AgentSessionGrantStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class AgentSessionLifecycleService {

    private static final Duration MAXIMUM_SESSION_TTL = Duration.ofHours(24);

    private static final int SESSION_ID_GENERATION_ATTEMPTS = 3;

    private static final Pattern LOCALE = Pattern.compile("^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$");

    private static final Pattern SESSION_ID = Pattern.compile("^as-s-[A-Za-z0-9_-]{1,123}$");

    private static final Pattern DELEGATION_TOKEN_ID = Pattern.compile("^adt-[A-Za-z0-9_-]{1,128}$");

    private static final Set<String> CAMPAIGN_ANALYSIS_SCOPES = Set.of(
            "agent:run",
            "agent:session:read",
            "capability:group:read",
            "capability:stats:read"
    );

    private final AgentIdentityConfiguration configuration;

    private final AgentTokenService tokenService;

    private final AgentSessionGrantStore grantStore;

    private final Clock clock;

    public AgentSessionLifecycleService(
            AgentIdentityConfiguration configuration,
            AgentTokenService tokenService,
            AgentSessionGrantStore grantStore,
            @Qualifier("agentIdentityClock") Clock clock
    ) {
        this.configuration = configuration;
        this.tokenService = tokenService;
        this.grantStore = grantStore;
        this.clock = clock;
    }

    public AgentSessionTokenResponse bootstrap(
            AgentSessionBootstrapRequest request,
            String ownerUserId,
            String ownerUsername
    ) {
        requireEnabled();
        validateBootstrapRequest(request);
        Instant sessionExpiresAt = clock.instant().plus(sessionTtl());
        for (int attempt = 0; attempt < SESSION_ID_GENERATION_ATTEMPTS; attempt++) {
            String sessionId = grantStore.newSessionId();
            AgentTokenService.IssuedDelegationToken issued = tokenService.issueDelegationToken(
                    ownerUserId,
                    ownerUsername,
                    sessionId,
                    1L,
                    sessionExpiresAt,
                    CAMPAIGN_ANALYSIS_SCOPES
            );
            try {
                AgentSessionGrant grant = grantStore.bootstrap(new AgentSessionGrantStore.BootstrapCommand(
                        sessionId,
                        configuration.getDefaultTenantId(),
                        ownerUserId,
                        ownerUsername,
                        request.agentType(),
                        CAMPAIGN_ANALYSIS_SCOPES,
                        issued.grantVersion(),
                        issued.tokenId(),
                        issued.expiresAt(),
                        sessionExpiresAt
                ));
                return response(grant, issued);
            } catch (AgentSessionGrantException exception) {
                if (exception.reason() != AgentSessionGrantException.Reason.CONFLICT
                        || attempt == SESSION_ID_GENERATION_ATTEMPTS - 1) {
                    throw exception;
                }
            }
        }
        throw AgentSessionGrantException.conflict();
    }

    public AgentSessionTokenResponse refresh(
            String sessionId,
            String ownerUserId,
            String ownerUsername,
            Map<String, Object> requestBody
    ) {
        requireEnabled();
        requireEmptyBody(requestBody);
        AgentSessionGrant current = grantStore.requireOwnedActive(ownerCommand(
                sessionId,
                ownerUserId,
                ownerUsername
        ));
        return rotate(current, ownerUserId, ownerUsername);
    }

    public AgentSessionTokenResponse refreshForCompatibilityChat(
            String sessionId,
            String ownerUserId,
            String ownerUsername,
            String agentType
    ) {
        requireEnabled();
        AgentSessionGrant current = grantStore.requireOwnedActive(ownerCommand(
                sessionId,
                ownerUserId,
                ownerUsername
        ));
        if (!current.agentType().equals(agentType)) {
            throw AgentSessionGrantException.invalid("Agent type does not match the Session Grant.");
        }
        return rotate(current, ownerUserId, ownerUsername);
    }

    private AgentSessionTokenResponse rotate(
            AgentSessionGrant current,
            String ownerUserId,
            String ownerUsername
    ) {
        long nextVersion;
        try {
            nextVersion = Math.addExact(current.grantVersion(), 1L);
        } catch (ArithmeticException exception) {
            throw AgentSessionGrantException.conflict();
        }
        AgentTokenService.IssuedDelegationToken issued = tokenService.issueDelegationToken(
                ownerUserId,
                ownerUsername,
                current.sessionId(),
                nextVersion,
                current.expiresAt(),
                current.scopes()
        );
        AgentSessionGrantStore.RefreshResult refreshed = grantStore.refresh(
                new AgentSessionGrantStore.RefreshCommand(
                        current.sessionId(),
                        current.tenantId(),
                        ownerUserId,
                        ownerUsername,
                        current.grantVersion(),
                        current.latestTokenId(),
                        nextVersion,
                        issued.tokenId(),
                        issued.expiresAt()
                )
        );
        return response(refreshed.grant(), issued);
    }

    public void revoke(
            String sessionId,
            String ownerUserId,
            String ownerUsername,
            Map<String, Object> requestBody
    ) {
        requireEnabled();
        requireEmptyBody(requestBody);
        grantStore.revoke(new AgentSessionGrantStore.RevokeCommand(
                sessionId,
                configuration.getDefaultTenantId(),
                ownerUserId,
                ownerUsername,
                "USER_CLOSED"
        ));
    }

    public boolean isActive(String sessionId, long grantVersion, String tokenId) {
        requireEnabled();
        if (sessionId == null
                || !SESSION_ID.matcher(sessionId).matches()
                || grantVersion < 1
                || tokenId == null
                || !DELEGATION_TOKEN_ID.matcher(tokenId).matches()) {
            throw AgentSessionGrantException.invalid("Token revocation check request is invalid.");
        }
        return grantStore.isActive(sessionId, grantVersion, tokenId);
    }

    public void requireActive(AgentTokenPrincipal principal, String tokenId) {
        requireEnabled();
        try {
            grantStore.requireActiveToken(new AgentSessionGrantStore.ActiveTokenCommand(
                    principal.sessionId(),
                    principal.tenantId(),
                    principal.subject(),
                    principal.username(),
                    principal.grantVersion(),
                    principal.scopes(),
                    tokenId
            ));
        } catch (AgentSessionGrantException exception) {
            throw AgentIdentityException.invalidToken();
        }
    }

    private AgentSessionGrantStore.OwnerCommand ownerCommand(
            String sessionId,
            String ownerUserId,
            String ownerUsername
    ) {
        return new AgentSessionGrantStore.OwnerCommand(
                sessionId,
                configuration.getDefaultTenantId(),
                ownerUserId,
                ownerUsername
        );
    }

    private AgentSessionTokenResponse response(
            AgentSessionGrant grant,
            AgentTokenService.IssuedDelegationToken issued
    ) {
        return new AgentSessionTokenResponse(
                grant.sessionId(),
                grant.agentType(),
                "/api/short-link/agent-runtime/v1/sessions/" + grant.sessionId(),
                issued.value(),
                issued.expiresAt(),
                grant.expiresAt(),
                grant.grantVersion()
        );
    }

    private void validateBootstrapRequest(AgentSessionBootstrapRequest request) {
        if (request == null
                || !AgentSessionGrantStore.CAMPAIGN_ANALYSIS_AGENT.equals(request.agentType())
                || request.clientContext() == null
                || !StringUtils.hasText(request.clientContext().locale())
                || !LOCALE.matcher(request.clientContext().locale()).matches()
                || request.clientContext().locale().length() > 35
                || !StringUtils.hasText(request.clientContext().timezone())
                || request.clientContext().timezone().length() > 64) {
            throw AgentSessionGrantException.invalid("Agent session bootstrap request is invalid.");
        }
        try {
            ZoneId.of(request.clientContext().timezone());
        } catch (DateTimeException exception) {
            throw AgentSessionGrantException.invalid("Agent session bootstrap request is invalid.");
        }
    }

    private void requireEmptyBody(Map<String, Object> requestBody) {
        if (requestBody != null && !requestBody.isEmpty()) {
            throw AgentSessionGrantException.invalid("Agent session request body must be empty.");
        }
    }

    private Duration sessionTtl() {
        Duration ttl = configuration.getSessionGrantTtl();
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAXIMUM_SESSION_TTL) > 0) {
            throw AgentIdentityException.sessionGrantUnavailable();
        }
        return ttl;
    }

    private void requireEnabled() {
        if (!configuration.isSessionGrantEnabled()) {
            throw AgentIdentityException.sessionGrantUnavailable();
        }
    }
}
