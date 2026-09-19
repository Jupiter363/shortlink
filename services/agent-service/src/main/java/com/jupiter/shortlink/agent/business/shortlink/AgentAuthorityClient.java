package com.jupiter.shortlink.agent.business.shortlink;

import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.infrastructure.llm.BoundedHttpTransport;
import com.jupiter.shortlink.contract.GroupMembersPage;
import com.fasterxml.jackson.core.JsonProcessingException;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Current scope is always checked by Admin/Command before any stored Agent profile is used. */
@Component
public class AgentAuthorityClient {
    public static final String GROUP_MEMBERS_PATH = "/internal/short-link-admin/v1/agent-tools/authorization/group-members-page";
    private static final Set<String> PAGE_FAILURES = Set.of("FORBIDDEN", "QUERY_SCOPE_CHANGED",
            "AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE", "REMOTE_UNAVAILABLE");
    private final AgentProperties properties;
    private final BoundedHttpTransport transport;

    public AgentAuthorityClient(AgentProperties properties, BoundedHttpTransport transport) {
        this.properties = properties;
        this.transport = transport;
    }

    /** One bounded current authority page. Collection, persistence and new generations belong to the caller. */
    public GroupMembersPage resolveGroupMembersPage(AgentPrincipal principal, String gid,
                                                   Long afterLinkId, String ownershipVersion) {
        if (principal == null || principal.system() || principal.tenantId() == null || principal.username() == null
                || principal.authVersion() < 0) throw pageFailure("FORBIDDEN");
        var request = new GroupMembersPage.Request(gid, afterLinkId, ownershipVersion);
        Map<String, String> trustedHeaders;
        try { trustedHeaders = headers(principal); }
        catch (SecurityException denied) { throw pageFailure("FORBIDDEN"); }
        Map<String, Object> response;
        try {
            response = transport.exchange("POST", URI.create(properties.getBusiness().getBaseUrl().replaceAll("/+$", "")
                    + GROUP_MEMBERS_PATH), trustedHeaders, request.asMap());
        } catch (BoundedHttpTransport.HttpStatusFailure failure) {
            throw pageFailure(switch (failure.statusCode()) {
                case 401, 403 -> "FORBIDDEN";
                case 409 -> "QUERY_SCOPE_CHANGED";
                case 404, 405, 501 -> "AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE";
                default -> "REMOTE_UNAVAILABLE";
            });
        } catch (IllegalStateException failure) {
            throw pageFailure(failure.getCause() instanceof JsonProcessingException
                    ? "AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE" : "REMOTE_UNAVAILABLE");
        }
        if (response == null) throw pageFailure("AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE");
        if (!"0".equals(response.get("code"))) {
            Object code = response.get("code");
            throw pageFailure(code instanceof String value && PAGE_FAILURES.contains(value)
                    ? value : "AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE");
        }
        if (!(response.get("data") instanceof Map<?, ?> data)) throw pageFailure("AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE");
        try {
            GroupMembersPage page = GroupMembersPage.fromMap(data);
            page.requireMatches(request, principal.tenantId(), principal.username(), principal.authVersion());
            return page;
        } catch (IllegalArgumentException invalid) {
            throw pageFailure("QUERY_SCOPE_CHANGED".equals(invalid.getMessage())
                    ? "QUERY_SCOPE_CHANGED" : "AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE");
        }
    }

    public static final class AuthorityPageException extends IllegalStateException {
        private final String code;
        private AuthorityPageException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }

    private static AuthorityPageException pageFailure(String code) { return new AuthorityPageException(code); }

    @SuppressWarnings("unchecked")
    public AuthorizedScope resolve(
            AgentPrincipal principal, String gid, String fullShortUrl, List<Long> linkIds) {
        AuthorizedScope scope = resolvePage(principal, gid, fullShortUrl, linkIds, null, null);
        if (scope.nextCursor() != null)
            throw new IllegalStateException("TOO_LARGE: narrow the authorized profile scope");
        return scope;
    }

