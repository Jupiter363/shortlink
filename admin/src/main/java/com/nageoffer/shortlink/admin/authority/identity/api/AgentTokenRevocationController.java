package com.nageoffer.shortlink.admin.authority.identity.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.nageoffer.shortlink.admin.authority.identity.model.TokenRevocationCheckRequest;
import com.nageoffer.shortlink.admin.authority.identity.model.TokenRevocationCheckResponse;
import com.nageoffer.shortlink.admin.authority.identity.service.AgentSessionLifecycleService;
import com.nageoffer.shortlink.admin.authority.identity.service.MtlsRuntimeIdentityVerifier;
import com.nageoffer.shortlink.admin.authority.session.AgentSessionGrantException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;

@RestController
public class AgentTokenRevocationController {

    private final AgentSessionLifecycleService sessionLifecycleService;

    private final MtlsRuntimeIdentityVerifier runtimeIdentityVerifier;

    private final Clock clock;

    public AgentTokenRevocationController(
            AgentSessionLifecycleService sessionLifecycleService,
            MtlsRuntimeIdentityVerifier runtimeIdentityVerifier,
            @Qualifier("agentIdentityClock") Clock clock
    ) {
        this.sessionLifecycleService = sessionLifecycleService;
        this.runtimeIdentityVerifier = runtimeIdentityVerifier;
        this.clock = clock;
    }

    @PostMapping(
            value = "/internal/short-link-admin/v1/agent-identity/revocations/check",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<TokenRevocationCheckResponse> check(
            HttpServletRequest servletRequest,
            @RequestBody(required = false) JsonNode requestBody
    ) {
        runtimeIdentityVerifier.verify(servletRequest);
        TokenRevocationCheckRequest request = revocationCheckRequest(requestBody);
        boolean active = sessionLifecycleService.isActive(
                request.sessionId(),
                request.grantVersion(),
                request.tokenId()
        );
        TokenRevocationCheckResponse response = new TokenRevocationCheckResponse(
                active,
                request.sessionId(),
                request.grantVersion(),
                request.tokenId(),
                clock.instant(),
                active ? null : "TOKEN_REVOKED"
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(response);
    }

    private TokenRevocationCheckRequest revocationCheckRequest(JsonNode request) {
        if (request == null
                || !request.isObject()
                || !fieldNames(request).equals(Set.of("sessionId", "grantVersion", "tokenId"))
                || !request.path("sessionId").isTextual()
                || !request.path("grantVersion").isIntegralNumber()
                || !request.path("grantVersion").canConvertToLong()
                || !request.path("tokenId").isTextual()) {
            throw AgentSessionGrantException.invalid("Token revocation check request is invalid.");
        }
        return new TokenRevocationCheckRequest(
                request.get("sessionId").textValue(),
                request.get("grantVersion").longValue(),
                request.get("tokenId").textValue()
        );
    }

    private Set<String> fieldNames(JsonNode node) {
        HashSet<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }
}
