package com.jupiter.shortlink.redirect.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jupiter.shortlink.redirect.membership.RouteMembershipGuard;
import com.jupiter.shortlink.redirect.route.AuthorityUnavailableException;
import com.jupiter.shortlink.redirect.route.RouteAuthority;
import com.jupiter.shortlink.redirect.route.RouteInfo;

import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class RouteResolver {
    private final Cache<String, RouteInfo> local;
    private final Cache<String, Long> minimumVersions;
    private final ConcurrentHashMap<String, Mono<RouteInfo>> flights = new ConcurrentHashMap<>();
    private final RedisRouteCache redis;
    private final RouteAuthority authority;
    private final CacheGeneration generation;
    private final Supplier<Mono<Void>> budget;
    private final Clock clock;
    private final int maxFlights;
    private final long minimumRemainingMillis;
    private final RouteMembershipGuard membership;

    public RouteResolver(
            RedisRouteCache redis,
            RouteAuthority authority,
            CacheGeneration generation,
            Supplier<Mono<Void>> budget,
            Clock clock,
            int maxEntries,
            int maxFlights,
            long ttlMillis,
            long guardMillis,
            long minimumRemainingMillis) {
        this(
                redis,
                authority,
                generation,
                budget,
                clock,
                maxEntries,
                maxFlights,
                ttlMillis,
                guardMillis,
                minimumRemainingMillis,
                RouteMembershipGuard.disabled());
    }

    public RouteResolver(
            RedisRouteCache redis,
            RouteAuthority authority,
            CacheGeneration generation,
            Supplier<Mono<Void>> budget,
            Clock clock,
            int maxEntries,
            int maxFlights,
            long ttlMillis,
            long guardMillis,
            long minimumRemainingMillis,
            RouteMembershipGuard membership) {
        if (minimumRemainingMillis < 1 || minimumRemainingMillis >= ttlMillis)
            throw new IllegalArgumentException("Route proof must outlive the request budget");
        this.redis = redis;
        this.authority = authority;
        this.generation = generation;
        this.budget = budget;
        this.clock = clock;
        this.maxFlights = maxFlights;
        this.minimumRemainingMillis = minimumRemainingMillis;
        this.membership = java.util.Objects.requireNonNull(membership);
        local =
                Caffeine.newBuilder()
                        .maximumSize(maxEntries)
                        .expireAfterWrite(Duration.ofMillis(ttlMillis))
                        .build();
        minimumVersions =
                Caffeine.newBuilder()
                        .maximumSize(maxEntries)
                        .expireAfterWrite(Duration.ofMillis(guardMillis))
                        .build();
    }

    public Mono<RouteResolution> resolveGuarded(String domain, String uri) {
        return Mono.defer(
                () -> {
                    long epoch = generation.current();
                    String key = domain + "/" + uri;
                    RouteInfo cached = local.getIfPresent(key);
                    // Hot routes do not hash an address or consult any Bloom state.
                    if (usable(cached, key, epoch)) return Mono.just(RouteResolution.route(cached));
                    RouteMembershipGuard.Check check;
                    try {
                        check = membership.check(domain, uri);
                    } catch (RuntimeException unavailable) {
                        // The existence optimization must not bypass the original bounded fallback.
                        check = RouteMembershipGuard.Check.unknown();
                    }
                    if (check.outcome() == RouteMembershipGuard.Outcome.DEFINITELY_ABSENT)
                        return Mono.just(new RouteResolution.Absent(check.proof(), epoch));
                    return resolve(domain, uri).map(RouteResolution::route);
                });
    }

    public boolean validAbsent(RouteResolution.Absent absent) {
        try {
            return generation.current() == absent.cacheEpoch() && membership.valid(absent.proof());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** Original bounded route chain; also the single fallback after a denial proof expires. */
    public Mono<RouteInfo> resolve(String domain, String uri) {
        return Mono.defer(
                () -> {
                    long epoch = generation.current();
                    String key = domain + "/" + uri;
                    RouteInfo cached = local.getIfPresent(key);
                    if (usable(cached, key, epoch)) return Mono.just(cached);
                    String flightKey = epoch + ":" + key;
                    synchronized (flights) {
                        Mono<RouteInfo> existing = flights.get(flightKey);
                        if (existing != null) return forCaller(existing, key, epoch);
                        if (flights.size() >= maxFlights)
                            return Mono.error(
                                    new AuthorityUnavailableException("TOO_MANY_ROUTE_LOOKUPS"));
                        Mono<RouteInfo> flight =
                                flights.computeIfAbsent(
                                        flightKey,
                                        ignored ->
                                                redis.get(domain, uri, epoch)
                                                        .onErrorResume(error -> Mono.empty())
                                                        .filter(value -> usable(value, key, epoch))
                                                        .switchIfEmpty(
                                                                Mono.defer(
                                                                        () ->
                                                                                budget.get()
                                                                                        .then(
                                                                                                Mono
                                                                                                        .defer(
                                                                                                                () ->
                                                                                                                        authority
                                                                                                                                .find(
                                                                                                                                        domain,
                                                                                                                                        uri,
                                                                                                                                        epoch)))
                                                                                        .filter(
                                                                                                value ->
                                                                                                        usable(
                                                                                                                value,
                                                                                                                key,
                                                                                                                epoch))
                                                                                        .switchIfEmpty(
                                                                                                Mono
                                                                                                        .error(
                                                                                                                new AuthorityUnavailableException(
                                                                                                                        "STALE_AUTHORITY_READ")))
                                                                                        .flatMap(
                                                                                                value ->
                                                                                                        redis.put(
                                                                                                                        value)
                                                                                                                .flatMap(
                                                                                                                        accepted ->
                                                                                                                                accepted
                                                                                                                                        ? Mono
                                                                                                                                                .just(
                                                                                                                                                        value)
                                                                                                                                        : Mono
                                                                                                                                                .error(
                                                                                                                                                        new AuthorityUnavailableException(
                                                                                                                                                                "ROUTE_REVISION_CHANGED"))))))
                                                        .filter(value -> usable(value, key, epoch))
                                                        .switchIfEmpty(
                                                                Mono.error(
                                                                        new AuthorityUnavailableException(
                                                                                "ROUTE_GENERATION_CHANGED")))
                                                        .doOnNext(value -> local.put(key, value))
                                                        .doFinally(
                                                                signal -> flights.remove(flightKey))
                                                        .cache());
                        return forCaller(flight, key, epoch);
                    }
                });
    }

    private boolean usable(RouteInfo value, String key, long epoch) {
        long now = clock.millis();
        if (value == null
                || !value.fresh(now, epoch)
                || generation.current() != epoch
                || value.validUntil() - now <= minimumRemainingMillis) return false;
        Long minimum = minimumVersions.getIfPresent(key);
        return minimum == null || value.routeVersion() >= minimum;
    }

    private Mono<RouteInfo> forCaller(Mono<RouteInfo> source, String key, long epoch) {
        // Check every subscriber, including a late subscriber to a shared cached flight.
        // A near-expiry cache entry is refreshed through the same bounded origin path;
        // neither an L2 hit nor a shared result can renew its authority timestamps.
        return source.filter(value -> usable(value, key, epoch))
                .switchIfEmpty(
                        Mono.error(
                                new AuthorityUnavailableException("ROUTE_PROOF_BUDGET_EXHAUSTED")));
    }

    public Mono<Void> invalidate(String domain, String uri, long version) {
        String key = domain + "/" + uri;
        minimumVersions.asMap().merge(key, version, Math::max);
        local.invalidate(key);
        return Mono.defer(() -> redis.invalidate(domain, uri, generation.current(), version));
    }

    public void invalidateAll() {
        local.invalidateAll();
    }
}
