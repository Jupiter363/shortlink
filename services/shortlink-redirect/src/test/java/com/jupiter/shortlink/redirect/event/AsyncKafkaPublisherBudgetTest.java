package com.jupiter.shortlink.redirect.event;

import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.redirect.TestConfig;
import com.jupiter.shortlink.redirect.config.RedirectProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

class AsyncKafkaPublisherBudgetTest {
    private static final String TOPIC = "test-topic";

    static Stream<String> unicodeKeys() {
        return Stream.of("ascii", "café", "租户", "\uD83D\uDE80", "\uD800", "\uDC00",
                "租\uD83D\uDE80\uD800x\uDC00");
    }

    @ParameterizedTest
    @MethodSource("unicodeKeys")
    void exactSerializedByteBoundaryPreservesWireBytesAndReusesEncodingOnRetry(String key) {
        String body = bodyAtSize(key, 1024);
        var producer = producer();
        var meters = new SimpleMeterRegistry();
        var lane = new AsyncKafkaPublisher.Lane("click", producer, config(2, 2048, 1024), meters);
        try {
            assertEquals(1024, serializedSize(key, body));
            assertTrue(lane.offer(TOPIC, key, body));
            await(() -> producer.history().size() == 1);
            assertEquals(1024, pendingBytes(meters));
            assertFalse(lane.offer(TOPIC, key, body + "x"));
            assertEquals(1, reason(meters, "event_size"));
            assertEquals(1025, meters.get("shortlink.events.diagnostic.first.rejection")
                    .tag("field", "event_bytes").gauge().value());

            assertTrue(producer.errorNext(new RuntimeException("test ACK unknown")));
            await(() -> producer.history().size() == 2);
            assertEquals(1024, pendingBytes(meters));
            for (var record : producer.history()) {
                assertArrayEquals(new StringSerializer().serialize(TOPIC, key),
                        record.key());
                assertArrayEquals(new StringSerializer().serialize(TOPIC, body),
                        record.value());
                assertSame(producer.history().get(0).key(), record.key(), "retry reuses encoded key");
                assertSame(producer.history().get(0).value(), record.value(), "retry reuses encoded body");
                assertSame(record.key(), new ByteArraySerializer().serialize(TOPIC, record.key()));
                assertSame(record.value(), new ByteArraySerializer().serialize(TOPIC, record.value()));
            }
            assertTrue(producer.completeNext());
            assertEquals(Map.of("attempted", 2L, "delivered", 1L, "failed", 0L,
                    "rejected", 1L, "pending", 0L), lane.quality());
            assertEquals(0, pendingBytes(meters));
        } finally {
            lane.close();
        }
    }

    @Test
    void combinedByteBudgetIncludesNonAsciiKeyAndIsReleasedOnlyAfterAck() {
        String key = "租\uD83D\uDE80\uD800";
        String body = bodyAtSize(key, 768);
        var producer = producer();
        var meters = new SimpleMeterRegistry();
        var lane = new AsyncKafkaPublisher.Lane("click", producer, config(4, 1536, 1024), meters);
        try {
            assertTrue(lane.offer(TOPIC, key, body));
            assertTrue(lane.offer(TOPIC, key, body));
            await(() -> producer.history().size() == 2);
            assertEquals(1536, pendingBytes(meters));
            assertFalse(lane.offer(TOPIC, key, body));
            assertEquals(1, reason(meters, "bytes"));
            assertEquals(0, reason(meters, "slots"));
            assertTrue(producer.completeNext());
            assertEquals(768, pendingBytes(meters));
            assertTrue(lane.offer(TOPIC, key, body));
            await(() -> producer.history().size() == 3);
            assertTrue(producer.completeNext());
            assertTrue(producer.completeNext());
            assertEquals(Map.of("attempted", 4L, "delivered", 3L, "failed", 0L,
                    "rejected", 1L, "pending", 0L), lane.quality());
            assertEquals(0, pendingBytes(meters));
        } finally {
            lane.close();
        }
    }

    @Test
    void largeUnicodeRejectionDoesNotConsumeEitherReservation() {
        String key = "域\uDC00";
        String body = "汉\uD83D\uDE00".repeat(65536);
        var producer = producer();
        var meters = new SimpleMeterRegistry();
        var lane = new AsyncKafkaPublisher.Lane("click", producer, config(1, 1024, 1024), meters);
        try {
            assertFalse(lane.offer(TOPIC, key, body));
            assertEquals(serializedSize(key, body), meters.get("shortlink.events.diagnostic.first.rejection")
                    .tag("field", "event_bytes").gauge().value());
            assertEquals(0, pendingBytes(meters));
            assertEquals(0, lane.quality().get("pending"));
            assertTrue(producer.history().isEmpty());
            assertTrue(lane.offer(TOPIC, key, bodyAtSize(key, 1024)));
            await(() -> producer.history().size() == 1);
            assertTrue(producer.completeNext());
            assertEquals(1, reason(meters, "event_size"));
            assertEquals(0, lane.quality().get("pending"));
        } finally {
            lane.close();
        }
    }

