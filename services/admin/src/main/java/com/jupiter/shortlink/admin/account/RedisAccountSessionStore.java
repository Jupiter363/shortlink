package com.jupiter.shortlink.admin.account;

import com.alibaba.fastjson2.JSON;
import com.jupiter.shortlink.admin.common.convention.exception.ClientException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

@Component
public final class RedisAccountSessionStore implements AccountSessionStore {
    public static final String SESSION_KEY_PREFIX = "login_";
    public static final Duration SESSION_LIFETIME = Duration.ofMinutes(30);
    public static final int MAX_SESSIONS = 16;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DefaultRedisScript<Long> ISSUE =
            new DefaultRedisScript<>(
                    """
local values = redis.call('HGETALL', KEYS[1])
for i = 1, #values, 2 do
  local ok, session = pcall(cjson.decode, values[i + 1])
  if not ok or type(session) ~= 'table' or not session.expiresAt or tonumber(session.expiresAt) <= tonumber(ARGV[3]) then
    redis.call('HDEL', KEYS[1], values[i])
  end
end
if redis.call('HLEN', KEYS[1]) >= tonumber(ARGV[5]) then return 0 end
if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 1 then return -1 end
redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
redis.call('PEXPIRE', KEYS[1], ARGV[4])
return 1
""",
                    Long.class);
    private static final DefaultRedisScript<Long> CLEANUP =
            new DefaultRedisScript<>(
                    """
local values = redis.call('HGETALL', KEYS[1])
local removed = 0
for i = 1, #values, 2 do
  local ok, session = pcall(cjson.decode, values[i + 1])
  if not ok or type(session) ~= 'table' or not session.authVersion or tonumber(session.authVersion) < tonumber(ARGV[1]) then
    removed = removed + redis.call('HDEL', KEYS[1], values[i])
  end
end
return removed
""",
                    Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    @Autowired
    public RedisAccountSessionStore(StringRedisTemplate redis) {
        this(redis, Clock.systemUTC());
    }

    public RedisAccountSessionStore(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public String issue(long tenantId, String username, long authVersion) {
        long now = clock.millis();
        AccountSession session =
                new AccountSession(
                        tenantId, username, authVersion, now + SESSION_LIFETIME.toMillis());
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Long result =
                redis.execute(
                        ISSUE,
                        List.of(SESSION_KEY_PREFIX + username),
                        token,
                        JSON.toJSONString(session),
                        Long.toString(now),
                        Long.toString(SESSION_LIFETIME.toMillis()),
                        Integer.toString(MAX_SESSIONS));
        if (!Long.valueOf(1).equals(result)) throw new ClientException("登录会话已达上限或服务暂不可用");
        return token;
    }

    @Override
    public AccountSession find(String username, String token) {
        if (username == null || token == null || token.length() > 128) return null;
        Object value = redis.opsForHash().get(SESSION_KEY_PREFIX + username, token);
        if (value == null) return null;
        try {
            AccountSession session = JSON.parseObject(value.toString(), AccountSession.class);
            if (session != null
                    && username.equals(session.username())
                    && session.expiresAt() > clock.millis()) return session;
        } catch (RuntimeException malformed) {
            /* Fail closed for malformed/legacy sessions. */
        }
        revoke(username, token);
        return null;
    }

    @Override
    public void revoke(String username, String token) {
        if (username != null && token != null)
            redis.opsForHash().delete(SESSION_KEY_PREFIX + username, token);
    }

    @Override
    public void revokeBefore(String username, long authVersion) {
        redis.execute(CLEANUP, List.of(SESSION_KEY_PREFIX + username), Long.toString(authVersion));
    }
}
