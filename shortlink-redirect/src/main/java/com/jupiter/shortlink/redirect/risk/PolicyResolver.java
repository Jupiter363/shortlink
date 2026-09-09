package com.jupiter.shortlink.redirect.risk;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jupiter.shortlink.redirect.route.AuthorityUnavailableException;
import com.jupiter.shortlink.risk.PolicySnapshot;

import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Only explicit, resource-scoped authority responses establish known-no-policy. */
public final class PolicyResolver {
    private final Cache<String, PolicySnapshot> cache;
    private final Cache<String, Long> floors;
    private final Map<String, Mono<PolicySnapshot>> flights = new HashMap<>();
    private final PolicyAuthority authority;
    private final Supplier<Mono<Void>> budget;
    private final Clock clock;
    private final int maxFlights;
    private final long ttl;
    private final long minimumRemainingMillis;

    public PolicyResolver(
            PolicyAuthority authority,
            Supplier<Mono<Void>> budget,
            Clock clock,
            int entries,
            int maxFlights,
            long ttl,
            long minimumRemainingMillis) {
        if (minimumRemainingMillis < 1 || minimumRemainingMillis >= ttl)
            throw new IllegalArgumentException("Policy proof must outlive the request budget");
        this.authority = authority;
        this.budget = budget;
        this.clock = clock;
        this.maxFlights = maxFlights;
        this.ttl = ttl;
        this.minimumRemainingMillis = minimumRemainingMillis;
        cache =
                Caffeine.newBuilder()
                        .maximumSize(entries)
                        .expireAfterWrite(Duration.ofMillis(ttl))
                        .build();
        floors =
                Caffeine.newBuilder()
                        .maximumSize(entries)
                        .expireAfterWrite(Duration.ofSeconds(30))
                        .build();
    }

    public Mono<PolicySnapshot> resolve(String tenant, long link) {
        return Mono.defer(
                () -> {
                    String resource = tenant + ":" + link;
                    PolicySnapshot snapshot = cache.getIfPresent(resource);
                    if (usable(snapshot, resource)) return Mono.just(snapshot);
                    synchronized (flights) {
                        Mono<PolicySnapshot> existing = flights.get(resource);
                        if (existing != null) return forCaller(existing, resource);
                        if (flights.size() >= maxFlights)
                            return Mono.error(new AuthorityUnavailableException("POLICY_BUSY"));
                        Mono<PolicySnapshot> flight =
                                Mono.defer(
                                                () ->
                                                        budget.get()
                                                                .then(
                                                                        Mono.defer(
                                                                                () ->
                                                                                        authority
                                                                                                .read(
                                                                                                        tenant,
                                                                                                        link))))
                                        .filter(value -> usable(value, resource))
                                        .switchIfEmpty(
                                                Mono.error(
                                                        new AuthorityUnavailableException(
                                                                "POLICY_UNKNOWN")))
                                        .doOnNext(value -> cache.put(resource, value))
                                        .doFinally(
                                                signal -> {
                                                    synchronized (flights) {
                                                        flights.remove(resource);
                                                    }
                                                })
                                        .cache();
                        flights.put(resource, flight);
                        return forCaller(flight, resource);
                    }
                });
    }

    private boolean usable(PolicySnapshot value, String resource) {
        long now = clock.millis();
        if (value == null
                || !resource.equals(value.resourceKey())
                || !value.authoritativeAt(now)
                || now - value.evaluatedAt() >= ttl
                || remainingObservationBudget(value, now) <= minimumRemainingMillis
                || value.validUntil() - value.evaluatedAt() > ttl) return false;
        Long floor = floors.getIfPresent(resource);
        return floor == null || value.policyRevision() >= floor;
    }

    private long remainingObservationBudget(PolicySnapshot value, long now) {
        // Command can shorten an otherwise fresh observation to a scheduled policy
        // transition. Refreshing cannot move that transition; reserve observation
        // freshness separately and let authoritativeAt/final evaluation enforce it.
        if (value.nextTransitionAt() != null && value.validUntil() == value.nextTransitionAt())
            return ttl - (now - value.evaluatedAt());
        return value.validUntil() - now;
    }

    private Mono<PolicySnapshot> forCaller(Mono<PolicySnapshot> source, String resource) {
        return source.filter(value -> usable(value, resource))
                .switchIfEmpty(
                        Mono.error(
                                new AuthorityUnavailableException(
                                        "POLICY_PROOF_BUDGET_EXHAUSTED")));
    }

    public void invalidate(String resource, long revision) {
        floors.asMap().merge(resource, revision, Math::max);
        cache.invalidate(resource);
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }
}
