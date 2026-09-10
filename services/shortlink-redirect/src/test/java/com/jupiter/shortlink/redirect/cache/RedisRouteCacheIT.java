package com.jupiter.shortlink.redirect.cache;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.redirect.risk.RedisRiskRateLimiter;
import com.jupiter.shortlink.redirect.route.RouteInfo;
import com.jupiter.shortlink.risk.*;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.*;
import java.util.*;

class RedisRouteCacheIT {
    static LettuceConnectionFactory connection;
    static ReactiveStringRedisTemplate redis;
    final String suffix = UUID.randomUUID().toString().replace("-", "");
    final MutableClock clock = new MutableClock(System.currentTimeMillis());

    @BeforeAll
    static void connect() {
        String port = System.getenv("SHORTLINK_REDIS_TEST_PORT");
        assertNotNull(port, "Explicit isolated Redis port required");
        connection = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(port));
        connection.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(connection);
        assertEquals(
                "PONG",
                redis.getConnectionFactory()
                        .getReactiveConnection()
                        .ping()
                        .block(Duration.ofSeconds(3)));
    }

    @AfterAll
    static void close() {
        if (connection != null) connection.destroy();
    }

    RouteInfo route(long version, long generation) {
        return new RouteInfo(
                "it.example",
                suffix,
                1,
                "1",
                "g",
                "https://target.example",
                "ACTIVE",
                null,
                version,
                1,
                clock.millis(),
                clock.millis() + 1000,
                generation);
    }

    @Test
    void originalAgeAndGenerationStayFixedAcrossRedisReads() {
        RedisRouteCache cache = new RedisRouteCache(redis, new ObjectMapper(), clock, 1000, 30000);
        RouteInfo route = route(1, 31);
        try {
            assertTrue(cache.put(route).block());
            clock.value += 900;
            assertEquals(
                    route.authorityCheckedAt(),
                    cache.get("it.example", suffix, 31).block().authorityCheckedAt());
            assertNull(cache.get("it.example", suffix, 32).block());
            clock.value += 100;
            assertNull(cache.get("it.example", suffix, 31).block());
        } finally {
            redis.delete(RedisRouteCache.keys("it.example", suffix, 31).toArray(String[]::new))
                    .block();
        }
    }

    @Test
    void outOfOrderHintsAndLateWritesCannotResurrectPriorVersionBeyondDoublePrecision() {
        RedisRouteCache cache = new RedisRouteCache(redis, new ObjectMapper(), clock, 1000, 30000);
        long floor = 9007199254740995L;
        try {
            assertTrue(cache.put(route(floor - 1, 51)).block());
            cache.invalidate("it.example", suffix, 51, floor).block();
            cache.invalidate("it.example", suffix, 51, floor - 2).block();
            assertFalse(cache.put(route(floor - 1, 51)).block());
            assertNull(cache.get("it.example", suffix, 51).block());
            assertTrue(cache.put(route(floor, 51)).block());
            List<String> keys = RedisRouteCache.keys("it.example", suffix, 51);
            assertTrue(redis.getExpire(keys.get(0)).block().toMillis() > 0);
            String tag =
                    keys.get(0).substring(keys.get(0).indexOf('{'), keys.get(0).indexOf('}') + 1);
            assertTrue(keys.stream().allMatch(key -> key.contains(tag)));
        } finally {
            redis.delete(RedisRouteCache.keys("it.example", suffix, 51).toArray(String[]::new))
                    .block();
        }
    }

    @Test
    void liveRedisNearExpiryProofIsRefreshedBeforeEnteringTheRequestPipeline() {
        RedisRouteCache cache = new RedisRouteCache(redis, new ObjectMapper(), clock, 1000, 30000);
        RouteInfo old = route(1, 71);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var budgets = new java.util.concurrent.atomic.AtomicInteger();
        CacheGeneration generation =
                new CacheGeneration() {
                    public long current() {
                        return 71;
                    }

                    public reactor.core.publisher.Mono<Long> refresh() {
                        return reactor.core.publisher.Mono.just(71L);
                    }
                };
        RouteResolver resolver =
                new RouteResolver(
                        cache,
                        (domain, uri, epoch) -> {
                            reads.incrementAndGet();
                            return reactor.core.publisher.Mono.just(route(2, epoch));
                        },
                        generation,
                        () -> reactor.core.publisher.Mono.fromRunnable(budgets::incrementAndGet),
                        clock,
                        10,
                        2,
                        1000,
                        30000,
                        500);
        try {
            assertTrue(cache.put(old).block());
            clock.value += 999;
            RouteInfo refreshed = resolver.resolve("it.example", suffix).block();
            assertEquals(2, refreshed.routeVersion());
            assertEquals(old.authorityCheckedAt() + 999, refreshed.authorityCheckedAt());
            assertEquals(refreshed.authorityCheckedAt() + 1000, refreshed.validUntil());
            assertEquals(1, old.validUntil() - clock.millis());
            assertEquals(1, reads.get());
            assertEquals(1, budgets.get());
            assertEquals(refreshed, cache.get("it.example", suffix, 71).block());
            assertEquals(refreshed, resolver.resolve("it.example", suffix).block());
            assertEquals(1, reads.get());
        } finally {
            redis.delete(RedisRouteCache.keys("it.example", suffix, 71).toArray(String[]::new))
                    .block();
        }
    }

    @Test
    void fixedWindowQuotaIsAtomicAndRevisionDoesNotResetIt() {
        String resource = "it:" + suffix;
        Clock fixed = Clock.fixed(Instant.ofEpochMilli(100000), ZoneOffset.UTC);
        RedisRiskRateLimiter first = new RedisRiskRateLimiter(redis, fixed, 1000),
                second = new RedisRiskRateLimiter(redis, fixed, 1000);
        var decision = new RiskDecision(200, "ALLOWED", 7, new RateLimitRule(2, 60));
        List<String> keys =
                List.of(
                        "sl:risk:{" + resource + "}:quota:60:1",
                        "sl:risk:{" + resource + "}:revision");
        try {
            List<RiskDecision> result =
                    reactor.core.publisher.Flux.range(0, 6)
                            .flatMap(
                                    i -> (i % 2 == 0 ? first : second).evaluate(resource, decision),
                                    6)
                            .collectList()
                            .block();
            assertEquals(2, result.stream().filter(RiskDecision::allowed).count());
            assertEquals(4, result.stream().filter(r -> r.status() == 429).count());
            assertEquals(
                    429,
                    first.evaluate(
                                    resource,
                                    new RiskDecision(200, "ALLOWED", 8, new RateLimitRule(2, 60)))
                            .block()
                            .status());
            assertEquals(503, first.evaluate(resource, decision).block().status());
            assertTrue(redis.getExpire(keys.get(0)).block().toMillis() > 0);
        } finally {
            redis.delete(keys.toArray(String[]::new)).block();
        }
    }

    static final class MutableClock extends Clock {
        long value;

        MutableClock(long value) {
            this.value = value;
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return Instant.ofEpochMilli(value);
        }

        public long millis() {
            return value;
        }
    }
}
