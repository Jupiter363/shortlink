package com.jupiter.shortlink.redirect.web;

import com.jupiter.shortlink.contract.*;
import com.jupiter.shortlink.redirect.cache.RouteResolution;
import com.jupiter.shortlink.redirect.cache.RouteResolver;
import com.jupiter.shortlink.redirect.config.RedirectProperties;
import com.jupiter.shortlink.redirect.event.RequestEventPublisher;
import com.jupiter.shortlink.redirect.risk.PolicyResolver;
import com.jupiter.shortlink.redirect.risk.RedisRiskRateLimiter;
import com.jupiter.shortlink.redirect.route.RouteInfo;
import com.jupiter.shortlink.risk.*;

import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

@Component
public final class RedirectController {
    private static final Pattern SHORT_URI = Pattern.compile("[A-Za-z0-9]{1,32}");
    private static final Pattern VISITOR_ID = Pattern.compile("[a-f0-9]{32}");
    private final RouteResolver routes;
    private final PolicyResolver policies;
    private final RedisRiskRateLimiter rates;
    private final RequestEventPublisher events;
    private final RedirectProperties config;
    private final TrustedProxyResolver proxies;
    private final RiskEvaluator evaluator = new RiskEvaluator();
    private final RiskHash riskHash;
    private final HostNormalizer hostNormalizer = new HostNormalizer();
    private final Clock clock;

    public RedirectController(
            RouteResolver routes,
            PolicyResolver policies,
            RedisRiskRateLimiter rates,
            RequestEventPublisher events,
            RedirectProperties config,
            Clock clock) {
        SecureRequestIds.verifyAvailable();
        this.routes = routes;
        this.policies = policies;
        this.rates = rates;
        this.events = events;
        this.config = config;
        this.clock = clock;
        proxies = new TrustedProxyResolver(config.trustedProxyCidrs());
        riskHash = new RiskHash(config.riskHashSalt());
    }

    public Mono<Void> redirect(String shortUri, ServerWebExchange exchange) {
        return Mono.defer(
                () -> {
                    var request = exchange.getRequest();
                    String peer =
                            request.getRemoteAddress() == null
                                            || request.getRemoteAddress().getAddress() == null
                                    ? null
                                    : request.getRemoteAddress().getAddress().getHostAddress();
                    boolean trustedPeer = trustedPeer(peer);
                    Context context =
                            new Context(
                                    GatewayRequestIds.select(
                                            trustedPeer,
                                            request.getHeaders().get(GatewayRequestIds.HEADER)),
                                    shortUri);
                    exchange.getResponse()
                            .getHeaders()
                            .set(GatewayRequestIds.HEADER, context.requestId);
                    try {
                        if (request.getMethod() != HttpMethod.GET
                                && request.getMethod() != HttpMethod.HEAD)
                            return finish(exchange, context, 405, "METHOD_NOT_ALLOWED");
                        if (!SHORT_URI.matcher(shortUri).matches()
                                || !request.getURI().getRawPath().equals("/" + shortUri))
                            return finish(exchange, context, 404, "NOT_FOUND");
                        if (!trustedPeer) return finish(exchange, context, 403, "UNTRUSTED_PROXY");
                        List<String> hosts = request.getHeaders().get(HttpHeaders.HOST);
                        if (hosts == null || hosts.size() != 1)
                            return finish(exchange, context, 400, "INVALID_HOST");
                        List<String> schemes = request.getHeaders().get("X-Forwarded-Proto");
                        if (schemes == null
                                || schemes.size() != 1
                                || !(schemes.get(0).equals("https")
                                        || schemes.get(0).equals("http")))
                            return finish(exchange, context, 400, "INVALID_FORWARDED_SCHEME");
                        context.scheme = schemes.get(0);
                        context.domain = hostNormalizer.normalize(hosts.get(0), context.scheme);
                        if (!config.allowedHosts().contains(context.domain))
                            return finish(exchange, context, 404, "UNKNOWN_HOST");
                        context.ip =
                                proxies.resolve(
                                        peer, request.getHeaders().getFirst("X-Forwarded-For"));
                        return routes.resolveGuarded(context.domain, shortUri)
                                .flatMap(
                                        resolution -> {
                                            if (resolution instanceof RouteResolution.Absent absent)
                                                return finishAbsent(exchange, context, absent);
                                            return respondRoute(
                                                    exchange,
                                                    context,
                                                    ((RouteResolution.Route) resolution).value());
                                        })
                                .timeout(Duration.ofMillis(config.requestTimeoutMillis()))
                                .onErrorResume(
                                        error ->
                                                finish(
                                                        exchange,
                                                        context,
                                                        503,
                                                        "AUTHORITY_UNAVAILABLE"));
                    } catch (IllegalArgumentException invalid) {
                        return finish(exchange, context, 400, "INVALID_REQUEST");
                    }
                });
    }

