package com.jupiter.shortlink.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.gateway.config.Config;
import com.jupiter.shortlink.gateway.config.GatewaySecurityProperties;
import com.jupiter.shortlink.risk.HostNormalizer;
import com.jupiter.shortlink.risk.TrustedProxyResolver;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

/**
 * Management session boundary: credential lookup is reactive and caller identity headers are
 * discarded.
 */
@Component
public class TokenValidateGatewayFilterFactory extends AbstractGatewayFilterFactory<Config> {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> PUBLIC_ROUTES =
            Set.of(
                    "POST /api/short-link/admin/v1/user",
                    "POST /api/short-link/admin/v1/user/login",
                    "GET /api/short-link/v1/user/has-username");
    private static final Set<String> LEGACY_IDENTITY =
            Set.of(
                    "username",
                    "userid",
                    "realname",
                    "tenantid",
                    "authversion",
                    "accountid",
                    "token",
                    "authorization",
                    "x-internal-token",
                    "forwarded",
                    "x-real-ip",
                    "x-forwarded-host");
    private final ReactiveStringRedisTemplate redis;
    private final GatewaySecurityProperties properties;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public TokenValidateGatewayFilterFactory(
            ReactiveStringRedisTemplate redis, GatewaySecurityProperties properties) {
        this(redis, properties, Clock.systemUTC());
    }

