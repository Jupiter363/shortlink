package com.nageoffer.shortlink.admin.authority.identity.service;

import com.nageoffer.shortlink.admin.authority.identity.config.AgentIdentityConfiguration;
import com.nageoffer.shortlink.admin.authority.identity.exception.AgentIdentityException;
import com.nageoffer.shortlink.admin.authority.identity.model.AgentTokenPrincipal;
import com.nageoffer.shortlink.admin.authority.identity.model.TokenExchangeResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

@Service
public class AgentTokenExchangeService {

    public static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange";

    public static final String SUBJECT_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:jwt";

    public static final String ISSUED_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";

    private static final Set<String> EXCHANGEABLE_SCOPES = Set.of(
            "capability:group:read",
            "capability:stats:read",
            "action:proposal:create"
    );

    private final AgentIdentityConfiguration configuration;

    private final AgentTokenService tokenService;

    private final MtlsRuntimeIdentityVerifier runtimeIdentityVerifier;

    private final AgentSessionLifecycleService sessionLifecycleService;

    private final Clock clock;

    @Autowired
    public AgentTokenExchangeService(
            AgentIdentityConfiguration configuration,
            AgentTokenService tokenService,
            MtlsRuntimeIdentityVerifier runtimeIdentityVerifier,
            AgentSessionLifecycleService sessionLifecycleService,
            @Qualifier("agentIdentityClock") Clock agentIdentityClock
    ) {
        this.configuration = configuration;
        this.tokenService = tokenService;
        this.runtimeIdentityVerifier = runtimeIdentityVerifier;
        this.sessionLifecycleService = sessionLifecycleService;
        this.clock = agentIdentityClock;
    }

    public AgentTokenExchangeService(
            AgentIdentityConfiguration configuration,
            AgentTokenService tokenService,
            MtlsRuntimeIdentityVerifier runtimeIdentityVerifier,
            Clock agentIdentityClock
    ) {
        this(configuration, tokenService, runtimeIdentityVerifier, null, agentIdentityClock);
    }

    public TokenExchangeResponse exchange(
            HttpServletRequest request,
            String grantType,
            String subjectToken,
            String subjectTokenType,
            String audience,
            String scope
    ) {
        validateProtocol(grantType, subjectToken, subjectTokenType, audience);
        String runtimeServiceId = runtimeIdentityVerifier.verify(request);
        AgentTokenPrincipal delegation = tokenService.verifyDelegationToken(subjectToken);
        if (configuration.isSessionGrantEnabled()) {
            if (sessionLifecycleService == null) {
                throw AgentIdentityException.sessionGrantUnavailable();
            }
            sessionLifecycleService.requireActive(delegation, delegation.tokenId());
        }
        Set<String> requestedScopes = parseScopes(scope);
        if (!EXCHANGEABLE_SCOPES.containsAll(requestedScopes)) {
            throw AgentIdentityException.forbidden("Requested scope is not exchangeable.");
        }
        AgentTokenService.IssuedAuthorityToken issued = tokenService.issueAuthorityToken(
                delegation,
                runtimeServiceId,
                requestedScopes
        );
        long expiresIn = Math.max(1, Duration.between(clock.instant(), issued.expiresAt()).toSeconds());
        return new TokenExchangeResponse(
                issued.value(),
                ISSUED_TOKEN_TYPE,
                "Bearer",
                expiresIn,
                String.join(" ", new TreeSet<>(issued.scopes()))
        );
    }

    private void validateProtocol(
            String grantType,
            String subjectToken,
            String subjectTokenType,
            String audience
    ) {
        if (!GRANT_TYPE.equals(grantType)
                || !StringUtils.hasText(subjectToken)
                || subjectToken.length() > 16_384
                || !SUBJECT_TOKEN_TYPE.equals(subjectTokenType)
                || !configuration.getAuthorityAudience().equals(audience)) {
            throw AgentIdentityException.invalidExchange("Token exchange parameters are invalid.");
        }
    }

    private Set<String> parseScopes(String scope) {
        if (!StringUtils.hasText(scope) || scope.length() > 1024) {
            throw AgentIdentityException.invalidExchange("Token exchange scope is invalid.");
        }
        Set<String> scopes = new LinkedHashSet<>(Arrays.asList(scope.trim().split("\\s+")));
        if (scopes.isEmpty() || scopes.stream().anyMatch(each -> !StringUtils.hasText(each))) {
            throw AgentIdentityException.invalidExchange("Token exchange scope is invalid.");
        }
        return Set.copyOf(scopes);
    }
}
