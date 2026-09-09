package com.jupiter.shortlink.redirect.cache;

import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.redirect.TestConfig;
import com.jupiter.shortlink.redirect.route.*;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.*;
import java.util.UUID;

class JdbcGenerationIT {
    static JdbcTemplate admin, jdbc;
    static String schema;
    static LettuceConnectionFactory connection;
    static ReactiveStringRedisTemplate redis;
    static Scheduler scheduler;

    @BeforeAll
    static void initialize() {
        String port = System.getenv("SHORTLINK_MYSQL_TEST_PORT"),
                redisPort = System.getenv("SHORTLINK_REDIS_TEST_PORT"),
                password = System.getenv("SHORTLINK_MYSQL_TEST_PASSWORD");
        assertNotNull(port, "Explicit isolated MySQL port required");
        assertNotNull(redisPort, "Explicit isolated Redis port required");
        assertNotNull(password, "Explicit isolated MySQL password required");
        String prefix = "jdbc:mysql://127.0.0.1:" + Integer.parseInt(port) + "/";
        admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                prefix
                                        + "?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true",
                                "root",
                                password));
        schema = "redirect_it_" + UUID.randomUUID().toString().replace("-", "");
        assertTrue(schema.matches("redirect_it_[a-f0-9]{32}"));
        admin.execute("CREATE DATABASE " + schema);
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                prefix
                                        + schema
                                        + "?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true",
                                "root",
                                password));
        jdbc.execute(
                "CREATE TABLE t_cache_generation(cache_name VARCHAR(64) PRIMARY KEY,generation"
                        + " BIGINT NOT NULL)");
        jdbc.update("INSERT INTO t_cache_generation VALUES ('redirect-route',1)");
        jdbc.execute(
                "CREATE TABLE t_link_route(domain_norm VARCHAR(253),short_uri VARCHAR(32),link_id"
                    + " BIGINT,tenant_id BIGINT,current_gid VARCHAR(64),origin_url"
                    + " VARCHAR(2048),route_status VARCHAR(16),expire_at DATETIME(3),route_version"
                    + " BIGINT,ownership_version BIGINT,PRIMARY KEY(domain_norm,short_uri))");
        jdbc.update(
                "INSERT INTO t_link_route"
                    + " VALUES('it.example','Ab',9007199254740993,42,'g','https://target.example','ACTIVE',NULL,7,3)");
        connection = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(redisPort));
        connection.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(connection);
        scheduler = Schedulers.newBoundedElastic(2, 2, "jdbc-it", 60, true);
    }

    @AfterAll
    static void close() {
        if (scheduler != null) scheduler.dispose();
        if (connection != null) connection.destroy();
        if (admin != null && schema != null && schema.matches("redirect_it_[a-f0-9]{32}"))
            admin.execute("DROP DATABASE " + schema);
    }

    @Test
    void routeQueryUsesRealAuthorityColumnsAndPreservesLongIdentity() {
        JdbcRouteAuthority authority =
                new JdbcRouteAuthority(jdbc, scheduler, Clock.systemUTC(), TestConfig.defaults());
        RouteInfo found = authority.find("it.example", "Ab", 7).block(Duration.ofSeconds(3));
        assertEquals(9007199254740993L, found.linkId());
        assertEquals("42", found.tenantId());
        assertEquals(3, found.ownershipVersion());
        assertEquals(1000, found.validUntil() - found.authorityCheckedAt());
        assertEquals(7, found.cacheGeneration());
        RouteInfo absent = authority.find("it.example", "missing", 7).block(Duration.ofSeconds(3));
        assertEquals("NOT_FOUND", absent.routeStatus());
        assertFalse(absent.active(System.currentTimeMillis()));
    }

    @Test
    void lostMarkerRotatesDurableGenerationAndRestoredOldMarkerCannotRewind() {
        String marker = "it:generation:" + UUID.randomUUID();
        var clock = new RedisRouteCacheIT.MutableClock(System.currentTimeMillis());
        GenerationCoordinator coordinator =
                new GenerationCoordinator(
                        redis, jdbc, scheduler, clock, TestConfig.defaults(), marker);
        try {
            long first = coordinator.refresh().block(Duration.ofSeconds(3));
            assertEquals(2, first);
            assertEquals(first, coordinator.current());
            redis.delete(marker).block();
            long second = coordinator.refresh().block(Duration.ofSeconds(3));
            assertEquals(3, second);
            redis.opsForValue().set(marker, "1").block();
            assertEquals(second, coordinator.refresh().block(Duration.ofSeconds(3)));
            clock.value += 1000;
            assertThrows(AuthorityUnavailableException.class, coordinator::current);
            coordinator.refresh().block(Duration.ofSeconds(3));
            clock.value -= 1;
            assertThrows(AuthorityUnavailableException.class, coordinator::current);
            clock.value += 1;
            redis.opsForValue().set(marker, "9999").block();
            assertThrows(Exception.class, () -> coordinator.refresh().block(Duration.ofSeconds(3)));
        } finally {
            redis.delete(marker).block();
        }
    }
}
