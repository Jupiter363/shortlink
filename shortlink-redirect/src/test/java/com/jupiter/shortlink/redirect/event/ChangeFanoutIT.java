package com.jupiter.shortlink.redirect.event;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.contract.*;
import com.jupiter.shortlink.redirect.TestConfig;
import com.jupiter.shortlink.redirect.cache.RouteResolver;
import com.jupiter.shortlink.redirect.config.RedirectProperties;
import com.jupiter.shortlink.redirect.risk.PolicyResolver;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class ChangeFanoutIT {
    private static RedirectProperties config(String id, String bootstrap) {
        var p = TestConfig.defaults();
        return new RedirectProperties(
                id,
                p.allowedHosts(),
                p.trustedProxyCidrs(),
                p.internalToken(),
                p.riskHashSalt(),
                p.commandBaseUrl(),
                p.authorityTtlMillis(),
                p.requestTimeoutMillis(),
                p.redisTimeoutMillis(),
                p.cacheEntries(),
                p.originConcurrency(),
                p.clusterOriginRate(),
                p.kafkaQueueCapacity(),
                p.kafkaQueueBytes(),
                p.eventMaxBytes(),
                bootstrap,
                p.clickBuckets(),
                p.generationPollMillis());
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        reactor.core.publisher.Flux.interval(Duration.ofMillis(50))
                .filter(ignored -> condition.getAsBoolean())
                .next()
                .block(Duration.ofSeconds(15));
    }

    private static String bootstrap() {
        String value = System.getenv("SHORTLINK_KAFKA_TEST_BOOTSTRAP");
        assertNotNull(value, "Explicit isolated Kafka required");
        return value;
    }

    private static List<org.apache.kafka.common.TopicPartition> partitions(Admin admin) throws Exception {
        var descriptions = admin.describeTopics(InvalidationBootstrap.TOPICS).allTopicNames()
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        List<org.apache.kafka.common.TopicPartition> answer = new ArrayList<>();
        for (String topic : InvalidationBootstrap.TOPICS) {
            assertEquals(2, descriptions.get(topic).partitions().size(), "Existing two-partition topics required");
            for (var part : descriptions.get(topic).partitions())
                answer.add(new org.apache.kafka.common.TopicPartition(topic, part.partition()));
        }
        return answer; // Read-only topology precondition: never create topics or add partitions.
    }

    private static KafkaProducer<String, String> producer(String bootstrap) {
        return new KafkaProducer<>(Map.of("bootstrap.servers", bootstrap,
                "key.serializer", StringSerializer.class.getName(),
                "value.serializer", StringSerializer.class.getName(), "acks", "all"));
    }

    private static Map<org.apache.kafka.common.TopicPartition, Long> sendHints(
            KafkaProducer<String, String> producer,
            List<org.apache.kafka.common.TopicPartition> partitions, String key, long version) throws Exception {
        Map<org.apache.kafka.common.TopicPartition, Long> offsets = new HashMap<>();
        for (var partition : partitions) {
            Object value = Topics.ROUTE_CHANGE.equals(partition.topic())
                    ? new RouteChangeV1(key, 1, System.currentTimeMillis(), "1", 1, version, "it.example", key)
                    : new RiskPolicyChangeV1(key, 1, System.currentTimeMillis(), "1", "1:" + key,
                            "AGGREGATE", version, null);
            var sent = producer.send(new ProducerRecord<>(partition.topic(), partition.partition(), key,
                    EventJson.write(value))).get(5, java.util.concurrent.TimeUnit.SECONDS);
            offsets.put(partition, sent.offset() + 1);
        }
        return offsets;
    }

    private static final class Seen {
        final RouteResolver routes = mock(RouteResolver.class);
        final PolicyResolver policies = mock(PolicyResolver.class);
        final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> counts =
                new java.util.concurrent.ConcurrentHashMap<>();
        Seen(String prefix) {
            when(routes.invalidate(anyString(), anyString(), anyLong())).thenAnswer(call -> {
                record(prefix, call.getArgument(1));
                return Mono.empty();
            });
            doAnswer(call -> {
                String resource = call.getArgument(0);
                if (resource.startsWith("1:")) record(prefix, resource.substring(2));
                return null;
            }).when(policies).invalidate(anyString(), anyLong());
        }
        void record(String prefix, String key) {
            if (key.startsWith(prefix)) counts.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
        }
        int count(String key) { return counts.getOrDefault(key, new AtomicInteger()).get(); }
    }

    private static final class TrackedFanout implements AutoCloseable {
        final ChangeFanout fanout;
        final String group;
        final java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        volatile Thread closingThread;
        boolean started;
        TrackedFanout(String id, String bootstrap, Seen seen) {
            group = "redirect-invalidation-" + id;
            fanout = new ChangeFanout(config(id, bootstrap), seen.routes, seen.policies, Map.of(), settings ->
                    new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(settings) {
                        @Override public void close(Duration timeout) {
                            closingThread = Thread.currentThread();
                            try { super.close(timeout); }
                            finally { closed.countDown(); }
                        }
                    });
        }
        void start() { fanout.start(); started = true; }
        @Override public void close() throws Exception {
            if (!started) return;
            fanout.stop();
            assertTrue(closed.await(5, java.util.concurrent.TimeUnit.SECONDS), "consumer.close must complete");
            closingThread.join(5000);
            assertFalse(closingThread.isAlive(), "Old consumer worker must terminate before same-group restart");
            System.out.println("FANOUT_IT_CLOSE " + EventJson.write(Map.of("group", group,
                    "consumerClosed", true, "workerTerminated", true)));
            started = false;
        }
    }

    private static boolean committed(Admin admin, String group,
                                     Map<org.apache.kafka.common.TopicPartition, Long> offsets) {
        try {
            var values = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                    .get(2, java.util.concurrent.TimeUnit.SECONDS);
            return offsets.entrySet().stream().allMatch(entry -> values.get(entry.getKey()) != null
                    && values.get(entry.getKey()).offset() >= entry.getValue());
        } catch (Exception failure) {
            throw new IllegalStateException("Test group offset query failed", failure);
        }
    }

    @Test
    void bothColdInstancesSkipExistingHistoryAndReceiveEveryNewRouteAndPolicyHint() throws Exception {
        String bootstrap = bootstrap();
        String id = "bootstrap-it-" + UUID.randomUUID().toString().replace("-", "");
        Seen a = new Seen(id), b = new Seen(id);
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrap));
             KafkaProducer<String, String> producer = producer(bootstrap);
             TrackedFanout first = new TrackedFanout("a-" + id, bootstrap, a);
             TrackedFanout second = new TrackedFanout("b-" + id, bootstrap, b)) {
            var partitions = partitions(admin);
            sendHints(producer, partitions, id + "-history", 1); // Four records before either group exists.
            first.start();
            second.start();
            await(() -> first.fanout.connected() && second.fanout.connected());
            assertEquals(0, a.count(id + "-history"));
            assertEquals(0, b.count(id + "-history"));
            var offsets = sendHints(producer, partitions, id + "-live", 2);
            await(() -> a.count(id + "-live") == 4 && b.count(id + "-live") == 4);
            await(() -> committed(admin, first.group, offsets) && committed(admin, second.group, offsets));
            assertEquals(0, a.count(id + "-history"));
            assertEquals(0, b.count(id + "-history"));
            verify(a.routes, times(1)).invalidateAll();
            verify(a.policies, times(1)).invalidateAll();
            verify(b.routes, times(1)).invalidateAll();
            verify(b.policies, times(1)).invalidateAll();
            System.out.println("FANOUT_IT_BROADCAST " + EventJson.write(Map.of("groups", List.of(first.group, second.group),
                    "historicalRecordsSkippedPerInstance", 4, "newHintsAppliedPerInstance", 4,
                    "committedOffsets", offsets.entrySet().stream().map(entry -> Map.of("topic", entry.getKey().topic(),
                            "partition", entry.getKey().partition(), "offset", entry.getValue())).toList())));
        }
    }

    @Test
    void newObjectWithExistingGroupResumesHintsProducedWhileItsOldConsumerWasClosed() throws Exception {
        String bootstrap = bootstrap();
        String id = "bootstrap-it-" + UUID.randomUUID().toString().replace("-", "");
        Seen initial = new Seen(id), resumed = new Seen(id);
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrap));
             KafkaProducer<String, String> producer = producer(bootstrap)) {
            var partitions = partitions(admin);
            String group = "redirect-invalidation-" + id;
            Map<org.apache.kafka.common.TopicPartition, Long> prior;
            try (TrackedFanout first = new TrackedFanout(id, bootstrap, initial)) {
                first.start();
                await(first.fanout::connected);
                prior = sendHints(producer, partitions, id + "-initial", 2);
                await(() -> initial.count(id + "-initial") == 4 && committed(admin, group, prior));
            } // Explicit consumer.close completion and worker join, not merely isRunning=false.
            var backlog = sendHints(producer, partitions, id + "-offline", 3);
            try (TrackedFanout next = new TrackedFanout(id, bootstrap, resumed)) {
                next.start();
                await(() -> next.fanout.connected() && resumed.count(id + "-offline") == 4);
                await(() -> committed(admin, group, backlog));
                assertEquals(0, resumed.count(id + "-initial"));
                verify(resumed.routes, never()).invalidateAll();
                verify(resumed.policies, never()).invalidateAll();
                System.out.println("FANOUT_IT_RESUME " + EventJson.write(Map.of("group", group,
                        "offlineHintsApplied", 4, "earlierHintsReplayed", 0, "newObject", true)));
            }
        }
    }
}