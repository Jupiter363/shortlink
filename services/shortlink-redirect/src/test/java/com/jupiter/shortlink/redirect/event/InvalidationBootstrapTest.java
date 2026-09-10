package com.jupiter.shortlink.redirect.event;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.contract.*;
import com.jupiter.shortlink.redirect.TestConfig;
import com.jupiter.shortlink.redirect.cache.*;
import com.jupiter.shortlink.redirect.risk.*;
import com.jupiter.shortlink.redirect.route.*;
import com.jupiter.shortlink.risk.PolicySnapshot;
import com.jupiter.shortlink.risk.PolicyState;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.WakeupException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

class InvalidationBootstrapTest {
    static final TopicPartition ROUTE = new TopicPartition(Topics.ROUTE_CHANGE, 0);
    static final TopicPartition RISK = new TopicPartition(Topics.RISK_POLICY_CHANGE, 0);
    static final Set<TopicPartition> ALL = Set.of(ROUTE, RISK);

    static final class Fixture {
        @SuppressWarnings("unchecked")
        final Consumer<String, String> consumer = mock(Consumer.class);
        final Map<TopicPartition, OffsetAndMetadata> committed = new HashMap<>();
        final Map<TopicPartition, Long> positions = new HashMap<>();
        final Map<TopicPartition, Long> ends = new HashMap<>();
        final AtomicReference<Set<TopicPartition>> assignment = new AtomicReference<>(ALL);
        final Runnable clear = mock(Runnable.class), disconnected = mock(Runnable.class);
        final AtomicLong nanos = new AtomicLong();
        final InvalidationBootstrap bootstrap;

        Fixture() {
            for (var partition : ALL) {
                committed.put(partition, null);
                positions.put(partition, 0L);
                ends.put(partition, 100L);
                when(consumer.partitionsFor(eq(partition.topic()), any(Duration.class)))
                        .thenReturn(List.of(new PartitionInfo(partition.topic(), 0, null, null, null)));
            }
            when(consumer.assignment()).thenAnswer(ignored -> assignment.get());
            when(consumer.committed(anySet(), any(Duration.class))).thenAnswer(ignored -> new HashMap<>(committed));
            when(consumer.endOffsets(anyCollection(), any(Duration.class))).thenAnswer(ignored -> new HashMap<>(ends));
            when(consumer.position(any(TopicPartition.class), any(Duration.class)))
                    .thenAnswer(call -> positions.get(call.getArgument(0)));
            doAnswer(call -> { positions.put(call.getArgument(0), call.getArgument(1)); return null; })
                    .when(consumer).seek(any(TopicPartition.class), anyLong());
            bootstrap = new InvalidationBootstrap(consumer, clear, disconnected, nanos::get);
        }
    }

    @Test
    void coldCompleteAssignmentCapturesOneFixedCutAndRewindsAnyFirstFetchedBatch() {
        Fixture f = new Fixture();
        f.bootstrap.onPartitionsAssigned(ALL);
        assertFalse(f.bootstrap.positionsReady());
        assertEquals(Map.of(ROUTE, 100L, RISK, 100L), f.positions);
        var order = inOrder(f.clear, f.consumer);
        order.verify(f.clear).run();
        order.verify(f.consumer).seek(RISK, 100L);
        f.positions.replaceAll((key, value) -> 125L); // poll advanced while returning a fetched batch.
        f.ends.replaceAll((key, value) -> 150L); // New hints arriving cannot move the selected cut.
        assertTrue(f.bootstrap.discardFetchedBootstrapBatch());
        assertEquals(Map.of(ROUTE, 100L, RISK, 100L), f.positions);
        assertTrue(f.bootstrap.positionsReady());
        assertFalse(f.bootstrap.discardFetchedBootstrapBatch());
        verify(f.consumer, times(1)).endOffsets(anyCollection(), any(Duration.class));
        verify(f.consumer, never()).commitSync(any(Duration.class));
        verify(f.clear, times(1)).run();
    }

