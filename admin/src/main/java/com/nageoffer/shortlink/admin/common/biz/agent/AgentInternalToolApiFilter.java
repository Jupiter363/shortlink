package com.nageoffer.shortlink.admin.common.biz.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.admin.authority.identity.config.AgentIdentityConfiguration;
import com.nageoffer.shortlink.admin.authority.identity.context.AgentAuthorityContext;
import com.nageoffer.shortlink.admin.authority.identity.exception.AgentIdentityException;
import com.nageoffer.shortlink.admin.authority.identity.model.AgentTokenPrincipal;
import com.nageoffer.shortlink.admin.authority.identity.service.AgentSessionLifecycleService;
import com.nageoffer.shortlink.admin.authority.identity.service.AgentTokenService;
import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.common.biz.user.UserInfoDTO;
import com.nageoffer.shortlink.admin.config.AgentAdminConfiguration;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public class AgentInternalToolApiFilter extends OncePerRequestFilter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private static final Map<String, String> REQUIRED_CAPABILITY_SCOPES = Map.of(
            "/internal/short-link-admin/v1/agent-capabilities/v1/groups/list",
            "capability:group:read",
            "/internal/short-link-admin/v1/agent-capabilities/v1/short-links/query",
            "capability:group:read",
            "/internal/short-link-admin/v1/agent-capabilities/v1/group-stats/query",
            "capability:stats:read"
    );

    public static final String INTERNAL_TOKEN_HEADER = "X-Agent-Internal-Token";

    public static final String USERNAME_HEADER = "X-Agent-Username";

    public static final String USER_ID_HEADER = "X-Agent-UserId";

    public static final String REAL_NAME_HEADER = "X-Agent-RealName";

    private static final String INTERNAL_TOOL_API_PREFIX = "/internal/short-link-admin/v1/agent-tools/";

    private static final String INTERNAL_CAPABILITY_API_PREFIX =
            "/internal/short-link-admin/v1/agent-capabilities/";

    private final AgentAdminConfiguration agentAdminConfiguration;

    private final AgentIdentityConfiguration agentIdentityConfiguration;

    private final AgentTokenService agentTokenService;

    private final AgentSessionLifecycleService sessionLifecycleService;

    public AgentInternalToolApiFilter(AgentAdminConfiguration agentAdminConfiguration) {
        this(agentAdminConfiguration, new AgentIdentityConfiguration(), null, null);
    }

    public AgentInternalToolApiFilter(
            AgentAdminConfiguration agentAdminConfiguration,
            AgentIdentityConfiguration agentIdentityConfiguration,
            AgentTokenService agentTokenService
    ) {
        this(agentAdminConfiguration, agentIdentityConfiguration, agentTokenService, null);
    }

    public AgentInternalToolApiFilter(
            AgentAdminConfiguration agentAdminConfiguration,
            AgentIdentityConfiguration agentIdentityConfiguration,
            AgentTokenService agentTokenService,
            AgentSessionLifecycleService sessionLifecycleService
    ) {
        this.agentAdminConfiguration = agentAdminConfiguration;
        this.agentIdentityConfiguration = agentIdentityConfiguration;
        this.agentTokenService = agentTokenService;
        this.sessionLifecycleService = sessionLifecycleService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!isProtectedAgentApi(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        UserInfoDTO authenticatedUser = isCapabilityApi(request)
                ? authenticateCapability(request, response)
                : authenticateLegacy(request, response, false);
        if (authenticatedUser == null) {
            return;
        }
        UserContext.setUser(authenticatedUser);
        try {
            filterChain.doFilter(request, response);
        } finally {
            UserContext.removeUser();
            AgentAuthorityContext.remove();
        }
    }

    private UserInfoDTO authenticateCapability(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        AgentIdentityConfiguration.CapabilityAuthMode mode =
                agentIdentityConfiguration.getCapabilityAuthMode();
        String authorization = request.getHeader("Authorization");
        if (StringUtils.hasText(authorization)) {
            if (mode == AgentIdentityConfiguration.CapabilityAuthMode.LEGACY) {
                writeCapabilityError(
                        request,
                        response,
                        HttpStatus.UNAUTHORIZED,
                        "Authority token authentication is disabled"
                );
                return null;
            }
            return authenticateAuthorityToken(request, response, authorization);
        }
        if (mode == AgentIdentityConfiguration.CapabilityAuthMode.AUTHORITY_TOKEN) {
            writeCapabilityError(request, response, HttpStatus.UNAUTHORIZED, "Authority token is required");
            return null;
        }
        return authenticateLegacy(request, response, true);
    }

    private UserInfoDTO authenticateAuthorityToken(
            HttpServletRequest request,
            HttpServletResponse response,
            String authorization
    ) throws IOException {
        if (agentTokenService == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            writeCapabilityError(request, response, HttpStatus.UNAUTHORIZED, "Invalid authority token");
            return null;
        }
        String token = authorization.substring(7).trim();
        String requiredScope = requiredScope(request);
        if (!StringUtils.hasText(token) || !StringUtils.hasText(requiredScope)) {
            writeCapabilityError(
                    request,
                    response,
                    HttpStatus.FORBIDDEN,
                    "Capability scope is not configured"
            );
            return null;
        }
        try {
            AgentTokenPrincipal principal = agentTokenService.verifyAuthorityToken(token, requiredScope);
            if (agentIdentityConfiguration.isSessionGrantEnabled()) {
                if (sessionLifecycleService == null) {
                    throw AgentIdentityException.sessionGrantUnavailable();
                }
                sessionLifecycleService.requireActive(principal, principal.parentTokenId());
            }
            AgentAuthorityContext.set(principal, agentIdentityConfiguration.getRuntimeServiceId());
            return new UserInfoDTO(principal.subject(), principal.username(), null);
        } catch (AgentIdentityException exception) {
            writeCapabilityError(request, response, exception.status(), exception.getMessage());
            return null;
        }
    }

    private UserInfoDTO authenticateLegacy(
            HttpServletRequest request,
            HttpServletResponse response,
            boolean capability
    ) throws IOException {
        String internalToken = agentAdminConfiguration.getInternalToken();
        if (!StringUtils.hasText(internalToken) && !agentAdminConfiguration.isInternalTokenDevMode()) {
            writeAuthError(
                    request,
                    response,
                    HttpStatus.UNAUTHORIZED,
                    "Internal token is not configured",
                    capability
            );
            return null;
        }
        if (StringUtils.hasText(internalToken)
                && !tokenMatches(internalToken, request.getHeader(INTERNAL_TOKEN_HEADER))) {
            writeAuthError(
                    request,
                    response,
                    HttpStatus.UNAUTHORIZED,
                    "Invalid internal token",
                    capability
            );
            return null;
        }
        String username = request.getHeader(USERNAME_HEADER);
        if (!StringUtils.hasText(username)) {
            writeAuthError(
                    request,
                    response,
                    HttpStatus.BAD_REQUEST,
                    "Missing trusted agent username",
                    capability
            );
            return null;
        }
        return new UserInfoDTO(
                request.getHeader(USER_ID_HEADER),
                username,
                request.getHeader(REAL_NAME_HEADER)
        );
    }

    private boolean isProtectedAgentApi(HttpServletRequest request) {
        String requestPath = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && requestPath.startsWith(contextPath)) {
            requestPath = requestPath.substring(contextPath.length());
        }
        return requestPath.startsWith(INTERNAL_TOOL_API_PREFIX)
                || requestPath.startsWith(INTERNAL_CAPABILITY_API_PREFIX);
    }

    private boolean isCapabilityApi(HttpServletRequest request) {
        return normalizedPath(request).startsWith(INTERNAL_CAPABILITY_API_PREFIX);
    }

    private String normalizedPath(HttpServletRequest request) {
        String requestPath = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && requestPath.startsWith(contextPath)) {
            return requestPath.substring(contextPath.length());
        }
        return requestPath;
    }

    private String requiredScope(HttpServletRequest request) {
        return REQUIRED_CAPABILITY_SCOPES.get(normalizedPath(request));
    }

    private boolean tokenMatches(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"success\":false,\"code\":\"%s\",\"message\":\"%s\"}"
                .formatted(status.value(), message));
    }

    private void writeAuthError(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String message,
            boolean capability
    ) throws IOException {
        if (capability) {
            writeCapabilityError(request, response, status, message);
            return;
        }
        writeError(response, status, message);
    }

    private void writeCapabilityError(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String detail
    ) throws IOException {
        String code = status == HttpStatus.FORBIDDEN ? "CAPABILITY_FORBIDDEN" : "TOKEN_INVALID";
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put(
                "type",
                "https://shortlink.example/problems/agent/"
                        + code.toLowerCase(Locale.ROOT).replace('_', '-')
        );
        problem.put("title", "Agent capability authentication failed");
        problem.put("status", status.value());
        problem.put("code", code);
        problem.put("detail", detail);
        problem.put("instance", normalizedPath(request));
        problem.put("traceId", safeRequestId(request.getHeader("X-Agent-Trace-ID")));
        problem.put("requestId", requestId(request.getHeader("X-Request-ID")));
        problem.put("retryable", false);
        problem.put("violations", List.of());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JSON.writeValueAsString(problem));
    }

    private String requestId(String candidate) {
        String safe = safeRequestId(candidate);
        return safe != null ? safe : "req-" + UUID.randomUUID().toString().replace("-", "");
    }

    private String safeRequestId(String candidate) {
        return StringUtils.hasText(candidate) && SAFE_REQUEST_ID.matcher(candidate).matches()
                ? candidate
                : null;
    }
}
