package com.nageoffer.shortlink.admin.common.biz.agent;

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

public class AgentInternalToolApiFilter extends OncePerRequestFilter {

    public static final String INTERNAL_TOKEN_HEADER = "X-Agent-Internal-Token";

    public static final String USERNAME_HEADER = "X-Agent-Username";

    public static final String USER_ID_HEADER = "X-Agent-UserId";

    public static final String REAL_NAME_HEADER = "X-Agent-RealName";

    private static final String INTERNAL_TOOL_API_PREFIX = "/internal/short-link-admin/v1/agent-tools/";

    private final AgentAdminConfiguration agentAdminConfiguration;

    public AgentInternalToolApiFilter(AgentAdminConfiguration agentAdminConfiguration) {
        this.agentAdminConfiguration = agentAdminConfiguration;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!isInternalToolApi(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        String internalToken = agentAdminConfiguration.getInternalToken();
        if (!StringUtils.hasText(internalToken) && !agentAdminConfiguration.isInternalTokenDevMode()) {
            writeError(response, HttpStatus.UNAUTHORIZED, "Internal token is not configured");
            return;
        }
        if (StringUtils.hasText(internalToken) && !internalToken.equals(request.getHeader(INTERNAL_TOKEN_HEADER))) {
            writeError(response, HttpStatus.UNAUTHORIZED, "Invalid internal token");
            return;
        }
        String username = request.getHeader(USERNAME_HEADER);
        if (!StringUtils.hasText(username)) {
            writeError(response, HttpStatus.BAD_REQUEST, "Missing trusted agent username");
            return;
        }
        UserContext.setUser(new UserInfoDTO(
                request.getHeader(USER_ID_HEADER),
                username,
                request.getHeader(REAL_NAME_HEADER)
        ));
        try {
            filterChain.doFilter(request, response);
        } finally {
            UserContext.removeUser();
        }
    }

    private boolean isInternalToolApi(HttpServletRequest request) {
        String requestPath = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && requestPath.startsWith(contextPath)) {
            requestPath = requestPath.substring(contextPath.length());
        }
        return requestPath.startsWith(INTERNAL_TOOL_API_PREFIX);
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"success\":false,\"code\":\"%s\",\"message\":\"%s\"}"
                .formatted(status.value(), message));
    }
}
