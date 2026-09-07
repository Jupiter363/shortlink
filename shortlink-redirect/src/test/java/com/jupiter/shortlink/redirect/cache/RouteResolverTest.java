package com.jupiter.shortlink.redirect.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.redirect.route.*;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.*;
import java.util.concurrent.atomic.AtomicLong;

class RouteResolverTest {
    final RedisRouteCache redis = mock(RedisRouteCache.class);
    final RouteAuthority authority = mock(RouteAuthority.class);
    final AtomicLong epoch = new AtomicLong(1);
    final Clock clock = Clock.fixed(Instant.ofEpochMilli(1500), ZoneOffset.UTC);
    final CacheGeneration generation =
            new CacheGeneration() {
                public long current() {
                    return epoch.get();
                }

                public Mono<Long> refresh() {
                    return Mono.just(epoch.get());
                }
            };

    RouteInfo route(long revision) {
        return new RouteInfo(
                "s.example",
                "Ab",
                1,
                "1",
                "g",
                "https://target.example",
                "ACTIVE",
                null,
                revision,
                1,
                1000,
                2000,
                1);
    }

    RouteResolver resolver(int flights) {
        when(redis.get(anyString(), anyString(), anyLong())).thenReturn(Mono.empty());
        when(redis.put(any())).thenReturn(Mono.just(true));
        when(redis.invalidate(anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(Mono.empty());
        return new RouteResolver(
                redis, authority, generation, Mono::empty, clock, 10, flights, 1000, 30000, 100);
    }

    @Test
    void simultaneousMissesShareOneAuthorityQueryAndUniqueMissesAreBounded() {
        RouteResolver resolver = resolver(1);
        Sinks.One<RouteInfo> source = Sinks.one();
        when(authority.find("s.example", "Ab", 1)).thenReturn(source.asMono());
        var first = resolver.resolve("s.example", "Ab").subscribe();
        var second = resolver.resolve("s.example", "Ab").subscribe();
        StepVerifier.create(resolver.resolve("s.example", "Cd")).expectError().verify();
        source.tryEmitValue(route(1));
        verify(authority, times(1)).find("s.example", "Ab", 1);
        assertEquals(route(1), resolver.resolve("s.example", "Ab").block());
        first.dispose();
        second.dispose();
    }

    @Test
    void invalidationAndGenerationChangeRejectLateAuthorityCompletion() {
        for (boolean invalidate : new boolean[] {true, false}) {
            RouteResolver resolver = resolver(2);
            epoch.set(1);
            Sinks.One<RouteInfo> source = Sinks.one();
            when(authority.find("s.example", "Ab", 1)).thenReturn(source.asMono());
            StepVerifier.create(resolver.resolve("s.example", "Ab"))
                    .then(
                            () -> {
                                if (invalidate) resolver.invalidate("s.example", "Ab", 2).block();
                                else epoch.set(2);
                                source.tryEmitValue(route(1));
                            })
                    .expectError()
                    .verify();
        }
    }

    @Test
    void staleL2ProofNeverGetsAnewAuthorityTimestamp() {
        RouteResolver resolver = resolver(2);
        when(redis.get("s.example", "Ab", 1)).thenReturn(Mono.just(route(1)));
        assertEquals(1000, resolver.resolve("s.example", "Ab").block().authorityCheckedAt());
        verifyNoInteractions(authority);
    }

    @Test
    void noRedisOriginBudgetMeansNoAuthorityRead() {
        RouteResolver resolver =
                new RouteResolver(
                        redis,
                        authority,
                        generation,
                        () -> Mono.error(new IllegalStateException("budget unavailable")),
                        clock,
                        10,
                        2,
                        1000,
                        30000,
                        100);
        when(redis.get("s.example", "Ab", 1))
                .thenReturn(Mono.error(new IllegalStateException("redis unavailable")));
        StepVerifier.create(resolver.resolve("s.example", "Ab")).expectError().verify();
        verifyNoInteractions(authority);
    }

    @Test
    void nearExpiryL1AndL2RefreshThroughOneBudgetedSharedFlight() {
        AtomicLong now = new AtomicLong(1000), reads = new AtomicLong();
        Clock mutable = mock(Clock.class);
        when(mutable.millis()).thenAnswer(ignored -> now.get());
        when(redis.get(anyString(), anyString(), anyLong())).thenReturn(Mono.empty());
        when(redis.put(any())).thenReturn(Mono.just(true));
        RouteResolver resolver =
                new RouteResolver(
                        redis,
                        authority,
                        generation,
                        () -> Mono.fromRunnable(reads::incrementAndGet),
                        mutable,
                        10,
                        1,
                        1000,
                        30000,
                        500);
        when(authority.find("s.example", "Ab", 1)).thenReturn(Mono.just(route(1)));
        assertEquals(1000, resolver.resolve("s.example", "Ab").block().authorityCheckedAt());
        now.set(1499);
        assertEquals(1, resolver.resolve("s.example", "Ab").block().routeVersion());
        assertEquals(1, reads.get()); // More than the full request budget remains.

        now.set(1999); // The proof is technically valid but only for one millisecond.
        when(redis.get("s.example", "Ab", 1)).thenReturn(Mono.just(route(1)));
        Sinks.One<RouteInfo> pending = Sinks.one();
        when(authority.find("s.example", "Ab", 1)).thenReturn(pending.asMono());
        var first = resolver.resolve("s.example", "Ab").toFuture();
        var second = resolver.resolve("s.example", "Ab").toFuture();
        RouteInfo refreshed =
                new RouteInfo(
                        "s.example",
                        "Ab",
                        1,
                        "1",
                        "g",
                        "https://new.example",
                        "ACTIVE",
                        null,
                        2,
                        1,
                        1999,
                        2999,
                        1);
        pending.tryEmitValue(refreshed);
        assertEquals(refreshed, first.join());
        assertEquals(refreshed, second.join());
        assertEquals(2, reads.get());
        verify(authority, times(2)).find("s.example", "Ab", 1);
        assertEquals(2000, route(1).validUntil());
    }

    @Test
    void publishingToRedisCannotConsumeTheReservedRequestBudget() {
        AtomicLong now = new AtomicLong(1000);
        Clock mutable = mock(Clock.class);
        when(mutable.millis()).thenAnswer(ignored -> now.get());
        when(redis.get(anyString(), anyString(), anyLong())).thenReturn(Mono.empty());
        when(authority.find("s.example", "Ab", 1)).thenReturn(Mono.just(route(1)));
        when(redis.put(any()))
                .thenReturn(
                        Mono.fromSupplier(
                                () -> {
                                    now.set(1500);
                                    return true;
                                }));
        RouteResolver resolver =
                new RouteResolver(
                        redis,
                        authority,
                        generation,
                        Mono::empty,
                        mutable,
                        10,
                        1,
                        1000,
                        30000,
                        500);
        StepVerifier.create(resolver.resolve("s.example", "Ab"))
                .expectError(AuthorityUnavailableException.class)
                .verify();
        verify(authority, times(1)).find("s.example", "Ab", 1);
        verify(redis)
                .put(route(1)); // Original proof is preserved even though it can no longer be used.
    }
}
