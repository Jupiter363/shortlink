package com.jupiter.shortlink.redirect.web;

import com.jupiter.shortlink.contract.*;
import com.jupiter.shortlink.redirect.cache.RouteResolver;
import com.jupiter.shortlink.redirect.config.RedirectProperties;
import com.jupiter.shortlink.redirect.event.RequestEventPublisher;
import com.jupiter.shortlink.redirect.risk.PolicyResolver;
import com.jupiter.shortlink.redirect.risk.RedisRiskRateLimiter;
import com.jupiter.shortlink.redirect.route.RouteInfo;
import com.jupiter.shortlink.risk.*;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
public final class RedirectController {
    private final RouteResolver routes;
    private final PolicyResolver policies;
    private final RedisRiskRateLimiter rates;
    private final RequestEventPublisher events;
    private final RedirectProperties config;
    private final TrustedProxyResolver proxies;
    private final RiskEvaluator evaluator = new RiskEvaluator();
    private final RiskHash riskHash;
    private final Clock clock;

    public RedirectController(
            RouteResolver routes,
            PolicyResolver policies,
            RedisRiskRateLimiter rates,
            RequestEventPublisher events,
            RedirectProperties config,
            Clock clock) {
        this.routes = routes;
        this.policies = policies;
        this.rates = rates;
        this.events = events;
        this.config = config;
        this.clock = clock;
        proxies = new TrustedProxyResolver(config.trustedProxyCidrs());
        riskHash = new RiskHash(config.riskHashSalt());
    }

