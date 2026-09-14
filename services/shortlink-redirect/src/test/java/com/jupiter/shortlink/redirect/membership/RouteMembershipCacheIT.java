package com.jupiter.shortlink.redirect.membership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.membership.*;
import com.jupiter.shortlink.redirect.TestConfig;
import com.jupiter.shortlink.redirect.cache.*;
import com.jupiter.shortlink.redirect.route.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Real MySQL + Redis; only lease time is controlled, never business authority or cache contents.
 */
@EnabledIfEnvironmentVariable(named = "BLOOM_REDIRECT_IT_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RouteMembershipCacheIT {
    static final String CATALOG = "shortlink_bloom_redirect_it";
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    TransactionTemplate transaction;
    JdbcRouteMembershipStore store;
    LettuceConnectionFactory connection;
    ReactiveStringRedisTemplate redis;
    Scheduler scheduler;
    LocalRouteMembership membership;
    RedisRouteCache cache;
    JdbcRouteAuthority authority;
    AtomicLong monotonic;
    Clock wallClock;
    long cacheEpoch;
    AtomicInteger budgets;

    @BeforeAll
    void connectOnlyDedicatedResources() throws Exception {
        String url = required("BLOOM_REDIRECT_IT_URL");
        assertEquals(CATALOG, required("BLOOM_REDIRECT_IT_ALLOW_RESET"));
        assertTrue(
                url.matches(
                        "jdbc:mysql://127\\.0\\.0\\.1:23306/shortlink_bloom_redirect_it(?:\\?.*)?"));
        source =
                new DriverManagerDataSource(
                        url,
                        required("BLOOM_REDIRECT_IT_USER"),
                        required("BLOOM_REDIRECT_IT_PASSWORD"));
        try (var c = source.getConnection()) {
            assertEquals(CATALOG, c.getCatalog());
        }
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(2);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        assertEquals("26379", required("BLOOM_REDIRECT_IT_REDIS_PORT"));
        RedisStandaloneConfiguration redisConfig =
                new RedisStandaloneConfiguration("127.0.0.1", 26379);
        redisConfig.setDatabase(6);
        connection = new LettuceConnectionFactory(redisConfig);
        connection.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(connection);
        assertEquals(
                "PONG",
                redis.getConnectionFactory()
                        .getReactiveConnection()
                        .ping()
                        .block(Duration.ofSeconds(3)));
        scheduler = Schedulers.newBoundedElastic(2, 2, "bloom-redirect-it", 30, true);
    }

    @BeforeEach
    void resetOnlyConfirmedCatalog() {
        jdbc.update("DELETE FROM t_link_route");
        jdbc.update("DELETE FROM t_route_membership");
        jdbc.update("DELETE FROM t_route_membership_control");
        jdbc.update(
                "INSERT INTO t_route_membership_control"
                    + " (namespace,generation,revision,member_count,mode,baseline_ready,transition_id)"
                    + " VALUES ('routes',?,0,0,'OFF',FALSE,?)",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString());
        monotonic = new AtomicLong(10_000_000_000L);
        store =
                new JdbcRouteMembershipStore(
                        jdbc, new DataSourceTransactionManager(source), monotonic::get);
        DrainPermit baseline = store.beginDrain();
        advanceGrace();
        store.completeBaseline(baseline);
        DrainPermit enforcement = store.beginDrain();
        advanceGrace();
        store.finishDrain(enforcement, Mode.ENFORCE);
        membership =
                new LocalRouteMembership(
                        store,
                        new RouteMembershipProperties(
                                true, 100, .0001, .001, 1048576, 2, 100, 250, 10000, "", false),
                        new SimpleMeterRegistry());
        wallClock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        cacheEpoch = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        cache = spy(new RedisRouteCache(redis, new ObjectMapper(), wallClock, 1000, 30000));
        authority = spy(new JdbcRouteAuthority(jdbc, scheduler, wallClock, TestConfig.defaults()));
        budgets = new AtomicInteger();
    }

    @AfterEach
    void removeOnlyThisTestsRedisGeneration() {
        if (membership != null) membership.stop();
        if (redis == null) return;
        var keys =
                redis.scan(
                                ScanOptions.scanOptions()
                                        .match("sl:r:v1:g" + cacheEpoch + ":*")
                                        .count(100)
                                        .build())
                        .collectList()
                        .block(Duration.ofSeconds(3));
        if (keys != null && !keys.isEmpty())
            redis.delete(keys.toArray(String[]::new)).block(Duration.ofSeconds(3));
    }

    @AfterAll
    void close() {
        if (connection != null) connection.destroy();
        if (scheduler != null) scheduler.dispose();
    }

    RouteResolver resolver() {
        return new RouteResolver(
                cache,
                authority,
                new CacheGeneration() {
                    public long current() {
                        return cacheEpoch;
                    }

                    public Mono<Long> refresh() {
                        return Mono.just(cacheEpoch);
                    }
                },
                () -> Mono.fromRunnable(budgets::incrementAndGet),
                wallClock,
                10,
                2,
                1000,
                30000,
                100,
                membership);
    }

    @Test
    void reliableNegativeDoesNotReadOrPopulateRealRedisOrMysqlRouteAuthority() {
        membership.refresh();
        RouteResolver routes = resolver();
        RouteResolution result =
                routes.resolveGuarded("bloom.example", "Missing01").block(Duration.ofSeconds(3));
        var absence = assertInstanceOf(RouteResolution.Absent.class, result);
        assertTrue(routes.validAbsent(absence));
        verifyNoInteractions(cache, authority);
        assertEquals(0, budgets.get());
        assertTrue(
                redis.scan(ScanOptions.scanOptions().match("sl:r:v1:g" + cacheEpoch + ":*").build())
                        .collectList()
                        .block(Duration.ofSeconds(3))
                        .isEmpty());
        // Once lease time expires, the same key must query authority and get its separate proof.
        advanceGrace();
        var fallback =
                assertInstanceOf(
                                RouteResolution.Route.class,
                                routes.resolveGuarded("bloom.example", "Missing01")
                                        .block(Duration.ofSeconds(3)))
                        .value();
        assertEquals("NOT_FOUND", fallback.routeStatus());
        assertEquals(0, fallback.linkId());
        assertEquals(wallClock.millis(), fallback.authorityCheckedAt());
        verify(authority).find("bloom.example", "Missing01", cacheEpoch);
        verify(cache).put(fallback);
        assertEquals(1, budgets.get());
    }

    @Test
    void registeredAndPublishedRouteSurvivesStoppedKafkaAndAnOldBloom() {
        membership.refresh();
        assertEquals(
                RouteMembershipGuard.Outcome.DEFINITELY_ABSENT,
                membership.check("bloom.example", "NewRoute1").outcome());
        RouteAddress address = new RouteAddress("bloom.example", "NewRoute1");
        RegistrationPermit registration = store.register(List.of(address));
        advanceGrace();
        transaction.execute(
                status -> {
                    store.verifyForPublication(registration, List.of(address));
                    jdbc.update(
                            "INSERT INTO t_link_route"
                                + " (link_id,tenant_id,current_gid,domain_norm,short_uri,origin_url,route_status,updated_at)"
                                + " VALUES (41,1,'g',?,?,'https://target.example/real','ACTIVE',?)",
                            address.domainNorm(),
                            address.shortUri(),
                            wallClock.millis());
                    return null;
                });
        // No Kafka hint and no membership refresh occurred after registration/publication.
        assertEquals(
                RouteMembershipGuard.Outcome.UNKNOWN,
                membership.check(address.domainNorm(), address.shortUri()).outcome());
        var route =
                assertInstanceOf(
                                RouteResolution.Route.class,
                                resolver()
                                        .resolveGuarded(address.domainNorm(), address.shortUri())
                                        .block(Duration.ofSeconds(3)))
                        .value();
        assertEquals(41, route.linkId());
        assertEquals("https://target.example/real", route.originUrl());
        assertTrue(route.active(wallClock.millis()));
        verify(authority).find(address.domainNorm(), address.shortUri(), cacheEpoch);
        membership.refresh();
        assertEquals(
                RouteMembershipGuard.Outcome.MAY_EXIST,
                membership.check(address.domainNorm(), address.shortUri()).outcome());
        clearInvocations(authority);
        assertInstanceOf(
                RouteResolution.Route.class,
                resolver()
                        .resolveGuarded(address.domainNorm(), address.shortUri())
                        .block(Duration.ofSeconds(3)));
        verifyNoInteractions(authority); // The second instance reads the real Redis route value.
    }

    @Test
    void pendingRegistrationIsAMaybeAndDatabaseAbsenceRetainsItsOriginalTimestamps() {
        RouteAddress address = new RouteAddress("bloom.example", "Pending01");
        store.register(List.of(address));
        membership.refresh();
        assertEquals(
                RouteMembershipGuard.Outcome.MAY_EXIST,
                membership.check(address.domainNorm(), address.shortUri()).outcome());
        var result =
                assertInstanceOf(
                                RouteResolution.Route.class,
                                resolver()
                                        .resolveGuarded(address.domainNorm(), address.shortUri())
                                        .block(Duration.ofSeconds(3)))
                        .value();
        assertEquals("NOT_FOUND", result.routeStatus());
        var cached =
                cache.get(address.domainNorm(), address.shortUri(), cacheEpoch)
                        .block(Duration.ofSeconds(3));
        assertEquals(result.authorityCheckedAt(), cached.authorityCheckedAt());
        assertEquals(result.validUntil(), cached.validUntil());
        verify(authority, times(1)).find(address.domainNorm(), address.shortUri(), cacheEpoch);
    }

    void advanceGrace() {
        monotonic.addAndGet(JdbcRouteMembershipStore.PUBLICATION_DELAY_NANOS);
    }

    static String required(String key) {
        String value = System.getenv(key);
        assertNotNull(value, "Required isolated integration setting: " + key);
        return value;
    }
}