    @Test
    void anyCommittedPartitionPreventsSkippingBothItsBacklogAndAnUncommittedPartition() {
        for (TopicPartition saved : ALL) {
            Fixture f = new Fixture();
            f.committed.put(saved, new OffsetAndMetadata(37));
            f.positions.put(saved, 37L);
            f.bootstrap.onPartitionsAssigned(ALL);
            assertTrue(f.bootstrap.positionsReady());
            assertEquals(37L, f.positions.get(saved));
            assertFalse(f.bootstrap.discardFetchedBootstrapBatch());
            verify(f.consumer, never()).endOffsets(anyCollection(), any(Duration.class));
            verify(f.consumer, never()).seek(any(TopicPartition.class), anyLong());
            verifyNoInteractions(f.clear);
        }
    }

    @Test
    void existingOffsetsAtTheEndOrResetByEarliestAreNeverReclassifiedAsCold() {
        for (long saved : new long[] {100, 1, 200}) {
            Fixture f = new Fixture();
            f.committed.put(ROUTE, new OffsetAndMetadata(saved));
            // Consumer supplies its ordinary resume/reset position, including retention fallback.
            f.positions.put(ROUTE, saved == 100 ? 100L : 5L);
            f.bootstrap.onPartitionsAssigned(ALL);
            assertTrue(f.bootstrap.positionsReady());
            verify(f.consumer, never()).seek(any(TopicPartition.class), anyLong());
            verify(f.consumer, never()).endOffsets(anyCollection(), any(Duration.class));
        }
    }

    @Test
    void partialFirstAssignmentConsumesColdEligibilityAndNeverAdvertisesCompleteBroadcast() {
        Fixture f = new Fixture();
        f.assignment.set(Set.of(ROUTE));
        f.bootstrap.onPartitionsAssigned(Set.of(ROUTE));
        assertFalse(f.bootstrap.positionsReady());
        f.assignment.set(ALL);
        f.bootstrap.onPartitionsAssigned(ALL);
        assertTrue(f.bootstrap.positionsReady());
        verify(f.consumer, never()).endOffsets(anyCollection(), any(Duration.class));
        verify(f.consumer, never()).seek(any(TopicPartition.class), anyLong());
    }

    @Test
    void laterRebalanceAndNewPartitionKeepNormalPositionsEvenWithoutCommits() {
        Fixture f = new Fixture();
        f.bootstrap.onPartitionsAssigned(ALL);
        assertTrue(f.bootstrap.discardFetchedBootstrapBatch());
        clearInvocations(f.consumer);
        f.bootstrap.onPartitionsRevoked(ALL);
        assertFalse(f.bootstrap.positionsReady());
        TopicPartition added = new TopicPartition(Topics.ROUTE_CHANGE, 1);
        Set<TopicPartition> expanded = Set.of(ROUTE, RISK, added);
        f.assignment.set(expanded);
        f.positions.put(added, 4L);
        when(f.consumer.partitionsFor(eq(Topics.ROUTE_CHANGE), any(Duration.class)))
                .thenReturn(List.of(new PartitionInfo(Topics.ROUTE_CHANGE, 0, null, null, null),
                        new PartitionInfo(Topics.ROUTE_CHANGE, 1, null, null, null)));
        f.bootstrap.onPartitionsAssigned(expanded);
        assertTrue(f.bootstrap.positionsReady());
        assertEquals(4L, f.positions.get(added));
        verify(f.consumer, never()).endOffsets(anyCollection(), any(Duration.class));
        verify(f.consumer, never()).seek(any(TopicPartition.class), anyLong());
    }

    @Test
    void incrementalCallbackCannotTreatAnAlreadyFullConsumerAssignmentAsACompleteColdAssignment() {
        Fixture f = new Fixture();
        f.bootstrap.onPartitionsAssigned(Set.of(ROUTE)); // Consumer.assignment still contains both topics.
        assertFalse(f.bootstrap.positionsReady());
        assertFalse(f.bootstrap.discardFetchedBootstrapBatch());
        f.bootstrap.onPartitionsAssigned(ALL);
        assertTrue(f.bootstrap.positionsReady());
        verify(f.consumer, never()).endOffsets(anyCollection(), any(Duration.class));
        verify(f.consumer, never()).seek(any(TopicPartition.class), anyLong());
    }

