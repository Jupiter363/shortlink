package com.jupiter.shortlink.admin.common.biz.user;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.Semaphore;

/** Register before session authentication, with REQUEST/ASYNC/ERROR and asyncSupported=true. */
public final class AdminAdmissionFilter implements Filter {
    private static final String HEALTH = AdminAdmissionFilter.class.getName() + ".health";
    private static final Set<String> HEALTH_PATHS = Set.of(
            "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness");
    private final Semaphore requests;

    public AdminAdmissionFilter(RequestBudgetProperties properties) {
        properties.validate();
        requests = new Semaphore(properties.getMaxInFlight());
    }

    @Override public void doFilter(ServletRequest raw, ServletResponse output, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) raw;
        HttpServletResponse response = (HttpServletResponse) output;
        if (Boolean.TRUE.equals(request.getAttribute(HEALTH))) {
            chain.doFilter(request, response);
            return;
        }
        AsyncBudgetLease lease = (AsyncBudgetLease) request.getAttribute(AsyncBudgetLease.ATTRIBUTE);
        if (lease == null) {
            if (request.getDispatcherType() == DispatcherType.REQUEST && isHealth(request)) {
                request.setAttribute(HEALTH, true);
                chain.doFilter(request, response);
                return;
            }
            if (!requests.tryAcquire()) {
                reject(response, 429);
                return;
            }
            lease = new AsyncBudgetLease(requests::release);
            request.setAttribute(AsyncBudgetLease.ATTRIBUTE, lease);
        }
        if (!lease.enterDispatch()) return;
        HttpServletRequest wrapped = lease.wrap(request, response);
        try {
            chain.doFilter(wrapped, response);
        } catch (IOException | ServletException | RuntimeException | Error failure) {
            lease.finish();
            try {
                if (wrapped.isAsyncStarted()) wrapped.getAsyncContext().complete();
            } catch (IllegalStateException alreadyCompleted) { /* Preserve the original failure. */ }
            throw failure;
        } finally {
            lease.leaveDispatch(wrapped);
        }
    }

    static boolean isHealth(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) && HEALTH_PATHS.contains(request.getRequestURI());
    }

    static void reject(HttpServletResponse response, int status) {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Connection", "close");
        if (status == 429) response.setHeader("Retry-After", "1");
        response.setContentLength(0);
    }

    int availableRequests() { return requests.availablePermits(); }
}