    private Mono<Void> respondRoute(ServerWebExchange exchange, Context context, RouteInfo route) {
        var request = exchange.getRequest();
        context.route = route;
        if (!route.active(clock.millis()))
            return finish(exchange, context, 404, "LINK_UNAVAILABLE");
        return policies.resolve(route.tenantId(), route.linkId())
                .flatMap(
                        policy -> {
                            context.policy = policy;
                            context.ipHash = riskHash.hash(route.tenantId(), context.ip);
                            RiskDecision decision =
                                    evaluator.evaluate(
                                            policy,
                                            route.resourceKey(),
                                            context.ipHash,
                                            clock.millis());
                            return rates.evaluate(route.resourceKey(), decision);
                        })
                .flatMap(
                        decision -> {
                            context.revision = decision.policyRevision();
                            if (!decision.allowed())
                                return finish(
                                        exchange, context, decision.status(), decision.reason());
                            // Recheck route and policy lease at
                            // the point of redirect. No I/O may
                            // extend a proof.
                            long decisionAt = clock.millis();
                            if (!route.active(decisionAt)
                                    || decisionAt < route.authorityCheckedAt()
                                    || decisionAt >= route.validUntil())
                                return finish(exchange, context, 503, "ROUTE_PROOF_EXPIRED");
                            RiskDecision finalDecision =
                                    evaluator.evaluate(
                                            context.policy,
                                            route.resourceKey(),
                                            context.ipHash,
                                            decisionAt);
                            if (!finalDecision.allowed())
                                return finish(
                                        exchange,
                                        context,
                                        finalDecision.status(),
                                        finalDecision.reason());
                            URI location = URI.create(route.originUrl());
                            if (!location.isAbsolute()
                                    || !(location.getScheme().equalsIgnoreCase("https")
                                            || location.getScheme().equalsIgnoreCase("http"))
                                    || location.getRawAuthority() == null
                                    || location.getUserInfo() != null)
                                return finish(exchange, context, 503, "INVALID_TARGET");
                            exchange.getResponse().getHeaders().setLocation(location);
                            exchange.getResponse().setStatusCode(HttpStatus.FOUND);
                            if (exchange.getRequest().getMethod() == HttpMethod.GET) {
                                String uv = visitor(exchange, context.scheme);
                                long occurredAt = clock.millis();
                                ClickEventV1 event =
                                        new ClickEventV1(
                                                EventIdentity.bind(
                                                        occurredAt,
                                                        SecureRequestIds.randomUuid().toString()),
                                                1,
                                                occurredAt,
                                                config.instanceId(),
                                                route.tenantId(),
                                                route.linkId(),
                                                route.currentGid(),
                                                route.ownershipVersion(),
                                                route.domainNorm(),
                                                route.shortUri(),
                                                route.routeVersion(),
                                                uv,
                                                context.ip,
                                                trim(
                                                        request.getHeaders().getFirst("User-Agent"),
                                                        2048),
                                                trim(
                                                        request.getHeaders().getFirst("Referer"),
                                                        2048),
                                                context.requestId,
                                                context.requestId,
                                                1);
                                events.click(event);
                            }
                            return finish(exchange, context, 302, "REDIRECT");
                        });
    }

    private boolean trustedPeer(String peer) {
        if (peer == null) return false;
        try {
            return proxies.isTrusted(peer);
        } catch (IllegalArgumentException invalidPeer) {
            return false;
        }
    }

    private String visitor(ServerWebExchange exchange, String scheme) {
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst("sl_uv");
        String value = cookie == null ? null : cookie.getValue();
        if (value != null && VISITOR_ID.matcher(value).matches()) return value;
        value = SecureRequestIds.randomUuid().toString().replace("-", "");
        exchange.getResponse()
                .addCookie(
                        ResponseCookie.from("sl_uv", value)
                                .httpOnly(true)
                                .secure("https".equals(scheme))
                                .sameSite("Lax")
                                .path("/")
                                .maxAge(Duration.ofDays(30))
                                .build());
        return value;
    }