    @Test
    void concurrentAdmissionAndDuplicateCallbacksConserveCountAndBytes() throws Exception {
        var producer = new SignallingProducer();
        var meters = new SimpleMeterRegistry();
        var lane = new AsyncKafkaPublisher.Lane("click", producer, config(4, 4096, 1024), meters);
        var executor = Executors.newFixedThreadPool(9);
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        var offersDone = new CountDownLatch(8);
        var firstSettlement = new CountDownLatch(1);
        var admissionsFinished = new AtomicBoolean();
        var settledDuringAdmissions = new AtomicBoolean();
        var accepted = new AtomicInteger(1);
        List<Future<?>> offers = new ArrayList<>();
        try {
            assertTrue(lane.offer(TOPIC, "seed", bodyAtSize("seed", 1024)));
            await(() -> producer.history().size() == 1);
            for (int worker = 0; worker < 8; worker++) {
                final String key = "键\uD83D\uDE80\uD800-" + worker;
                final String body = bodyAtSize(key, 1024);
                offers.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        assertTrue(start.await(2, TimeUnit.SECONDS));
                        for (int i = 0; i < 8; i++) {
                            if (lane.offer(TOPIC, key, body)) accepted.incrementAndGet();
                            if (i == 0) assertTrue(firstSettlement.await(2, TimeUnit.SECONDS));
                        }
                    } finally {
                        offersDone.countDown();
                    }
                    return null;
                }));
            }
            Future<?> callbacks = executor.submit(() -> {
                assertTrue(start.await(2, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!admissionsFinished.get() || lane.quality().get("pending") != 0) {
                    assertTrue(System.nanoTime() < deadline, "bounded callback drain");
                    if (producer.sent.tryAcquire(20, TimeUnit.MILLISECONDS)) {
                        assertTrue(producer.completeNext());
                        if (offersDone.getCount() != 0) settledDuringAdmissions.set(true);
                        firstSettlement.countDown();
                    }
                }
                return null;
            });
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> offer : offers) offer.get(5, TimeUnit.SECONDS);
            admissionsFinished.set(true);
            callbacks.get(5, TimeUnit.SECONDS);

            assertTrue(settledDuringAdmissions.get(), "ACKs must overlap the admission wave");
            assertEquals(Map.of("attempted", 65L, "delivered", (long) accepted.get(), "failed", 0L,
                    "rejected", 65L - accepted.get(), "pending", 0L), lane.quality());
            assertEquals(65 - accepted.get(), reason(meters, "slots"));
            assertEquals(0, reason(meters, "bytes"));
            assertEquals(0, reason(meters, "event_size"));
            assertEquals(0, pendingBytes(meters));
            assertTrue(meters.get("shortlink.events.diagnostic.high.watermark")
                    .tag("kind", "count").gauge().value() <= 4);
            assertTrue(meters.get("shortlink.events.diagnostic.high.watermark")
                    .tag("kind", "bytes").gauge().value() <= 4096);
        } finally {
            start.countDown();
            firstSettlement.countDown();
            admissionsFinished.set(true);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            lane.close();
        }
    }

    private static String bodyAtSize(String key, int size) {
        String content = "café汉\uD83D\uDE00\uD800x\uDC00";
        return content + "a".repeat(size - serializedSize(key, content));
    }

    private static int serializedSize(String key, String body) {
        var serializer = new StringSerializer();
        return serializer.serialize(TOPIC, key).length + serializer.serialize(TOPIC, body).length;
    }

    private static double pendingBytes(SimpleMeterRegistry meters) {
        return meters.get("shortlink.events.pending.bytes").gauge().value();
    }

    private static double reason(SimpleMeterRegistry meters, String reason) {
        return meters.get("shortlink.events.rejected.reason").tag("reason", reason).counter().count();
    }

    private static MockProducer<byte[], byte[]> producer() {
        return new MockProducer<>(false, new ByteArraySerializer(), new ByteArraySerializer());
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        reactor.core.publisher.Flux.interval(Duration.ofMillis(2))
                .filter(ignored -> condition.getAsBoolean()).next().block(Duration.ofSeconds(2));
    }

    private static RedirectProperties config(int capacity, long bytes, int eventLimit) {
        var c = TestConfig.defaults();
        return new RedirectProperties(c.instanceId(), c.allowedHosts(), c.trustedProxyCidrs(), c.internalToken(),
                c.riskHashSalt(), c.commandBaseUrl(), c.authorityTtlMillis(), c.requestTimeoutMillis(), c.redisTimeoutMillis(),
                c.cacheEntries(), c.originConcurrency(), c.clusterOriginRate(), capacity, bytes, eventLimit,
                c.kafkaBootstrap(), c.clickBuckets(), c.generationPollMillis());
    }

    private static final class SignallingProducer extends MockProducer<byte[], byte[]> {
        final Semaphore sent = new Semaphore(0);

        SignallingProducer() {
            super(false, new ByteArraySerializer(), new ByteArraySerializer());
        }

        @Override
        public Future<RecordMetadata> send(ProducerRecord<byte[], byte[]> record, Callback callback) {
            Future<RecordMetadata> future = super.send(record, (metadata, error) -> {
                callback.onCompletion(metadata, error);
                callback.onCompletion(metadata, error);
            });
            sent.release();
            return future;
        }
    }
}
