package com.jupiter.shortlink.admin.account;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.fastjson2.JSON;
import com.jupiter.shortlink.admin.common.convention.exception.ClientException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A real isolated Redis is mandatory for this test; missing configuration fails rather than
 * silently skipping.
 */
class AccountRedisIntegrationTest {
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private RedisAccountSessionStore sessions;
    private String username;

    @BeforeEach
    void connect() {
        String port = System.getProperty("account.it.redis-port");
        assertNotNull(
                port,
                "Set -Daccount.it.redis-port to an isolated loopback Redis; do not point at a"
                    + " developer/production cache");
        factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(port));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        sessions = new RedisAccountSessionStore(redis);
        username = "account_it_" + UUID.randomUUID().toString().replace("-", "");
    }

    @AfterEach
    void cleanup() {
        if (redis != null && username != null) redis.delete("login_" + username);
        if (factory != null) factory.destroy();
    }

    @Test
    void LoginLastsThirtyDaysWithoutRenewingEarlierTokens() {
        Instant issuedAt = Instant.parse("2026-09-15T00:00:00Z");
        Duration thirtyDays = Duration.ofDays(30);
        sessions = new RedisAccountSessionStore(redis, Clock.fixed(issuedAt, ZoneOffset.UTC));
        String first = sessions.issue(9, username, 1);
        assertEquals(issuedAt.plus(thirtyDays).toEpochMilli(),
                sessions.find(username, first).expiresAt());
        long ttlSeconds = redis.getExpire("login_" + username);
        assertTrue(ttlSeconds > thirtyDays.minusSeconds(10).toSeconds()
                && ttlSeconds <= thirtyDays.toSeconds(), "Redis must retain the session for 30 days");

        var laterLogin = new RedisAccountSessionStore(redis,
                Clock.fixed(issuedAt.plus(Duration.ofDays(10)), ZoneOffset.UTC));
        String second = laterLogin.issue(9, username, 1);
        assertNotNull(laterLogin.find(username, first), "Login must survive beyond 30 minutes");
        assertEquals(issuedAt.plus(thirtyDays).toEpochMilli(),
                laterLogin.find(username, first).expiresAt());
        var beforeExpiry = new RedisAccountSessionStore(redis,
                Clock.fixed(issuedAt.plus(thirtyDays).minusMillis(1), ZoneOffset.UTC));
        assertNotNull(beforeExpiry.find(username, first));
        var atExpiry = new RedisAccountSessionStore(redis,
                Clock.fixed(issuedAt.plus(thirtyDays), ZoneOffset.UTC));
        assertNull(atExpiry.find(username, first), "Original token must expire exactly at 30 days");
        assertNotNull(atExpiry.find(username, second), "Later login keeps its own expiry");
        assertTrue(redis.getExpire("login_" + username) > 0);
    }

    @Test
    void LuaSessionIssueHasAtomicTtlMinimalPayloadAndOnlyCurrentTokenLogout() {
        String first = sessions.issue(9, username, 1);
        String second = sessions.issue(9, username, 1);
        assertNotEquals(first, second);
        assertNotNull(sessions.find(username, first));
        assertTrue(redis.getExpire("login_" + username) > 0);
        String payload = redis.opsForHash().get("login_" + username, first).toString();
        assertEquals(4, JSON.parseObject(payload).size());
        sessions.revoke(username, first);
        assertNull(sessions.find(username, first));
        assertNotNull(sessions.find(username, second));
    }

    @Test
    void DelayedRevocationPreservesNewGenerationAndEnforcesIndividualExpiry() {
        String old = sessions.issue(9, username, 1);
        String current = sessions.issue(9, username, 2);
        sessions.revokeBefore(username, 2);
        sessions.revokeBefore(username, 2);
        assertNull(sessions.find(username, old));
        assertNotNull(sessions.find(username, current));
        RedisAccountSessionStore future =
                new RedisAccountSessionStore(
                        redis,
                        Clock.fixed(
                                Instant.now()
                                        .plus(RedisAccountSessionStore.SESSION_LIFETIME)
                                        .plusSeconds(1),
                                ZoneOffset.UTC));
        assertNull(future.find(username, current));
    }

    @Test
    void SessionCountIsBoundedAndExpiredSlotsCanBeReused() {
        for (int i = 0; i < RedisAccountSessionStore.MAX_SESSIONS; i++)
            sessions.issue(9, username, 1);
        assertThrows(ClientException.class, () -> sessions.issue(9, username, 1));
        RedisAccountSessionStore future =
                new RedisAccountSessionStore(
                        redis,
                        Clock.fixed(
                                Instant.now()
                                        .plus(RedisAccountSessionStore.SESSION_LIFETIME)
                                        .plusSeconds(1),
                                ZoneOffset.UTC));
        assertNotNull(future.issue(9, username, 2));
        assertEquals(1, redis.opsForHash().size("login_" + username));
    }
}
