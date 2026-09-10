package com.jupiter.shortlink.command.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/** Reject unauthenticated callers before allocating or parsing a command body. */
@Component
@Order(0)
public class InternalServiceFilter extends OncePerRequestFilter {
    private final CommandAuthorization authorization;

    public InternalServiceFilter(CommandAuthorization authorization) {
        this.authorization = authorization;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request.getMethod().equals("GET") && request.getRequestURI().startsWith("/actuator/")) {
            chain.doFilter(request, response);
            return;
        }
        try {
            authorization.requireService(request);
        } catch (ResponseStatusException rejected) {
            response.sendError(rejected.getStatusCode().value());
            return;
        }
        chain.doFilter(request, response);
    }
}