    public TokenValidateGatewayFilterFactory(
            ReactiveStringRedisTemplate redis, GatewaySecurityProperties properties, Clock clock) {
        super(Config.class);
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public GatewayFilter apply(Config ignored) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String path = request.getURI().getRawPath();
            if (!managementPath(path)) return reject(exchange, HttpStatus.NOT_FOUND, "NOT_FOUND");
            try {
                if (properties.getInternalToken().isBlank()
                        || properties.getAllowedHosts().isEmpty())
                    return reject(
                            exchange, HttpStatus.SERVICE_UNAVAILABLE, "GATEWAY_NOT_CONFIGURED");
                String peer =
                        request.getRemoteAddress() == null
                                        || request.getRemoteAddress().getAddress() == null
                                ? null
                                : request.getRemoteAddress().getAddress().getHostAddress();
                TrustedProxyResolver resolver =
                        new TrustedProxyResolver(properties.getTrustedProxyCidrs());
                if (peer == null || !resolver.isTrusted(peer))
                    return reject(exchange, HttpStatus.FORBIDDEN, "UNTRUSTED_PROXY");
                if (request.getHeaders().getOrEmpty(HttpHeaders.HOST).size() != 1)
                    return reject(exchange, HttpStatus.BAD_REQUEST, "INVALID_HOST");
                String scheme = request.getHeaders().getFirst("X-Forwarded-Proto");
                if (!"http".equals(scheme) && !"https".equals(scheme))
                    scheme = request.getURI().getScheme();
                String host =
                        new HostNormalizer()
                                .normalize(request.getHeaders().getFirst(HttpHeaders.HOST), scheme);
                if (!properties.getAllowedHosts().contains(host))
                    return reject(exchange, HttpStatus.NOT_FOUND, "UNKNOWN_HOST");
                String clientIp =
                        resolver.resolve(peer, request.getHeaders().getFirst("X-Forwarded-For"));
                String username = singleHeader(request, "username"),
                        token = singleHeader(request, "token");
                ServerHttpRequest sanitized =
                        request.mutate()
                                .headers(
                                        headers -> {
                                            stripIdentity(headers);
                                            headers.set(
                                                    "X-Internal-Token",
                                                    properties.getInternalToken());
                                            headers.set("X-Forwarded-For", clientIp);
                                        })
                                .build();
                ServerWebExchange trusted = exchange.mutate().request(sanitized).build();
                if (PUBLIC_ROUTES.contains(request.getMethod().name() + " " + path))
                    return chain.filter(trusted);
                if (username == null
                        || username.length() > 64
                        || username.isBlank()
                        || token == null
                        || token.length() < 20
                        || token.length() > 256)
                    return reject(exchange, HttpStatus.UNAUTHORIZED, "INVALID_SESSION");
                return redis.<String, String>opsForHash()
                        .get("login_" + username, token)
                        .timeout(properties.getSessionTimeout())
                        .map(value -> new Lookup(parseSession(value, username), false))
                        .defaultIfEmpty(new Lookup(null, false))
                        .onErrorResume(
                                InvalidSession.class, error -> Mono.just(new Lookup(null, false)))
                        .onErrorResume(error -> Mono.just(new Lookup(null, true)))
                        .flatMap(
                                lookup -> {
                                    if (lookup.unavailable())
                                        return reject(
                                                exchange,
                                                HttpStatus.SERVICE_UNAVAILABLE,
                                                "SESSION_UNAVAILABLE");
                                    if (lookup.session() == null)
                                        return reject(
                                                exchange,
                                                HttpStatus.UNAUTHORIZED,
                                                "INVALID_SESSION");
                                    Session session = lookup.session();
                                    return chain.filter(
                                            trusted.mutate()
                                                    .request(
                                                            sanitized
                                                                    .mutate()
                                                                    .headers(
                                                                            headers -> {
                                                                                headers.set(
                                                                                        "x-shortlink-tenant-id",
                                                                                        session
                                                                                                .tenantId());
                                                                                headers.set(
                                                                                        "x-shortlink-username",
                                                                                        session
                                                                                                .username());
                                                                                headers.set(
                                                                                        "x-shortlink-auth-version",
                                                                                        Long
                                                                                                .toString(
                                                                                                        session
                                                                                                                .authVersion()));
                                                                            })
                                                                    .build())
                                                    .build());
                                });
            } catch (IllegalArgumentException exception) {
                return reject(exchange, HttpStatus.BAD_REQUEST, "INVALID_REQUEST_CONTEXT");
            }
        };
    }

    private Session parseSession(String value, String username) {
        try {
            if (value.length() > 2048) throw new InvalidSession();
            JsonNode node = JSON.readTree(value);
            if (!node.isObject()
                    || node.has("password")
                    || node.has("passwordHash")
                    || node.has("hash")) throw new InvalidSession();
            JsonNode tenant = node.get("tenantId"),
                    version = node.get("authVersion"),
                    expiry = node.get("expiresAt");
            if (tenant == null
                    || !tenant.isIntegralNumber()
                    || !tenant.canConvertToLong()
                    || tenant.longValue() <= 0
                    || version == null
                    || !version.isIntegralNumber()
                    || !version.canConvertToLong()
                    || version.longValue() < 1
                    || expiry == null
                    || !expiry.isIntegralNumber()
                    || !expiry.canConvertToLong()
                    || expiry.longValue() <= clock.millis()
                    || !username.equals(node.path("username").asText())) throw new InvalidSession();
            return new Session(tenant.asText(), username, version.longValue());
        } catch (Exception exception) {
            throw new InvalidSession();
        }
    }

    private static boolean managementPath(String path) {
        if (path == null
                || path.contains("//")
                || path.contains("..")
                || path.contains(";")
                || path.indexOf(92) >= 0
                || path.toLowerCase(Locale.ROOT).matches(".*%(2f|5c|2e|00).*")) return false;
        return path.startsWith("/api/short-link/admin/v1/")
                || path.equals("/api/short-link/v1/user")
                || path.startsWith("/api/short-link/v1/user/");
    }

    private static String singleHeader(ServerHttpRequest request, String name) {
        return request.getHeaders().getOrEmpty(name).size() == 1
                ? request.getHeaders().getFirst(name)
                : null;
    }

    private static void stripIdentity(HttpHeaders headers) {
        for (String name : new ArrayList<>(headers.keySet())) {
            String normalized = name.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("x-shortlink-")
                    || normalized.startsWith("x-agent-")
                    || LEGACY_IDENTITY.contains(normalized)) headers.remove(name);
        }
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String reason) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        byte[] bytes =
                ("{\"success\":false,\"code\":\""
                                + reason
                                + "\",\"status\":"
                                + status.value()
                                + "}")
                        .getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }

    private record Session(String tenantId, String username, long authVersion) {}

    private record Lookup(Session session, boolean unavailable) {}

    private static final class InvalidSession extends RuntimeException {}
}
