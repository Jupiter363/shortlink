package com.jupiter.shortlink.admin.common.biz.user;

import com.jupiter.shortlink.admin.account.AccountSession;
import com.jupiter.shortlink.admin.account.AccountSessionStore;
import com.jupiter.shortlink.risk.HostNormalizer;
import com.jupiter.shortlink.risk.IpAddresses;
import com.jupiter.shortlink.risk.TrustedProxyResolver;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.Callable;

/** APISIX peer, server-side session, then current MySQL authority; no caller-issued identity. */
public class UserTransmitFilter implements Filter {
    private static final String VERIFIED = UserTransmitFilter.class.getName() + ".verified";
    private static final String LOGOUT = "/api/short-link/admin/v1/user/logout";
    private static final String INTERNAL = "/internal/short-link-admin/v1/agent-tools/";
    private static final Set<String> PUBLIC = Set.of(
            "POST /api/short-link/admin/v1/user/login", "POST /api/short-link/admin/v1/user",
            "GET /api/short-link/v1/user/has-username");
    private static final Set<String> MANAGEMENT = Set.of("/actuator/health",
            "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/info", "/actuator/prometheus");
    private static final Set<String> STRIP = Set.of("username", "userid", "realname", "tenantid",
            "authversion", "accountid", "token", "authorization", "x-internal-token",
            "forwarded", "x-real-ip", "x-forwarded-host", "x-forwarded-for", "x-forwarded-proto");
    private final TrustedManagementIdentity identities;
    private final AccountSessionStore sessions;
    private final TrustedProxyResolver proxies;
    private final HostNormalizer hosts = new HostNormalizer();
    private final Set<String> allowedHosts;
    private final int managementPort;
    private final Clock clock;

    public UserTransmitFilter(TrustedManagementIdentity identities, AccountSessionStore sessions,
            AdminIngressProperties properties, int managementPort) {
        this(identities, sessions, properties, managementPort, Clock.systemUTC());
    }
    UserTransmitFilter(TrustedManagementIdentity identities, AccountSessionStore sessions,
            AdminIngressProperties properties, int managementPort, Clock clock) {
        this.identities = Objects.requireNonNull(identities);
        this.sessions = Objects.requireNonNull(sessions);
        this.proxies = new TrustedProxyResolver(properties.getTrustedProxyCidrs());
        this.allowedHosts = Set.copyOf(properties.getAllowedHosts());
        this.managementPort = managementPort;
        this.clock = clock;
        for (String host : allowedHosts)
            if (!hosts.normalize(host, "https").equals(host))
                throw new IllegalArgumentException("Management allowed hosts must be canonical");
    }

