package com.jupiter.shortlink.admin.common.biz.user;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/** Identity requires an authenticated internal caller and the current account version. */
public class UserTransmitFilter implements Filter {
    private static final Set<String> PUBLIC =
            Set.of(
                    "POST /api/short-link/admin/v1/user/login",
                    "POST /api/short-link/admin/v1/user",
                    "GET /api/short-link/v1/user/has-username",
                    "GET /actuator/health",
                    "GET /actuator/health/liveness");
    private final TrustedManagementIdentity identities;
    private final String internalToken;

    public UserTransmitFilter(TrustedManagementIdentity identities, String internalToken) {
        this.identities = identities;
        this.internalToken = internalToken;
    }

    @Override
    public void doFilter(ServletRequest raw, ServletResponse output, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) raw;
        HttpServletResponse response = (HttpServletResponse) output;
        UserContext.removeUser();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/internal/short-link-admin/v1/agent-tools/")
                || PUBLIC.contains(request.getMethod() + " " + path)) {
            chain.doFilter(raw, output);
            return;
        }
        String supplied = request.getHeader("X-Internal-Token");
        if (internalToken == null
                || internalToken.length() < 32
                || supplied == null
                || !MessageDigest.isEqual(
                        internalToken.getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(401);
            return;
        }
        try {
            UserContext.setUser(
                    identities.verify(
                            request.getHeader("x-shortlink-tenant-id"),
                            request.getHeader("x-shortlink-username"),
                            request.getHeader("x-shortlink-auth-version")));
        } catch (IllegalArgumentException rejected) {
            response.sendError(401);
            return;
        } catch (RuntimeException unavailable) {
            com.jupiter.shortlink.admin.config.AdminDependencyDiagnostics.identityUnavailable(unavailable);
            response.sendError(503);
            return;
        }
        try {
            chain.doFilter(raw, output);
        } finally {
            UserContext.removeUser();
        }
    }
}
