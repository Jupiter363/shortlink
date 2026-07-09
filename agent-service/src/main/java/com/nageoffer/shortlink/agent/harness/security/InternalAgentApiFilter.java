package com.nageoffer.shortlink.agent.harness.security;

import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class InternalAgentApiFilter extends OncePerRequestFilter {

    public static final String INTERNAL_TOKEN_HEADER = "X-Agent-Internal-Token";

    private static final String INTERNAL_API_PREFIX = "/internal/short-link-agent/v1/";

    private final AgentProperties agentProperties;

    public InternalAgentApiFilter(AgentProperties agentProperties) {
        this.agentProperties = agentProperties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!isInternalAgentApi(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        String internalToken = agentProperties.getSecurity().getInternalToken();
        if (!StringUtils.hasText(internalToken) && !agentProperties.getSecurity().isInternalTokenDevMode()) {
            writeError(response, HttpStatus.UNAUTHORIZED, "Internal token is not configured");
            return;
        }
        if (!StringUtils.hasText(internalToken)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!internalToken.equals(request.getHeader(INTERNAL_TOKEN_HEADER))) {
            writeError(response, HttpStatus.UNAUTHORIZED, "Invalid internal token");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isInternalAgentApi(HttpServletRequest request) {
        String requestPath = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && requestPath.startsWith(contextPath)) {
            requestPath = requestPath.substring(contextPath.length());
        }
        return requestPath.startsWith(INTERNAL_API_PREFIX);
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"success\":false,\"code\":\"%s\",\"message\":\"%s\"}"
                .formatted(status.value(), message));
    }
}