    @Override
    public void doFilter(ServletRequest raw, ServletResponse output, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) raw;
        HttpServletResponse response = (HttpServletResponse) output;
        UserContext.removeUser();
        try {
            String path = request.getRequestURI();
            if (!request.getContextPath().isEmpty() || !safePath(path)) {
                reject(response, 400, "INVALID_REQUEST_PATH"); return;
            }
            if (path.startsWith(INTERNAL)) {
                chain.doFilter(request, response); return; // Independent Agent filter owns this boundary.
            }
            if ("GET".equals(request.getMethod()) && MANAGEMENT.contains(path)) {
                if (managementPort > 0 && request.getLocalPort() == managementPort
                        && loopback(request.getRemoteAddr()))
                    chain.doFilter(request, response);
                else reject(response, 403, "MANAGEMENT_PORT_REQUIRED");
                return;
            }
            if (!managementPath(path)) { reject(response, 404, "NOT_FOUND"); return; }
            if (request.getDispatcherType() == DispatcherType.ASYNC) {
                if (!(request.getAttribute(VERIFIED) instanceof Verified verified)
                        || !verified.path().equals(path) || !verified.method().equals(request.getMethod())) {
                    reject(response, 401, "UNAUTHENTICATED_DISPATCH"); return;
                }
                enter(request, response, chain, verified); return;
            }
            if (allowedHosts.isEmpty()) { reject(response, 503, "INGRESS_NOT_CONFIGURED"); return; }
            try {
                if (!proxies.isTrusted(request.getRemoteAddr())) { reject(response, 403, "UNTRUSTED_PROXY"); return; }
            } catch (IllegalArgumentException invalidPeer) {
                reject(response, 403, "UNTRUSTED_PROXY"); return;
            }
            if (!single(request, "Host", true) || !single(request, "X-Forwarded-Proto", false)
                    || !single(request, "X-Forwarded-For", false) || duplicateIdentity(request)) {
                reject(response, 400, "DUPLICATE_REQUEST_CONTEXT"); return;
            }
            String scheme = request.getHeader("X-Forwarded-Proto");
            if (scheme == null) scheme = request.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                reject(response, 400, "INVALID_REQUEST_CONTEXT"); return;
            }
            String clientIp;
            try {
                if (!allowedHosts.contains(hosts.normalize(request.getHeader("Host"), scheme))) {
                    reject(response, 404, "UNKNOWN_HOST"); return;
                }
                clientIp = proxies.resolve(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"));
            } catch (IllegalArgumentException invalidContext) {
                reject(response, 400, "INVALID_REQUEST_CONTEXT"); return;
            }
            String username = request.getHeader("username"), token = request.getHeader("token");
            Verified verified;
            if (PUBLIC.contains(request.getMethod() + " " + path)) {
                verified = new Verified(path, request.getMethod(), clientIp, scheme, null, null, null);
            } else {
                if (username == null || !username.matches("[A-Za-z0-9_-]{3,64}")
                        || token == null || !token.matches("[A-Za-z0-9_-]{20,256}")) {
                    reject(response, 401, "INVALID_SESSION"); return;
                }
                AccountSession session;
                UserInfoDTO principal;
                try {
                    session = sessions.find(username, token);
                    if (session == null || session.expiresAt() <= clock.millis()
                            || !username.equals(session.username())) {
                        reject(response, 401, "INVALID_SESSION"); return;
                    }
                    principal = identities.verify(Long.toString(session.tenantId()),
                            session.username(), Long.toString(session.authVersion()));
                } catch (IllegalArgumentException invalid) {
                    reject(response, 401, "INVALID_SESSION"); return;
                } catch (RuntimeException unavailable) {
                    com.jupiter.shortlink.admin.config.AdminDependencyDiagnostics.identityUnavailable(unavailable);
                    reject(response, 503, "SESSION_OR_AUTHORITY_UNAVAILABLE"); return;
                }
                if (isSessionOperation(request.getMethod(), path)
                        && !sessionArgumentsMatch(request, username, token)) {
                    reject(response, 401, "SESSION_ARGUMENT_MISMATCH"); return;
                }
                verified = new Verified(path, request.getMethod(), clientIp, scheme, session, token, principal);
            }
            request.setAttribute(VERIFIED, verified);
            if (verified.principal() != null)
                WebAsyncUtils.getAsyncManager(request).registerCallableInterceptor(VERIFIED,
                        new CallableProcessingInterceptor() {
                            @Override public <T> void preProcess(NativeWebRequest r, Callable<T> task) {
                                UserContext.setUser(verified.principal());
                            }
                            @Override public <T> void postProcess(NativeWebRequest r, Callable<T> task, Object result) {
                                UserContext.removeUser();
                            }
                        });
            enter(request, response, chain, verified);
        } finally {
            UserContext.removeUser();
        }
    }

    private void enter(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
            Verified verified) throws IOException, ServletException {
        if (verified.principal() != null) UserContext.setUser(verified.principal());
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public String getHeader(String name) {
                if ("x-forwarded-for".equalsIgnoreCase(name)) return verified.clientIp();
                if ("x-forwarded-proto".equalsIgnoreCase(name)) return verified.scheme();
                return stripped(name) ? null : super.getHeader(name);
            }
            @Override public Enumeration<String> getHeaders(String name) {
                if ("x-forwarded-for".equalsIgnoreCase(name)) return Collections.enumeration(List.of(verified.clientIp()));
                if ("x-forwarded-proto".equalsIgnoreCase(name)) return Collections.enumeration(List.of(verified.scheme()));
                return stripped(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
            }
            @Override public Enumeration<String> getHeaderNames() {
                List<String> names = Collections.list(super.getHeaderNames());
                names.removeIf(UserTransmitFilter::stripped);
                names.add("X-Forwarded-For"); names.add("X-Forwarded-Proto");
                return Collections.enumeration(names);
            }
        }, response);
    }
    public static boolean verifiedSessionMatches(HttpServletRequest request, String username, String token) {
        return request.getAttribute(VERIFIED) instanceof Verified v && v.session() != null
                && v.session().username().equals(username) && v.token().equals(token);
    }
    /** Credentials come only from the ingress proof, never from caller-controlled headers or parameters. */
    public static VerifiedSessionCredentials requireVerifiedLogoutSession(HttpServletRequest request) {
        if (!(request.getAttribute(VERIFIED) instanceof Verified v)
                || v.session() == null || v.principal() == null
                || !"DELETE".equals(v.method()) || !LOGOUT.equals(v.path())
                || !v.method().equals(request.getMethod()) || !v.path().equals(request.getRequestURI())
                || !sessionArgumentsMatch(request, v.session().username(), v.token())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Verified logout session required");
        }
        return new VerifiedSessionCredentials(v.session().username(), v.token());
    }
    public record VerifiedSessionCredentials(String username, String token) {
        @Override public String toString() { return "VerifiedSessionCredentials[redacted]"; }
    }
    private static boolean stripped(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("x-shortlink-") || n.startsWith("x-agent-") || STRIP.contains(n);
    }
    private static boolean duplicateIdentity(HttpServletRequest request) {
        for (String name : Collections.list(request.getHeaderNames()))
            if (stripped(name) && !single(request, name, false)) return true;
        return false;
    }
    private static boolean single(HttpServletRequest request, String name, boolean required) {
        int count = Collections.list(request.getHeaders(name)).size();
        return count == 1 || (!required && count == 0);
    }
    private static boolean singleParameter(HttpServletRequest request, String name, String expected) {
        String[] values = request.getParameterValues(name);
        return values != null && values.length == 1 && expected.equals(values[0]);
    }
    private static boolean isSessionOperation(String method, String path) {
        return (method.equals("GET") && path.equals("/api/short-link/v1/user/check-login"))
                || (method.equals("DELETE") && path.equals(LOGOUT));
    }
    private static boolean sessionArgumentsMatch(HttpServletRequest request, String username, String token) {
        // New clients keep the token out of URLs. Legacy credentials must be complete, unique and exact.
        if ("DELETE".equals(request.getMethod()) && LOGOUT.equals(request.getRequestURI())
                && request.getParameterValues("username") == null
                && request.getParameterValues("token") == null) return true;
        return singleParameter(request, "username", username) && singleParameter(request, "token", token);
    }
    private static boolean safePath(String path) {
        return path != null && path.startsWith("/") && !path.contains("//") && !path.contains("..")
                && !path.contains(";") && path.indexOf('\\') < 0 && path.indexOf('%') < 0;
    }
    private static boolean loopback(String value) {
        try { return IpAddresses.parse(value).isLoopbackAddress(); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private static boolean managementPath(String path) {
        return path.startsWith("/api/short-link/admin/v1/") || path.equals("/api/short-link/v1/user")
                || path.startsWith("/api/short-link/v1/user/");
    }
    private static void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"success\":false,\"code\":\"" + code + "\",\"status\":" + status + "}");
    }
    private record Verified(String path, String method, String clientIp, String scheme,
                            AccountSession session, String token, UserInfoDTO principal) {}
}