    @Test
    void rewindFailureOrChangedAssignmentCannotPublishReadinessOrCommitTheFetchedBatch() {
        for (boolean changedAssignment : new boolean[] {false, true}) {
            Fixture f = new Fixture();
            f.bootstrap.onPartitionsAssigned(ALL);
            if (changedAssignment) f.assignment.set(Set.of(ROUTE));
            else when(f.consumer.position(any(TopicPartition.class), any(Duration.class)))
                    .thenThrow(new TimeoutException("rewind timeout"));
            assertThrows(RuntimeException.class, f.bootstrap::discardFetchedBootstrapBatch);
            assertFalse(f.bootstrap.positionsReady());
            verify(f.consumer, never()).commitSync(any(Duration.class));
            verify(f.consumer, times(1)).endOffsets(anyCollection(), any(Duration.class));
        }
    }

    @Test
    void emptyCallbackDoesNotConsumeColdEligibilityAndLostAssignmentDisconnects() {
        Fixture f = new Fixture();
        f.bootstrap.onPartitionsAssigned(Set.of());
        assertFalse(f.bootstrap.positionsReady());
        f.bootstrap.onPartitionsAssigned(ALL);
        assertTrue(f.bootstrap.discardFetchedBootstrapBatch());
        f.bootstrap.onPartitionsLost(ALL);
        assertFalse(f.bootstrap.positionsReady());
        verify(f.disconnected, times(3)).run();
    }

    enum FailedApi { METADATA, COMMITTED, END_OFFSETS, SEEK, POSITION }

    @ParameterizedTest
    @EnumSource(FailedApi.class)
    void bootstrapApiFailureNeverReadiesOrCommits(FailedApi point) {
        Fixture f = new Fixture();
        TimeoutException failure = new TimeoutException("injected");
        switch (point) {
            case METADATA -> when(f.consumer.partitionsFor(anyString(), any(Duration.class))).thenThrow(failure);
            case COMMITTED -> when(f.consumer.committed(anySet(), any(Duration.class))).thenThrow(failure);
            case END_OFFSETS -> when(f.consumer.endOffsets(anyCollection(), any(Duration.class))).thenThrow(failure);
            case SEEK -> doThrow(failure).when(f.consumer).seek(any(TopicPartition.class), anyLong());
            case POSITION -> when(f.consumer.position(any(TopicPartition.class), any(Duration.class))).thenThrow(failure);
        }
        assertSame(failure, assertThrows(TimeoutException.class, () -> f.bootstrap.onPartitionsAssigned(ALL)));
        assertFalse(f.bootstrap.positionsReady());
        verify(f.consumer, never()).commitSync(any(Duration.class));
    }

    @Test
    void missingMetadataOrIncompleteBoundariesCannotSilentlyBecomeZeroOffsets() {
        for (String point : List.of("metadata", "committed", "end", "position")) {
            Fixture f = new Fixture();
            switch (point) {
                case "metadata" -> when(f.consumer.partitionsFor(eq(Topics.RISK_POLICY_CHANGE), any(Duration.class)))
                        .thenReturn(List.of());
                case "committed" -> f.committed.remove(RISK);
                case "end" -> f.ends.remove(RISK);
                case "position" -> when(f.consumer.position(eq(RISK), any(Duration.class))).thenReturn(99L);
            }
            assertThrows(IllegalStateException.class, () -> f.bootstrap.onPartitionsAssigned(ALL), point);
            assertFalse(f.bootstrap.positionsReady());
            verify(f.consumer, never()).commitSync(any(Duration.class));
        }
    }

    @Test
    void metadataAndPositionApisShareABoundedClockBudget() {
        Fixture f = new Fixture();
        when(f.consumer.partitionsFor(eq(Topics.ROUTE_CHANGE), any(Duration.class))).thenAnswer(call -> {
            assertEquals(Duration.ofSeconds(2), call.getArgument(1));
            f.nanos.set(Duration.ofSeconds(2).toNanos());
            return List.of(new PartitionInfo(Topics.ROUTE_CHANGE, 0, null, null, null));
        });
        assertThrows(IllegalStateException.class, () -> f.bootstrap.onPartitionsAssigned(ALL));
        assertFalse(f.bootstrap.positionsReady());
        verify(f.consumer, never()).committed(anySet(), any(Duration.class));
        verifyNoInteractions(f.clear);
    }