    private Mono<Void> finish(
            ServerWebExchange exchange, Context context, int status, String reason) {
        return finish(exchange, context, status, reason, true);
    }

    private Mono<Void> finish(
            ServerWebExchange exchange,
            Context context,
            int status,
            String reason,
            boolean record) {
        if (exchange.getResponse().isCommitted()) return Mono.empty();
        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(status));
        exchange.getResponse().getHeaders().setCacheControl("no-store, private");
        if (record) recordResult(exchange, context, status, reason);
        if (status == 404
                && exchange.getRequest().getMethod() != HttpMethod.HEAD
                && wantsHtml(exchange)) {
            exchange.getResponse()
                    .getHeaders()
                    .setContentType(
                            new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8));
            exchange.getResponse()
                    .getHeaders()
                    .set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
            exchange.getResponse().getHeaders().set("X-Content-Type-Options", "nosniff");
            byte[] html =
                    "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>短链接不可用</title><body><h1>短链接不可用</h1><p>此链接不存在、已过期或已停用。请联系链接提供者获取新的地址。</p></body></html>"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return exchange.getResponse()
                    .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(html)));
        }
        return exchange.getResponse().setComplete();
    }

    private Mono<Void> finishAbsent(
            ServerWebExchange exchange, Context context, RouteResolution.Absent absent) {
        return Mono.defer(
                () -> {
                    exchange.getResponse()
                            .beforeCommit(
                                    () ->
                                            Mono.fromRunnable(
                                                    () -> {
                                                        // Previous commit hooks have completed.
                                                        // This is the final denial decision.
                                                        // Failed commit actions leave Spring's
                                                        // response mutable for one fallback.
                                                        if (!routes.validAbsent(absent))
                                                            throw new MembershipProofExpired();
                                                        recordResult(
                                                                exchange,
                                                                context,
                                                                404,
                                                                "NOT_FOUND");
                                                    }));
                    return finish(exchange, context, 404, "NOT_FOUND", false)
                            .onErrorResume(
                                    MembershipProofExpired.class,
                                    expired -> {
                                        exchange.getResponse()
                                                .getHeaders()
                                                .remove("Content-Security-Policy");
                                        exchange.getResponse()
                                                .getHeaders()
                                                .remove("X-Content-Type-Options");
                                        // This bypasses Bloom once, inside the original
                                        // request-wide timeout.
                                        return routes.resolve(context.domain, context.uri)
                                                .flatMap(
                                                        route ->
                                                                respondRoute(
                                                                        exchange, context, route));
                                    });
                });
    }

    private static final class MembershipProofExpired extends RuntimeException {
        MembershipProofExpired() {
            super("Membership denial proof expired", null, false, false);
        }
    }

    private void recordResult(
            ServerWebExchange exchange, Context context, int status, String reason) {
        RouteInfo route = context.route;
        long occurredAt = clock.millis();
        events.result(
                new GatewayRequestEventV1(
                        EventIdentity.bind(occurredAt, SecureRequestIds.randomUuid().toString()),
                        1,
                        occurredAt,
                        config.instanceId(),
                        RequestSource.REDIRECT,
                        DecisionStage.BUSINESS,
                        exchange.getRequest().getMethod().name(),
                        status,
                        reason,
                        route == null ? null : route.tenantId(),
                        route == null || route.linkId() == 0 ? null : route.linkId(),
                        context.domain,
                        context.uri,
                        context.revision,
                        context.requestId,
                        context.requestId));
    }

    private boolean wantsHtml(ServerWebExchange exchange) {
        try {
            return exchange.getRequest().getHeaders().getAccept().stream()
                    .noneMatch(
                            type ->
                                    type.getType().equals("application")
                                            && (type.getSubtype().equals("json")
                                                    || type.getSubtype().endsWith("+json")));
        } catch (IllegalArgumentException invalidAccept) {
            return false;
        }
    }

    private static String trim(String value, int limit) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), limit));
    }

    private static final class Context {
        final String requestId, uri;
        String domain, scheme, ip, ipHash;
        RouteInfo route;
        Long revision;
        PolicySnapshot policy;

        Context(String requestId, String uri) {
            this.requestId = requestId;
            this.uri = uri;
        }
    }
}
