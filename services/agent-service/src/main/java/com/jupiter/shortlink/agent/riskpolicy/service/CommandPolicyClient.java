package com.jupiter.shortlink.agent.riskpolicy.service;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.infrastructure.llm.BoundedHttpTransport;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** Admin resolves interactive and configured system principals before forwarding to Command. */
@Component
public class CommandPolicyClient {
    private final AgentProperties properties;
    private final BoundedHttpTransport http;
    private final AgentAuthorityClient authority;

    public CommandPolicyClient(
            AgentProperties properties, BoundedHttpTransport http, AgentAuthorityClient authority) {
        this.properties = properties;
        this.http = http;
        this.authority = authority;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> result(AgentPrincipal principal, String commandId) {
        if (commandId == null || !commandId.matches("[a-zA-Z0-9:_-]{1,128}"))
            throw new IllegalArgumentException("Invalid commandId");
        Object data = call(principal, "GET", "commands/" + commandId, null);
        return data == null ? null : (Map<String, Object>) data;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> current(AgentPrincipal principal, List<Long> linkIds) {
        if (linkIds == null || linkIds.isEmpty() || linkIds.size() > 100)
            throw new IllegalArgumentException("Policy scope budget exceeded");
        return (List<Map<String, Object>>)
                call(principal, "POST", "current", Map.of("linkIds", linkIds));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> activate(AgentPrincipal principal, Map<String, Object> command) {
        return (Map<String, Object>) call(principal, "POST", "activate", command);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> revoke(
            AgentPrincipal principal, String commandId, long linkId, String policyId) {
        return (Map<String, Object>)
                call(
                        principal,
                        "POST",
                        "revoke",
                        Map.of("commandId", commandId, "linkId", linkId, "policyId", policyId));
    }

    private Object call(AgentPrincipal principal, String method, String path, Object request) {
        Map<String, Object> response =
                http.exchange(
                        method,
                        URI.create(
                                properties.getBusiness().getBaseUrl().replaceAll("/+$", "")
                                        + "/internal/short-link-admin/v1/agent-tools/policies/"
                                        + path),
                        authority.headers(principal),
                        request);
        if (!"0".equals(String.valueOf(response.get("code"))))
            throw new IllegalStateException("Policy authority request failed");
        return response.get("data");
    }
}
