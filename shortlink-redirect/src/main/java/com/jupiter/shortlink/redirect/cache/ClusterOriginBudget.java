package com.jupiter.shortlink.redirect.cache;

import com.jupiter.shortlink.redirect.route.AuthorityUnavailableException;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

public final class ClusterOriginBudget {
    private static final RedisScript<Long> TAKE =
            RedisScript.of(
                    "local n=redis.call('INCR',KEYS[1]); if n==1 then"
                        + " redis.call('PEXPIRE',KEYS[1],2000) end; return n",
                    Long.class);
    private final ReactiveStringRedisTemplate redis;
    private final Clock clock;
    private final int limit;
    private final Duration timeout;

    public ClusterOriginBudget(
            ReactiveStringRedisTemplate redis, Clock clock, int limit, long timeoutMillis) {
        this.redis = redis;
        this.clock = clock;
        this.limit = limit;
        timeout = Duration.ofMillis(timeoutMillis);
    }

    public Mono<Void> acquire() {
        return redis.execute(TAKE, List.of("sl:origin:budget:" + clock.millis() / 1000), List.of())
                .next()
                .timeout(timeout)
                .switchIfEmpty(
                        Mono.error(new AuthorityUnavailableException("ORIGIN_BUDGET_UNKNOWN")))
                .flatMap(
                        count ->
                                count <= limit
                                        ? Mono.empty()
                                        : Mono.error(
                                                new AuthorityUnavailableException(
                                                        "ORIGIN_BUDGET_EXHAUSTED")));
    }
}