    @Test
    void actualPollLoopDiscardsOldBatchThenDeliversPostCutHintsAndCommitsOnlyThatBatch() throws Exception {
        Fixture f = new Fixture();
        RouteResolver routes = mock(RouteResolver.class);
        PolicyResolver policies = mock(PolicyResolver.class);
        when(routes.invalidate(anyString(), anyString(), anyLong())).thenReturn(Mono.empty());
        AtomicReference<ConsumerRebalanceListener> listener = new AtomicReference<>();
        doAnswer(call -> { listener.set(call.getArgument(1)); return null; })
                .when(f.consumer).subscribe(anyCollection(), any(ConsumerRebalanceListener.class));
        AtomicInteger polls = new AtomicInteger();
        CountDownLatch settled = new CountDownLatch(1), release = new CountDownLatch(1), closed = new CountDownLatch(1);
        var old = new ConsumerRecords<String, String>(Map.of(ROUTE, List.of(
                new ConsumerRecord<>(Topics.ROUTE_CHANGE, 0, 0, "old", "invalid old JSON"),
                routeRecord(100, "new"))));
        var current = new ConsumerRecords<String, String>(Map.of(ROUTE, List.of(routeRecord(100, "new")),
                RISK, List.of(policyRecord(100, "1:9"))));
        when(f.consumer.poll(any(Duration.class))).thenAnswer(ignored -> {
            int number = polls.incrementAndGet();
            if (number == 1) {
                listener.get().onPartitionsAssigned(ALL);
                f.positions.replaceAll((key, value) -> 101L);
                return old;
            }
            if (number == 2) {
                assertEquals(Map.of(ROUTE, 100L, RISK, 100L), f.positions);
                verify(f.consumer, never()).commitSync(any(Duration.class));
                return current;
            }
            settled.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            throw new WakeupException();
        });
        doAnswer(ignored -> { release.countDown(); return null; }).when(f.consumer).wakeup();
        doAnswer(ignored -> { closed.countDown(); return null; }).when(f.consumer).close(any(Duration.class));
        ChangeFanout fanout = new ChangeFanout(TestConfig.defaults(), routes, policies, Map.of(), settings -> {
            assertEquals("earliest", settings.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
            return f.consumer;
        });
        try {
            fanout.start();
            assertTrue(settled.await(3, TimeUnit.SECONDS));
            assertTrue(fanout.connected());
            verify(routes, times(1)).invalidate("s.example", "new", 2);
            verify(routes, times(1)).invalidateAll();
            verify(policies, times(1)).invalidateAll();
            verify(policies, times(1)).invalidate("1:9", 2);
            verify(f.consumer, times(1)).commitSync(Duration.ofSeconds(2));
            verify(f.consumer, times(1)).endOffsets(anyCollection(), any(Duration.class));
        } finally {
            fanout.stop();
            assertTrue(closed.await(3, TimeUnit.SECONDS));
        }
        assertFalse(fanout.connected());
    }

    @Test
    void failedAssignmentInActualLoopClosesWithoutProcessingOrCommittingFetchedData() throws Exception {
        Fixture f = new Fixture();
        RouteResolver routes = mock(RouteResolver.class);
        PolicyResolver policies = mock(PolicyResolver.class);
        when(f.consumer.endOffsets(anyCollection(), any(Duration.class))).thenThrow(new TimeoutException("injected"));
        AtomicReference<ConsumerRebalanceListener> listener = new AtomicReference<>();
        doAnswer(call -> { listener.set(call.getArgument(1)); return null; })
                .when(f.consumer).subscribe(anyCollection(), any(ConsumerRebalanceListener.class));
        when(f.consumer.poll(any(Duration.class))).thenAnswer(ignored -> {
            listener.get().onPartitionsAssigned(ALL);
            return new ConsumerRecords<>(Map.of(ROUTE, List.of(routeRecord(0, "old"))));
        });
        CountDownLatch closed = new CountDownLatch(1);
        doAnswer(ignored -> { closed.countDown(); return null; }).when(f.consumer).close(any(Duration.class));
        var fanout = new ChangeFanout(TestConfig.defaults(), routes, policies, Map.of(), ignored -> f.consumer);
        fanout.start();
        assertTrue(closed.await(3, TimeUnit.SECONDS));
        assertFalse(fanout.connected());
        verifyNoInteractions(routes, policies);
        verify(f.consumer, never()).commitSync(any(Duration.class));
    }

    @Test
    void realResolversAllowLateFlightRefillOnlyWithinOriginalAuthorityLeaseAfterBootstrapClear() {
        AtomicLong now = new AtomicLong(1000);
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenAnswer(ignored -> now.get());
        RedisRouteCache redis = mock(RedisRouteCache.class);
        RouteAuthority authority = mock(RouteAuthority.class);
        PolicyAuthority policyAuthority = mock(PolicyAuthority.class);
        CacheGeneration generation = mock(CacheGeneration.class);
        when(generation.current()).thenReturn(1L);
        when(redis.get(anyString(), anyString(), anyLong())).thenReturn(Mono.empty());
        when(redis.put(any())).thenReturn(Mono.just(true));
        var routes = new RouteResolver(redis, authority, generation, Mono::empty, clock, 10, 2, 1000, 30000, 500);
        var policies = new PolicyResolver(policyAuthority, Mono::empty, clock, 10, 2, 1000, 500);
        RouteInfo oldRoute = new RouteInfo("s.example", "Ab", 9, "1", "g", "https://target.example",
                "ACTIVE", null, 1, 1, 1000, 2000, 1);
        PolicySnapshot oldPolicy = new PolicySnapshot("1:9", 1, 1000, null, 2000,
                PolicyState.KNOWN_ALLOWED, false, true, "UTC", List.of(), Set.of(), null);
        Sinks.One<RouteInfo> routeFlight = Sinks.one();
        Sinks.One<PolicySnapshot> policyFlight = Sinks.one();
        when(authority.find("s.example", "Ab", 1)).thenReturn(routeFlight.asMono());
        when(policyAuthority.read("1", 9)).thenReturn(policyFlight.asMono());
        var firstRoute = routes.resolve("s.example", "Ab").toFuture();
        var firstPolicy = policies.resolve("1", 9).toFuture();
        Fixture f = new Fixture();
        var bootstrap = new InvalidationBootstrap(f.consumer, () -> {
            routes.invalidateAll();
            policies.invalidateAll();
        }, f.disconnected);
        bootstrap.onPartitionsAssigned(ALL);
        assertTrue(bootstrap.discardFetchedBootstrapBatch());
        now.set(1100);
        assertEquals(Sinks.EmitResult.OK, routeFlight.tryEmitValue(oldRoute));
        assertEquals(Sinks.EmitResult.OK, policyFlight.tryEmitValue(oldPolicy));
        assertSame(oldRoute, firstRoute.join());
        assertSame(oldPolicy, firstPolicy.join());
        now.set(1499);
        assertSame(oldRoute, routes.resolve("s.example", "Ab").block());
        assertSame(oldPolicy, policies.resolve("1", 9).block());
        routes.invalidateAll();
        when(redis.get("s.example", "Ab", 1)).thenReturn(Mono.just(oldRoute));
        assertSame(oldRoute, routes.resolve("s.example", "Ab").block()); // L2 also keeps original dates.
        assertEquals(1000, oldRoute.authorityCheckedAt());
        assertEquals(2000, oldRoute.validUntil());
        assertEquals(1000, oldPolicy.evaluatedAt());
        assertEquals(2000, oldPolicy.validUntil());
        when(authority.find("s.example", "Ab", 1)).thenReturn(Mono.just(oldRoute));
        when(policyAuthority.read("1", 9)).thenReturn(Mono.just(oldPolicy));
        for (long time : new long[] {1500, 2001}) {
            now.set(time); // Preserve both the 500ms processing reserve and the original 1s lease.
            StepVerifier.create(routes.resolve("s.example", "Ab")).expectError().verify();
            StepVerifier.create(policies.resolve("1", 9)).expectError().verify();
        }
        verify(redis, never()).invalidate(anyString(), anyString(), anyLong(), anyLong());
    }

    private static ConsumerRecord<String, String> routeRecord(long offset, String uri) {
        var value = new RouteChangeV1("event", 1, 1000, "1", 9, 2, "s.example", uri);
        return new ConsumerRecord<>(Topics.ROUTE_CHANGE, 0, offset, "route", EventJson.write(value));
    }

    private static ConsumerRecord<String, String> policyRecord(long offset, String resource) {
        var value = new RiskPolicyChangeV1("event", 1, 1000, "1", resource, "AGGREGATE", 2, null);
        return new ConsumerRecord<>(Topics.RISK_POLICY_CHANGE, 0, offset, "risk", EventJson.write(value));
    }
}
