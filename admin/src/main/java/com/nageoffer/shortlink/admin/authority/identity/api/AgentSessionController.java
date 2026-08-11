package com.nageoffer.shortlink.admin.authority.identity.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.nageoffer.shortlink.admin.authority.identity.exception.AgentIdentityException;
import com.nageoffer.shortlink.admin.authority.identity.model.AgentSessionBootstrapRequest;
import com.nageoffer.shortlink.admin.authority.identity.model.AgentSessionTokenResponse;
import com.nageoffer.shortlink.admin.authority.identity.service.AgentSessionLifecycleService;
import com.nageoffer.shortlink.admin.authority.session.AgentSessionGrantException;
import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

@RestController
@RequiredArgsConstructor
public class AgentSessionController {

    private final AgentSessionLifecycleService sessionLifecycleService;

    @PostMapping(
            value = "/api/short-link/admin/v1/agent/sessions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<AgentSessionTokenResponse> bootstrap(
            @RequestBody(required = false) JsonNode request
    ) {
        String username = requireUsername();
        AgentSessionTokenResponse response = sessionLifecycleService.bootstrap(
                bootstrapRequest(request),
                UserContext.getUserId(),
                username
        );
        return tokenResponse(response);
    }

    @PostMapping(
            value = "/api/short-link/admin/v1/agent/sessions/{sessionId}/token/refresh",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<AgentSessionTokenResponse> refresh(
            @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, Object> requestBody
    ) {
        AgentSessionTokenResponse response = sessionLifecycleService.refresh(
                sessionId,
                UserContext.getUserId(),
                requireUsername(),
                requestBody
        );
        return tokenResponse(response);
    }

    @DeleteMapping("/api/short-link/admin/v1/agent/sessions/{sessionId}")
    public ResponseEntity<Void> revoke(
            @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, Object> requestBody
    ) {
        sessionLifecycleService.revoke(
                sessionId,
                UserContext.getUserId(),
                requireUsername(),
                requestBody
        );
        return ResponseEntity.noContent().build();
    }

    private String requireUsername() {
        String username = UserContext.getUsername();
        if (!StringUtils.hasText(username)) {
            throw AgentIdentityException.unauthenticatedSessionActor();
        }
        return username;
    }

    private ResponseEntity<AgentSessionTokenResponse> tokenResponse(
            AgentSessionTokenResponse response
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(response);
    }

    private AgentSessionBootstrapRequest bootstrapRequest(JsonNode request) {
        if (request == null
                || !request.isObject()
                || !fieldNames(request).equals(Set.of("agentType", "clientContext"))) {
            throw AgentSessionGrantException.invalid(
                    "Agent session bootstrap request is invalid."
            );
        }
        JsonNode clientContext = request.get("clientContext");
        if (clientContext == null
                || !clientContext.isObject()
                || !fieldNames(clientContext).equals(Set.of("locale", "timezone"))
                || !request.path("agentType").isTextual()
                || !clientContext.path("locale").isTextual()
                || !clientContext.path("timezone").isTextual()) {
            throw AgentSessionGrantException.invalid(
                    "Agent session bootstrap request is invalid."
            );
        }
        return new AgentSessionBootstrapRequest(
                request.get("agentType").textValue(),
                new AgentSessionBootstrapRequest.ClientContext(
                        clientContext.get("locale").textValue(),
                        clientContext.get("timezone").textValue()
                )
        );
    }

    private Set<String> fieldNames(JsonNode node) {
        java.util.HashSet<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }
}
