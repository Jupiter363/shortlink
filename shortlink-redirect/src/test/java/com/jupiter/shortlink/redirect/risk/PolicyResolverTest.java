package com.jupiter.shortlink.redirect.risk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.risk.*;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.*;
import java.util.*;

class PolicyResolverTest {
    private PolicySnapshot snapshot(long revision, long checked, long until) {
        return new PolicySnapshot(
                "1:5",
                revision,
                checked,
                null,
                until,
                PolicyState.KNOWN_ALLOWED,
                false,
                true,
                "UTC",
                List.of(),
                Set.of(),
                null);
    }

    private PolicyResolver resolver(PolicyAuthority authority, int flights) {
        return new PolicyResolver(
                authority,
                Mono::empty,
                Clock.fixed(Instant.ofEpochMilli(1500), ZoneOffset.UTC),
                10,
                flights,
                1000,
                100);
    }

    @Test
    void unknownForeignExpiredAndExtendedProofsFailClosed() {
        for (PolicySnapshot value :
                List.of(
                        snapshot(1, 100, 1100),
                        snapshot(1, 1000, 2100),
                        snapshot(1, 1600, 2000),
                        new PolicySnapshot(
                                "2:5",
                                1,
                                1000,
                                null,
                                2000,
                                PolicyState.KNOWN_ALLOWED,
                                false,
                                true,
                                "UTC",
                                List.of(),
                                Set.of(),
                                null))) {
            PolicyAuthority source = mock(PolicyAuthority.class);
            when(source.read("1", 5)).thenReturn(Mono.just(value));
            StepVerifier.create(resolver(source, 2).resolve("1", 5)).expectError().verify();
        }
    }

    @Test
    void knownAllowedCachesOnlyUntilOriginalAuthorityBoundary() {
        PolicyAuthority source = mock(PolicyAuthority.class);
        when(source.read("1", 5)).thenReturn(Mono.just(snapshot(1, 1000, 2000)));
        PolicyResolver resolver = resolver(source, 2);
        assertEquals(1, resolver.resolve("1", 5).block().policyRevision());
        assertEquals(1, resolver.resolve("1", 5).block().policyRevision());
        verify(source, times(1)).read("1", 5);
    }

    @Test
    void delayedOldSnapshotCannotWinAfterNewerHint() {
        PolicyAuthority source = mock(PolicyAuthority.class);
        Sinks.One<PolicySnapshot> pending = Sinks.one();
        when(source.read("1", 5)).thenReturn(pending.asMono());
        PolicyResolver resolver = resolver(source, 2);
        StepVerifier.create(resolver.resolve("1", 5))
                .then(
                        () -> {
                            resolver.invalidate("1:5", 2);
                            pending.tryEmitValue(snapshot(1, 1000, 2000));
                        })
                .expectError()
                .verify();
    }

    @Test
    void sameResourceMergesAndUniqueFlightsAreBounded() {
        PolicyAuthority source = mock(PolicyAuthority.class);
        Sinks.One<PolicySnapshot> pending = Sinks.one();
        when(source.read("1", 5)).thenReturn(pending.asMono());
        PolicyResolver resolver = resolver(source, 1);
        var first = resolver.resolve("1", 5).subscribe();
        StepVerifier.create(resolver.resolve("1", 6)).expectError().verify();
        var second = resolver.resolve("1", 5).subscribe();
        pending.tryEmitValue(snapshot(1, 1000, 2000));
        verify(source, times(1)).read("1", 5);
        first.dispose();
        second.dispose();
    }