    @SuppressWarnings("unchecked")
    public AuthorizedScope resolvePage(
            AgentPrincipal principal,
            String gid,
            String fullShortUrl,
            List<Long> linkIds,
            Long afterLinkId,
            String ownershipVersion) {
        Map<String, Object> request = new LinkedHashMap<>();
        if (gid != null) request.put("gid", gid);
        if (fullShortUrl != null) request.put("fullShortUrl", fullShortUrl);
        if (linkIds != null) request.put("linkIds", linkIds);
        if (afterLinkId != null) request.put("afterLinkId", afterLinkId);
        if (ownershipVersion != null) request.put("ownershipVersion", ownershipVersion);
        Map<String, Object> response =
                transport.exchange(
                        "POST",
                        URI.create(
                                properties.getBusiness().getBaseUrl().replaceAll("/+$", "")
                                        + "/internal/short-link-admin/v1/agent-tools/authorization/resolve"),
                        headers(principal),
                        request);
        if (!"0".equals(String.valueOf(response.get("code")))
                || !(response.get("data") instanceof Map<?, ?> data)) {
            throw new SecurityException("Current profile access could not be authorized");
        }
        if (!(data.get("tenantId") instanceof String tenant)
                || !tenant.matches("[1-9][0-9]{0,18}")
                || !(data.get("ownershipVersion") instanceof String version)
                || version.isBlank())
            throw new SecurityException("Authority scope identity is invalid");
        if (!principal.system() && !tenant.equals(principal.tenantId()))
            throw new SecurityException("Profile tenant scope mismatch");
        if (!(data.get("links") instanceof List<?> links) || links.size() > 500)
            throw new SecurityException("Profile scope is invalid or exceeds budget");
        for (Object value : links) {
            if (!(value instanceof Map<?, ?> link)
                    || !(link.get("gid") instanceof String group)
                    || group.isBlank()
                    || com.jupiter.shortlink.agent.riskprofile.model.StatsEvidence.number(
                                    link.get("linkId"))
                            < 1) throw new SecurityException("Authority link identity is invalid");
        }
        if (ownershipVersion != null && !ownershipVersion.equals(version))
            throw new SecurityException("QUERY_SCOPE_CHANGED: ownership changed");
        return new AuthorizedScope(
                tenant,
                version,
                links.stream().map(value -> (Map<String, Object>) value).toList(),
                data.get("nextCursor") == null
                        ? null
                        : com.jupiter.shortlink.agent.riskprofile.model.StatsEvidence.number(
                                data.get("nextCursor")));
    }

    public Map<String, String> headers(AgentPrincipal principal) {
        if (principal == null) throw new SecurityException("Trusted Agent principal is required");
        String token = properties.getBusiness().getInternalToken();
        if (token == null || token.length() < 24)
            throw new SecurityException("Agent service credentials are unavailable");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Agent-Internal-Token", token);
        headers.put("X-Agent-Username", principal.username());
        if (principal.system()) headers.put("X-Agent-Principal-Mode", "SYSTEM");
        else {
            headers.put("X-Agent-UserId", principal.tenantId());
            headers.put("X-Agent-Auth-Version", Long.toString(principal.authVersion()));
        }
        return headers;
    }

    public record AuthorizedScope(
            String tenantId,
            String ownershipVersion,
            List<Map<String, Object>> links,
            Long nextCursor) {
        public AuthorizedScope(
                String tenantId, String ownershipVersion, List<Map<String, Object>> links) {
            this(tenantId, ownershipVersion, links, null);
        }

        public boolean contains(long linkId, String gid) {
            return links.stream()
                    .anyMatch(
                            link ->
                                    new java.math.BigDecimal(link.get("linkId").toString())
                                                            .longValueExact()
                                                    == linkId
                                            && (gid == null || gid.equals(link.get("gid"))));
        }
    }
}