    @RequestMapping("/{shortUri}")
    public Mono<Void> redirect(@PathVariable String shortUri, ServerWebExchange exchange) {
        return Mono.defer(
                () -> {
                    Context context = new Context(UUID.randomUUID().toString(), shortUri);
                    try {
                        var request = exchange.getRequest();
                        if (request.getMethod() != HttpMethod.GET
                                && request.getMethod() != HttpMethod.HEAD)
                            return finish(exchange, context, 405, "METHOD_NOT_ALLOWED");
                        if (!shortUri.matches("[A-Za-z0-9]{1,32}")
                                || !request.getURI().getRawPath().equals("/" + shortUri))
                            return finish(exchange, context, 404, "NOT_FOUND");
                        String peer =
                                request.getRemoteAddress() == null
                                        ? null
                                        : request.getRemoteAddress().getAddress().getHostAddress();
                        if (peer == null || !proxies.isTrusted(peer))
                            return finish(exchange, context, 403, "UNTRUSTED_PROXY");
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
                        context.domain =
                                new HostNormalizer().normalize(hosts.get(0), context.scheme);
                        if (!config.allowedHosts().contains(context.domain))
                            return finish(exchange, context, 404, "UNKNOWN_HOST");
                        context.ip =
                                proxies.resolve(
                                        peer, request.getHeaders().getFirst("X-Forwarded-For"));
                        return routes.resolve(context.domain, shortUri)
                                .flatMap(
                                        route -> {
                                            context.route = route;
                                            if (!route.active(clock.millis()))
                                                return finish(
                                                        exchange, context, 404, "LINK_UNAVAILABLE");
                                            return policies.resolve(
                                                            route.tenantId(), route.linkId())
                                                    .flatMap(
                                                            policy -> {
                                                                context.policy = policy;
                                                                RiskDecision decision =
                                                                        evaluator.evaluate(
                                                                                policy,
                                                                                route.resourceKey(),
                                                                                riskHash.hash(
                                                                                        route
                                                                                                .tenantId(),
                                                                                        context.ip),
                                                                                clock.millis());
                                                                return rates.evaluate(
                                                                        route.resourceKey(),
                                                                        decision);
                                                            })
                                                    .flatMap(
                                                            decision -> {
                                                                context.revision =
                                                                        decision.policyRevision();
                                                                if (!decision.allowed())
                                                                    return finish(
                                                                            exchange,
                                                                            context,
                                                                            decision.status(),
                                                                            decision.reason());
                                                                // Recheck route and policy lease at
                                                                // the point of redirect. No I/O may
                                                                // extend a proof.
                                                                long decisionAt = clock.millis();
                                                                if (!route.active(decisionAt)
                                                                        || decisionAt
                                                                                < route
                                                                                        .authorityCheckedAt()
                                                                        || decisionAt
                                                                                >= route
                                                                                        .validUntil())
                                                                    return finish(
                                                                            exchange,
                                                                            context,
                                                                            503,
                                                                            "ROUTE_PROOF_EXPIRED");
                                                                RiskDecision finalDecision =
                                                                        evaluator.evaluate(
                                                                                context.policy,
                                                                                route.resourceKey(),
                                                                                riskHash.hash(
                                                                                        route
                                                                                                .tenantId(),
                                                                                        context.ip),
                                                                                decisionAt);
                                                                if (!finalDecision.allowed())
                                                                    return finish(
                                                                            exchange,
                                                                            context,
                                                                            finalDecision.status(),
                                                                            finalDecision.reason());
                                                                URI location =
                                                                        URI.create(
                                                                                route.originUrl());
                                                                if (!location.isAbsolute()
                                                                        || !(location.getScheme()
                                                                                        .equalsIgnoreCase(
                                                                                                "https")
                                                                                || location.getScheme()
                                                                                        .equalsIgnoreCase(
                                                                                                "http"))
                                                                        || location
                                                                                        .getRawAuthority()
                                                                                == null
                                                                        || location.getUserInfo()
                                                                                != null)
                                                                    return finish(
                                                                            exchange,
                                                                            context,
                                                                            503,
                                                                            "INVALID_TARGET");
                                                                exchange.getResponse()
                                                                        .getHeaders()
                                                                        .setLocation(location);
                                                                exchange.getResponse()
                                                                        .setStatusCode(
                                                                                HttpStatus.FOUND);
                                                                if (exchange.getRequest()
                                                                                .getMethod()
                                                                        == HttpMethod.GET) {
                                                                    String uv =
                                                                            visitor(
                                                                                    exchange,
                                                                                    context.scheme);
                                                                    long occurredAt =
                                                                            clock.millis();
                                                                    ClickEventV1 event =
                                                                            new ClickEventV1(
                                                                                    EventIdentity
                                                                                            .bind(
                                                                                                    occurredAt,
                                                                                                    UUID.randomUUID()
                                                                                                            .toString()),
                                                                                    1,
                                                                                    occurredAt,
                                                                                    config
                                                                                            .instanceId(),
                                                                                    route
                                                                                            .tenantId(),
                                                                                    route.linkId(),
                                                                                    route
                                                                                            .currentGid(),
                                                                                    route
                                                                                            .ownershipVersion(),
                                                                                    route
                                                                                            .domainNorm(),
                                                                                    route
                                                                                            .shortUri(),
                                                                                    route
                                                                                            .routeVersion(),
                                                                                    uv,
                                                                                    context.ip,
                                                                                    trim(
                                                                                            request.getHeaders()
                                                                                                    .getFirst(
                                                                                                            "User-Agent"),
                                                                                            2048),
                                                                                    trim(
                                                                                            request.getHeaders()
                                                                                                    .getFirst(
                                                                                                            "Referer"),
                                                                                            2048),
                                                                                    context.requestId,
                                                                                    context.requestId,
                                                                                    1);
                                                                    events.click(event);
                                                                }
                                                                return finish(
                                                                        exchange,
                                                                        context,
                                                                        302,
                                                                        "REDIRECT");
                                                            });
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

    private String visitor(ServerWebExchange exchange, String scheme) {
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst("sl_uv");
        String value = cookie == null ? null : cookie.getValue();
        if (value != null && value.matches("[a-f0-9]{32}")) return value;
        value = UUID.randomUUID().toString().replace("-", "");
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
        if (exchange.getResponse().isCommitted()) return Mono.empty();
        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(status));
        exchange.getResponse().getHeaders().setCacheControl("no-store, private");
        RouteInfo route = context.route;
        long occurredAt = clock.millis();
        events.result(
                new GatewayRequestEventV1(
                        EventIdentity.bind(occurredAt, UUID.randomUUID().toString()),
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
        String domain, scheme, ip;
        RouteInfo route;
        Long revision;
        PolicySnapshot policy;

        Context(String requestId, String uri) {
            this.requestId = requestId;
            this.uri = uri;
        }
    }
}
