package com.jupiter.shortlink.redirect.event;

import com.jupiter.shortlink.contract.Topics;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** Consumer-thread-only positioning for the two invalidation hint topics, never statistics. */
final class InvalidationBootstrap implements ConsumerRebalanceListener {
    static final List<String> TOPICS = List.of(Topics.ROUTE_CHANGE, Topics.RISK_POLICY_CHANGE);
    // Each assignment/rewind phase has its own budget; a cold start can use both phases.
    static final Duration API_BUDGET = Duration.ofSeconds(2);
    private final Consumer<String, String> consumer;
    private final Runnable clearLocalValues;
    private final Runnable disconnected;
    private final LongSupplier clock;
    private boolean firstAssignment = true;
    private boolean positionsReady;
    private Map<TopicPartition, Long> pendingCut = Map.of();

    InvalidationBootstrap(Consumer<String, String> consumer, Runnable clearLocalValues,
                          Runnable disconnected) {
        this(consumer, clearLocalValues, disconnected, System::nanoTime);
    }

    InvalidationBootstrap(Consumer<String, String> consumer, Runnable clearLocalValues,
                          Runnable disconnected, LongSupplier clock) {
        this.consumer = consumer;
        this.clearLocalValues = clearLocalValues;
        this.disconnected = disconnected;
        this.clock = clock;
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        positionsReady = false;
        disconnected.run();
        pendingCut = Map.of();
        Set<TopicPartition> assigned = Set.copyOf(partitions);
        if (assigned.isEmpty()) return;
        boolean eligible = firstAssignment;
        firstAssignment = false; // Partial or recovering assignments consume the one cold decision.
        long began = clock.getAsLong();
        boolean complete = assigned.equals(consumer.assignment())
                && assigned.equals(expectedPartitions(began));
        if (eligible && complete) {
            Map<TopicPartition, OffsetAndMetadata> committed =
                    consumer.committed(assigned, remaining(began));
            require(committed.keySet().equals(assigned), "INVALIDATION_COMMITTED_PARTITIONS");
            if (committed.values().stream().allMatch(value -> value == null)) {
                Map<TopicPartition, Long> cut = consumer.endOffsets(assigned, remaining(began));
                require(cut.keySet().equals(assigned)
                                && cut.values().stream().allMatch(value -> value != null && value >= 0),
                        "INVALIDATION_END_PARTITIONS");
                pendingCut = Map.copyOf(cut); // Capture once; later hints cannot move this cut.
                // This clears values only, not in-flight proofs, version floors or shared L2.
                // The original authority lease still bounds the skipped-hint interval.
                clearLocalValues.run();
                seekAndVerify(pendingCut, began);
                return; // The poll containing this callback must be discarded before readiness.
            }
        }
        verifyPositions(assigned, Map.of(), began);
        positionsReady = complete;
    }

    private Set<TopicPartition> expectedPartitions(long began) {
        Set<TopicPartition> expected = new HashSet<>();
        for (String topic : TOPICS) {
            List<PartitionInfo> infos = consumer.partitionsFor(topic, remaining(began));
            require(infos != null && !infos.isEmpty(), "INVALIDATION_TOPIC_METADATA_MISSING");
            for (PartitionInfo info : infos) {
                require(topic.equals(info.topic()) && info.partition() >= 0
                                && expected.add(new TopicPartition(topic, info.partition())),
                        "INVALIDATION_TOPIC_METADATA_INVALID");
            }
        }
        return expected;
    }

    /**
     * A poll may already contain records around a rebalance callback. Rewind to the same cut
     * and discard that entire batch, including any post-cut records; the next poll rereads them.
     * No bootstrap commit is manufactured for hints that were deliberately not processed.
     */
    boolean discardFetchedBootstrapBatch() {
        if (pendingCut.isEmpty()) return false;
        positionsReady = false;
        require(consumer.assignment().equals(pendingCut.keySet()), "INVALIDATION_ASSIGNMENT_CHANGED");
        seekAndVerify(pendingCut, clock.getAsLong());
        pendingCut = Map.of();
        positionsReady = true;
        return true;
    }

    boolean positionsReady() {
        return positionsReady;
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        positionsReady = false;
        pendingCut = Map.of();
        disconnected.run();
    }

    @Override
    public void onPartitionsLost(Collection<TopicPartition> partitions) {
        onPartitionsRevoked(partitions);
    }

    private void seekAndVerify(Map<TopicPartition, Long> cut, long began) {
        for (TopicPartition partition : sorted(cut.keySet())) {
            remaining(began);
            consumer.seek(partition, cut.get(partition));
        }
        verifyPositions(cut.keySet(), cut, began);
    }

    private void verifyPositions(Set<TopicPartition> partitions,
                                 Map<TopicPartition, Long> expected, long began) {
        for (TopicPartition partition : sorted(partitions)) {
            long position = consumer.position(partition, remaining(began));
            require(position >= 0 && (!expected.containsKey(partition)
                            || position == expected.get(partition)),
                    "INVALIDATION_POSITION_NOT_ESTABLISHED");
        }
        remaining(began);
    }

    private static List<TopicPartition> sorted(Set<TopicPartition> partitions) {
        return partitions.stream().sorted(Comparator.comparing(TopicPartition::topic)
                .thenComparingInt(TopicPartition::partition)).toList();
    }

    private Duration remaining(long began) {
        long elapsed = clock.getAsLong() - began;
        require(elapsed >= 0 && elapsed < API_BUDGET.toNanos(), "INVALIDATION_BOOTSTRAP_TIMEOUT");
        return Duration.ofNanos(API_BUDGET.toNanos() - elapsed);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }
}
