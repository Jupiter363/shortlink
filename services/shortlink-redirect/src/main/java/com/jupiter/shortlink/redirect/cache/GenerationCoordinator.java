package com.jupiter.shortlink.redirect.cache;

import com.jupiter.shortlink.redirect.config.RedirectProperties;
import com.jupiter.shortlink.redirect.route.AuthorityUnavailableException;

import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** A short authority lease fences instances after Redis restoration, including stale instances. */
public final class GenerationCoordinator implements CacheGeneration, SmartLifecycle {
    public static final String MARKER = "sl:route:generation";
    private static final RedisScript<Long> MAX =
            RedisScript.of(
                    "local old=redis.call('GET',KEYS[1]); local n=ARGV[1]; if not old or #n>#old or"
                        + " (#n==#old and n>old) then redis.call('SET',KEYS[1],n); return 1 end;"
                        + " return 0",
                    Long.class);
    private final ReactiveStringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final Scheduler scheduler;
    private final Clock clock;
    private final RedirectProperties properties;
    private final String markerKey;
    private volatile long generation, checkedAt, validUntil;
    private volatile boolean running;
    private Disposable subscription;

    public GenerationCoordinator(
            ReactiveStringRedisTemplate redis,
            JdbcTemplate jdbc,
            Scheduler scheduler,
            Clock clock,
            RedirectProperties properties) {
        this(redis, jdbc, scheduler, clock, properties, MARKER);
    }

    GenerationCoordinator(
            ReactiveStringRedisTemplate redis,
            JdbcTemplate jdbc,
            Scheduler scheduler,
            Clock clock,
            RedirectProperties properties,
            String markerKey) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.scheduler = scheduler;
        this.clock = clock;
        this.properties = properties;
        this.markerKey = markerKey;
    }

    @Override
    public long current() {
        if (generation <= 0 || clock.millis() < checkedAt || clock.millis() >= validUntil)
            throw new AuthorityUnavailableException("GENERATION_UNKNOWN");
        return generation;
    }

    @Override
    public Mono<Long> refresh() {
        long started = clock.millis();
        return redis.opsForValue()
                .get(markerKey)
                .defaultIfEmpty("")
                .flatMap(
                        marker ->
                                Mono.fromCallable(
                                                () -> {
                                                    Long value =
                                                            jdbc.queryForObject(
                                                                    "SELECT generation FROM"
                                                                        + " t_cache_generation"
                                                                        + " WHERE"
                                                                        + " cache_name='redirect-route'",
                                                                    Long.class);
                                                    if (value == null
                                                            || value < 1
                                                            || value == Long.MAX_VALUE)
                                                        throw new AuthorityUnavailableException(
                                                                "INVALID_GENERATION");
                                                    if (marker.isEmpty()) {
                                                        jdbc.update(
                                                                "UPDATE t_cache_generation SET"
                                                                    + " generation=generation+1"
                                                                    + " WHERE"
                                                                    + " cache_name='redirect-route'"
                                                                    + " AND generation=?",
                                                                value);
                                                        value =
                                                                jdbc.queryForObject(
                                                                        "SELECT generation FROM"
                                                                            + " t_cache_generation"
                                                                            + " WHERE"
                                                                            + " cache_name='redirect-route'",
                                                                        Long.class);
                                                    }
                                                    return value;
                                                })
                                        .subscribeOn(scheduler))
                .flatMap(
                        value ->
                                redis.execute(
                                                MAX,
                                                List.of(markerKey),
                                                List.of(Long.toString(value)))
                                        .then(redis.opsForValue().get(markerKey))
                                        .map(Long::parseLong)
                                        .filter(
                                                actual ->
                                                        actual.equals(value)
                                                                && actual >= generation)
                                        .switchIfEmpty(
                                                Mono.error(
                                                        new AuthorityUnavailableException(
                                                                "GENERATION_DIVERGED"))))
                .timeout(Duration.ofMillis(properties.requestTimeoutMillis()))
                .doOnNext(
                        value -> {
                            generation = value;
                            checkedAt = started;
                            validUntil = started + properties.authorityTtlMillis();
                        });
    }

    @Override
    public void start() {
        running = true;
        subscription =
                Flux.interval(
                                Duration.ZERO,
                                Duration.ofMillis(
                                        Math.min(
                                                properties.generationPollMillis(),
                                                Math.max(1, properties.authorityTtlMillis() / 3))))
                        .onBackpressureDrop()
                        .concatMap(ignored -> refresh().onErrorResume(error -> Mono.empty()), 1)
                        .subscribe();
    }

    @Override
    public void stop() {
        running = false;
        if (subscription != null) subscription.dispose();
        validUntil = 0;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
