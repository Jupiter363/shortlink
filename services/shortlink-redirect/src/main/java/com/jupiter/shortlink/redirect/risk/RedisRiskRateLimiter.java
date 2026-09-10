package com.jupiter.shortlink.redirect.risk;

import com.jupiter.shortlink.risk.RateLimitRule;
import com.jupiter.shortlink.risk.RiskDecision;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** Fixed UTC windows; policy revision never resets an already consumed quota. */
public final class RedisRiskRateLimiter {
    private static final RedisScript<Long> RESERVE =
            RedisScript.of(
                    """
                    local function less(a,b) return #a<#b or (#a==#b and a<b) end
                    local v=redis.call('GET',KEYS[2])
                    if v and less(ARGV[2],v) then return -1 end
                    redis.call('SET',KEYS[2],ARGV[2],'PX',ARGV[4])
                    local n=redis.call('INCR',KEYS[1])
                    if n==1 then redis.call('PEXPIRE',KEYS[1],ARGV[3]) end
                    if n>tonumber(ARGV[1]) then return 0 end
                    return 1
                    """,
                    Long.class);
    private final ReactiveStringRedisTemplate redis;
    private final Clock clock;
    private final Duration timeout;

    public RedisRiskRateLimiter(
            ReactiveStringRedisTemplate redis, Clock clock, long timeoutMillis) {
        this.redis = redis;
        this.clock = clock;
        timeout = Duration.ofMillis(timeoutMillis);
    }

    public Mono<RiskDecision> evaluate(String resource, RiskDecision decision) {
        RateLimitRule rule = decision.rateLimit();
        if (!decision.allowed() || rule == null) return Mono.just(decision);
        return Mono.defer(
                () -> {
                    long now = clock.millis(),
                            size = rule.windowSeconds() * 1000L,
                            index = Math.floorDiv(now, size);
                    String base = "sl:risk:{" + resource + "}:";
                    long ttl = Math.addExact(Math.multiplyExact(index + 1, size), 1000) - now;
                    return redis.execute(
                                    RESERVE,
                                    List.of(
                                            base + "quota:" + rule.windowSeconds() + ":" + index,
                                            base + "revision"),
                                    List.of(
                                            Long.toString(rule.limit()),
                                            Long.toString(decision.policyRevision()),
                                            Long.toString(ttl),
                                            "86401000"))
                            .next()
                            .map(
                                    result ->
                                            result == 1
                                                    ? decision
                                                    : new RiskDecision(
                                                            result == 0 ? 429 : 503,
                                                            result == 0
                                                                    ? "RATE_LIMITED"
                                                                    : "POLICY_REVISION_CHANGED",
                                                            decision.policyRevision(),
                                                            null))
                            .switchIfEmpty(
                                    Mono.just(
                                            new RiskDecision(
                                                    503,
                                                    "RATE_LIMIT_UNKNOWN",
                                                    decision.policyRevision(),
                                                    null)))
                            .timeout(timeout)
                            .onErrorReturn(
                                    new RiskDecision(
                                            503,
                                            "RATE_LIMIT_UNAVAILABLE",
                                            decision.policyRevision(),
                                            null));
                });
    }
}
