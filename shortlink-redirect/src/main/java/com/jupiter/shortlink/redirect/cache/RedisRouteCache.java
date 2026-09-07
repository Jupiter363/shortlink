package com.jupiter.shortlink.redirect.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.redirect.route.RouteInfo;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/** Each atomic operation is confined to one Redis Cluster hash slot. */
public final class RedisRouteCache {
    private static final String COMPARE =
            "local function less(a,b) return #a<#b or (#a==#b and a<b) end; ";
    private static final RedisScript<String> READ =
            RedisScript.of(
                    "if redis.call('GET',KEYS[3])~=ARGV[1] then return nil end; return"
                        + " redis.call('GET',KEYS[1])",
                    String.class);
    private static final RedisScript<Long> PUT =
            RedisScript.of(
                    COMPARE
                            + "local g=redis.call('GET',KEYS[3]); if g and g~=ARGV[1] then return 0"
                            + " end; local v=redis.call('GET',KEYS[2]); if v and less(ARGV[2],v)"
                            + " then return 0 end; redis.call('SET',KEYS[3],ARGV[1],'PX',ARGV[5]);"
                            + " redis.call('SET',KEYS[2],ARGV[2],'PX',ARGV[5]);"
                            + " redis.call('SET',KEYS[1],ARGV[3],'PX',ARGV[4]); return 1",
                    Long.class);
    private static final RedisScript<Long> INVALIDATE =
            RedisScript.of(
                    COMPARE
                            + "local v=redis.call('GET',KEYS[2]); if not v or less(v,ARGV[2]) then"
                            + " redis.call('SET',KEYS[2],ARGV[2],'PX',ARGV[3]);"
                            + " redis.call('DEL',KEYS[1]); end;"
                            + " redis.call('SET',KEYS[3],ARGV[1],'PX',ARGV[3]); return 1",
                    Long.class);
    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration timeout;
    private final long guardMillis;

    public RedisRouteCache(
            ReactiveStringRedisTemplate redis,
            ObjectMapper json,
            Clock clock,
            long timeoutMillis,
            long guardMillis) {
        this.redis = redis;
        this.json = json;
        this.clock = clock;
        this.timeout = Duration.ofMillis(timeoutMillis);
        this.guardMillis = guardMillis;
    }

    public Mono<RouteInfo> get(String domain, String uri, long generation) {
        return redis.execute(
                        READ, keys(domain, uri, generation), List.of(Long.toString(generation)))
                .next()
                .map(body -> decode(body))
                .filter(
                        route ->
                                route.fresh(clock.millis(), generation)
                                        && route.domainNorm().equals(domain)
                                        && route.shortUri().equals(uri))
                .timeout(timeout);
    }

    public Mono<Boolean> put(RouteInfo route) {
        return Mono.defer(
                () -> {
                    long ttl = route.validUntil() - clock.millis();
                    if (ttl <= 0) return Mono.just(false);
                    return redis.execute(
                                    PUT,
                                    keys(
                                            route.domainNorm(),
                                            route.shortUri(),
                                            route.cacheGeneration()),
                                    List.of(
                                            Long.toString(route.cacheGeneration()),
                                            Long.toString(route.routeVersion()),
                                            encode(route),
                                            Long.toString(ttl),
                                            Long.toString(guardMillis)))
                            .next()
                            .map(value -> value == 1)
                            .timeout(timeout);
                });
    }

    public Mono<Void> invalidate(String domain, String uri, long generation, long minimumVersion) {
        return redis.execute(
                        INVALIDATE,
                        keys(domain, uri, generation),
                        List.of(
                                Long.toString(generation),
                                Long.toString(minimumVersion),
                                Long.toString(guardMillis)))
                .then()
                .timeout(timeout);
    }

    static List<String> keys(String domain, String uri, long generation) {
        try {
            String hash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    (domain + "/" + uri)
                                                            .getBytes(StandardCharsets.UTF_8)));
            String base = "sl:r:v1:g" + generation + ":{" + hash + "}:";
            return List.of(base + "value", base + "version", base + "guard");
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private String encode(RouteInfo route) {
        try {
            return json.writeValueAsString(route);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private RouteInfo decode(String body) {
        try {
            return json.readValue(body, RouteInfo.class);
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid cache payload", error);
        }
    }
}