    @Test
    void refreshesAtTheRemainingRequestBudgetWithoutExtendingTheCachedProof() {
        var now = new java.util.concurrent.atomic.AtomicLong(1000);
        var budget = new java.util.concurrent.atomic.AtomicInteger();
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenAnswer(ignored -> now.get());
        PolicyAuthority source = mock(PolicyAuthority.class);
        when(source.read("1", 5))
                .thenAnswer(ignored -> Mono.just(snapshot(1, now.get(), now.get() + 1000)));
        PolicyResolver resolver =
                new PolicyResolver(
                        source,
                        () -> Mono.fromRunnable(budget::incrementAndGet),
                        clock,
                        10,
                        1,
                        1000,
                        500);
        PolicySnapshot initial = resolver.resolve("1", 5).block();
        now.set(1499);
        assertSame(initial, resolver.resolve("1", 5).block());
        now.set(1500);
        PolicySnapshot refreshed = resolver.resolve("1", 5).block();
        assertEquals(1500, refreshed.evaluatedAt());
        assertEquals(2500, refreshed.validUntil());
        assertEquals(2000, initial.validUntil());
        assertEquals(2, budget.get());
        verify(source, times(2)).read("1", 5);
    }

    @Test
    void aSlowAuthorityResponseCannotSpendTheProcessingReserveOrTriggerUnlimitedRetry() {
        var now = new java.util.concurrent.atomic.AtomicLong(1000);
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenAnswer(ignored -> now.get());
        PolicyAuthority source = mock(PolicyAuthority.class);
        when(source.read("1", 5))
                .thenReturn(
                        Mono.fromSupplier(
                                () -> {
                                    now.set(1500);
                                    return snapshot(1, 1000, 2000);
                                }));
        PolicyResolver resolver = new PolicyResolver(source, Mono::empty, clock, 10, 1, 1000, 500);
        StepVerifier.create(resolver.resolve("1", 5)).expectError().verify();
        verify(source, times(1)).read("1", 5);
    }

    @Test
    void scheduledTransitionCanShortenAFreshProofWithoutCreatingAnEarlyOutage() {
        var now = new java.util.concurrent.atomic.AtomicLong(1500);
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenAnswer(ignored -> now.get());
        PolicyAuthority source = mock(PolicyAuthority.class);
        when(source.read("1", 5))
                .thenAnswer(
                        ignored ->
                                Mono.just(
                                        now.get() < 1600
                                                ? new PolicySnapshot(
                                                        "1:5",
                                                        1,
                                                        now.get(),
                                                        1600L,
                                                        1600,
                                                        PolicyState.KNOWN_ALLOWED,
                                                        false,
                                                        true,
                                                        "UTC",
                                                        List.of(),
                                                        Set.of(),
                                                        null)
                                                : snapshot(2, now.get(), now.get() + 1000)));
        PolicyResolver resolver = new PolicyResolver(source, Mono::empty, clock, 10, 1, 1000, 500);
        PolicySnapshot clipped = resolver.resolve("1", 5).block();
        assertEquals(1600, clipped.validUntil());
        now.set(1599);
        assertSame(clipped, resolver.resolve("1", 5).block());
        verify(source, times(1)).read("1", 5);
        now.set(1600);
        PolicySnapshot afterTransition = resolver.resolve("1", 5).block();
        assertEquals(2, afterTransition.policyRevision());
        assertEquals(1600, afterTransition.evaluatedAt());
        verify(source, times(2)).read("1", 5);
    }

    @Test
    void aTransitionHintCannotLaunderAnOldObservationOrAShortUnrelatedProof() {
        for (long transition : new long[] {1600, 1700}) {
            PolicyAuthority source = mock(PolicyAuthority.class);
            when(source.read("1", 5))
                    .thenReturn(
                            Mono.just(
                                    new PolicySnapshot(
                                            "1:5",
                                            1,
                                            transition == 1600 ? 1000 : 1500,
                                            transition,
                                            1600,
                                            PolicyState.KNOWN_ALLOWED,
                                            false,
                                            true,
                                            "UTC",
                                            List.of(),
                                            Set.of(),
                                            null)));
            PolicyResolver resolver =
                    new PolicyResolver(
                            source,
                            Mono::empty,
                            Clock.fixed(Instant.ofEpochMilli(1500), ZoneOffset.UTC),
                            10,
                            1,
                            1000,
                            500);
            StepVerifier.create(resolver.resolve("1", 5)).expectError().verify();
        }
    }
}
