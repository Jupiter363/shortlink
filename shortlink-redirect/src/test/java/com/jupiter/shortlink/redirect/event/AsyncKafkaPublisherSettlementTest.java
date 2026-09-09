package com.jupiter.shortlink.redirect.event;

import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.contract.ClickEventV1;
import com.jupiter.shortlink.redirect.TestConfig;
import com.jupiter.shortlink.redirect.config.RedirectProperties;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class AsyncKafkaPublisherSettlementTest {
    @Test
    void successfulAckAndQualityFinishWhilePublicOfferIsBlockedInsideRejectedCounter() throws Exception {
        var meters = new BlockingRejectedRegistry();
        var producer = new ControlledProducer();
        var executor = Executors.newFixedThreadPool(3);
        try (var publisher = new AsyncKafkaPublisher(TestConfig.defaults(), meters, producer, automatic())) {
            assertTrue(publisher.click(event("")));
            Delivery delivery = producer.next();
            Future<Boolean> rejection = executor.submit(() -> publisher.click(event("x".repeat(20000))));
            assertTrue(meters.entered.await(2, TimeUnit.SECONDS));

            // The real public offer owns the original admission monitor inside a slow diagnostic.
            executor.submit(() -> delivery.callback.onCompletion(null, null)).get(2, TimeUnit.SECONDS);
            var quality = executor.submit(() -> clickQuality(publisher)).get(2, TimeUnit.SECONDS);
            assertEquals(Map.of("attempted", 2L, "delivered", 1L, "failed", 0L,
                    "rejected", 1L, "pending", 0L), quality);
            assertEquals(0, pendingBytes(meters));
            assertEquals(0, meters.get("shortlink.events.rejected").tag("lane", "click").counter().count(),
                    "diagnostic counters may lag the authoritative state while their increment is blocked");
            meters.release.countDown();
            assertFalse(rejection.get(2, TimeUnit.SECONDS));
            assertEquals(1, meters.get("shortlink.events.rejected").tag("lane", "click").counter().count());
            delivery.callback.onCompletion(null, null);
            assertEquals(quality, clickQuality(publisher), "duplicate ACK cannot release or deliver twice");
        } finally {
            meters.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void closeWaitsForAdmissionPublicationButDoesNotHoldItsMonitorAcrossProducerClose() throws Exception {
        var meters = new BlockingRejectedRegistry();
        var producer = new ControlledProducer();
        producer.closeCallbacksOnOtherThread = true;
        var lane = new AsyncKafkaPublisher.Lane("click", producer, TestConfig.defaults(), meters);
        var executor = Executors.newSingleThreadExecutor();
        Thread closer = new Thread(lane::close);
        try {
            assertTrue(lane.offer("topic", "key", "body"));
            producer.next();
            Future<Boolean> rejected = executor.submit(() -> lane.offer("topic", "key", "x".repeat(20000)));
            assertTrue(meters.entered.await(2, TimeUnit.SECONDS));
            closer.start();
            await(() -> closer.getState() == Thread.State.BLOCKED);
            assertEquals(0, producer.closeCalls.get());
            meters.release.countDown();
            assertFalse(rejected.get(2, TimeUnit.SECONDS));
            closer.join(4000);
            assertFalse(closer.isAlive());
            assertTrue(producer.closeCallbackFinished.get(), "failed ACK must acquire the free admission monitor during close");
            assertEquals(Map.of("attempted", 2L, "delivered", 0L, "failed", 1L,
                    "rejected", 1L, "pending", 0L), lane.quality());
            assertFalse(lane.offer("topic", "key", "late"));
            assertEquals(1, producer.deliveries.size());
            assertEquals(0, pendingBytes(meters));
        } finally {
            meters.release.countDown();
            closer.join(4000);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            if (producer.closeCalls.get() == 0) lane.close();
        }
    }

    @Test
    void failedCallbackWaitingForAdmissionCannotPublishRetryAfterClose() throws Exception {
        var producer = new ControlledProducer();
        var meters = new SimpleMeterRegistry();
        var lane = new AsyncKafkaPublisher.Lane("click", producer, TestConfig.defaults(), meters);
        assertTrue(lane.offer("topic", "key", "body"));
        Delivery delivery = producer.next();
        var failure = new FutureTask<Void>(() -> {
            delivery.callback.onCompletion(null, new IllegalStateException("ACK unknown"));
            return null;
        });
        Thread callback = new Thread(failure);
        try {
            synchronized (lane) {
                callback.start();
                await(() -> callback.getState() == Thread.State.BLOCKED);
                lane.close();
            }
            failure.get(2, TimeUnit.SECONDS);
            assertEquals(1, producer.deliveries.size(), "a callback waiting before shutdown must not requeue afterwards");
            assertEquals(Map.of("attempted", 1L, "delivered", 0L, "failed", 1L,
                    "rejected", 0L, "pending", 0L), lane.quality());
            assertEquals(0, pendingBytes(meters));
        } finally {
            callback.join(2000);
            if (producer.closeCalls.get() == 0) lane.close();
        }
    }

    @Test
    void retryPublishedBeforeCloseIsSettledWithoutResurrectingTheQueue() throws Exception {
        var producer = new ControlledProducer();
        var meters = new SimpleMeterRegistry();
        var lane = new AsyncKafkaPublisher.Lane("click", producer, TestConfig.defaults(), meters);
        try {
            assertTrue(lane.offer("topic", "key", "body"));
            Delivery first = producer.next();
            first.callback.onCompletion(null, new IllegalStateException("ACK unknown"));
            Delivery retry = producer.next();
            assertSame(first.record.key(), retry.record.key());
            assertSame(first.record.value(), retry.record.value());
            assertEquals(1, lane.quality().get("pending"));
            lane.close();
            assertEquals(2, producer.deliveries.size());
            assertEquals(1, lane.quality().get("failed"));
            assertEquals(0, lane.quality().get("pending"));
            retry.callback.onCompletion(null, null);
            assertEquals(0, lane.quality().get("delivered"));
            assertEquals(0, pendingBytes(meters));
        } finally {
            if (producer.closeCalls.get() == 0) lane.close();
        }
    }

    @Test
    void threeFailedAttemptsKeepExactReservationThenSettleOnce() throws Exception {
        var producer = new ControlledProducer();
        var meters = new SimpleMeterRegistry();
        var lane = new AsyncKafkaPublisher.Lane("click", producer, TestConfig.defaults(), meters);
        try {
            assertTrue(lane.offer("topic", "key", "body"));
            Delivery first = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                Delivery sent = producer.next();
                if (first == null) first = sent;
                assertSame(first.record.key(), sent.record.key());
                assertSame(first.record.value(), sent.record.value());
                assertEquals(7, pendingBytes(meters));
                assertEquals(1, lane.quality().get("pending"));
                sent.callback.onCompletion(null, new IllegalStateException("ACK unknown"));
                sent.callback.onCompletion(null, null);
            }
            assertEquals(Map.of("attempted", 1L, "delivered", 0L, "failed", 1L,
                    "rejected", 0L, "pending", 0L), lane.quality());
            assertEquals(0, pendingBytes(meters));
            assertEquals(3, producer.deliveries.size());
        } finally { lane.close(); }
    }

    @Test
    void simultaneousOffersCallbacksAndQualityReadsNeverBreakConservationOrEitherBudget() throws Exception {
        var producer = new ControlledProducer();
        var meters = new SimpleMeterRegistry();
        var lane = new AsyncKafkaPublisher.Lane("click", producer, config(), meters);
        var executor = Executors.newFixedThreadPool(6);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(4);
        var accepted = new AtomicInteger();
        var observations = new AtomicInteger();
        List<Future<?>> offers = new ArrayList<>();
        try {
            for (int worker = 0; worker < 4; worker++) {
                offers.add(executor.submit(() -> {
                    assertTrue(start.await(2, TimeUnit.SECONDS));
                    try {
                        for (int i = 0; i < 100; i++) {
                            if (lane.offer("topic", "k", "x".repeat(511))) accepted.incrementAndGet();
                        }
                    } finally { done.countDown(); }
                    return null;
                }));
            }
            Future<?> acknowledgements = executor.submit(() -> {
                assertTrue(start.await(2, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (done.getCount() > 0 || lane.quality().get("pending") > 0) {
                    assertTrue(System.nanoTime() < deadline);
                    Delivery sent = producer.pending.poll(10, TimeUnit.MILLISECONDS);
                    if (sent != null) {
                        sent.callback.onCompletion(null, null);
                        sent.callback.onCompletion(null, null);
                    }
                }
                return null;
            });
            Future<?> reader = executor.submit(() -> {
                assertTrue(start.await(2, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                do {
                    assertTrue(System.nanoTime() < deadline);
                    Map<String, Long> q = lane.quality();
                    assertEquals(q.get("attempted"), q.get("delivered") + q.get("failed") + q.get("rejected") + q.get("pending"));
                    assertTrue(q.get("pending") >= 0 && q.get("pending") <= 8);
                    double bytes = pendingBytes(meters);
                    assertTrue(bytes >= 0 && bytes <= 4096);
                    observations.incrementAndGet();
                } while (done.getCount() > 0 || lane.quality().get("pending") > 0);
                return null;
            });
            start.countDown();
            for (Future<?> offer : offers) offer.get(5, TimeUnit.SECONDS);
            acknowledgements.get(5, TimeUnit.SECONDS);
            reader.get(5, TimeUnit.SECONDS);
            assertTrue(observations.get() > 0);
            assertEquals(Map.of("attempted", 400L, "delivered", (long) accepted.get(), "failed", 0L,
                    "rejected", 400L - accepted.get(), "pending", 0L), lane.quality());
            assertEquals(0, pendingBytes(meters));
            assertTrue(meters.get("shortlink.events.diagnostic.high.watermark").tag("kind", "count").gauge().value() <= 8);
            assertTrue(meters.get("shortlink.events.diagnostic.high.watermark").tag("kind", "bytes").gauge().value() <= 4096);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            lane.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> clickQuality(AsyncKafkaPublisher publisher) {
        return ((Map<String, Map<String, Long>>) publisher.quality().get("lanes")).get("click");
    }

    private static ClickEventV1 event(String agent) {
        return new ClickEventV1("event", 1, 1000, "test", "1", 5, "g", 1, "s.example", "Ab", 1,
                "uv", "198.51.100.1", agent, "", "request", "trace", 1);
    }

    private static MockProducer<byte[], byte[]> automatic() {
        return new MockProducer<>(true, new ByteArraySerializer(), new ByteArraySerializer());
    }

    private static double pendingBytes(SimpleMeterRegistry meters) {
        return meters.get("shortlink.events.pending.bytes").tag("lane", "click").gauge().value();
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        reactor.core.publisher.Flux.interval(Duration.ofMillis(2)).filter(i -> condition.getAsBoolean())
                .next().block(Duration.ofSeconds(2));
    }

    private static RedirectProperties config() {
        var c = TestConfig.defaults();
        return new RedirectProperties(c.instanceId(), c.allowedHosts(), c.trustedProxyCidrs(), c.internalToken(),
                c.riskHashSalt(), c.commandBaseUrl(), c.authorityTtlMillis(), c.requestTimeoutMillis(), c.redisTimeoutMillis(),
                c.cacheEntries(), c.originConcurrency(), c.clusterOriginRate(), 8, 4096, 1024,
                c.kafkaBootstrap(), c.clickBuckets(), c.generationPollMillis());
    }

    private static final class BlockingRejectedRegistry extends SimpleMeterRegistry {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

        @Override protected Counter newCounter(Meter.Id id) {
            Counter delegate = super.newCounter(id);
            if (!id.getName().equals("shortlink.events.rejected") || !"click".equals(id.getTag("lane"))) return delegate;
            return new Counter() {
                public Meter.Id getId() { return delegate.getId(); }
                public double count() { return delegate.count(); }
                public void increment(double amount) {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS), "bounded blocked diagnostic"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                    delegate.increment(amount);
                }
            };
        }
    }

    private record Delivery(ProducerRecord<byte[], byte[]> record, Callback callback) {}

    private static final class ControlledProducer extends MockProducer<byte[], byte[]> {
        final LinkedBlockingQueue<Delivery> pending = new LinkedBlockingQueue<>();
        final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
        final AtomicInteger closeCalls = new AtomicInteger();
        final AtomicBoolean closeCallbackFinished = new AtomicBoolean();
        boolean closeCallbacksOnOtherThread;

        ControlledProducer() { super(false, new ByteArraySerializer(), new ByteArraySerializer()); }

        @Override public Future<RecordMetadata> send(ProducerRecord<byte[], byte[]> record, Callback callback) {
            Delivery delivery = new Delivery(record, callback);
            deliveries.add(delivery);
            pending.add(delivery);
            return new CompletableFuture<>();
        }

        Delivery next() throws InterruptedException {
            Delivery value = pending.poll(2, TimeUnit.SECONDS);
            assertNotNull(value, "bounded wait for real worker send");
            return value;
        }

        @Override public void close(Duration timeout) {
            closeCalls.incrementAndGet();
            Runnable callbacks = () -> {
                for (Delivery sent : deliveries) sent.callback.onCompletion(null, new IllegalStateException("producer closed"));
                closeCallbackFinished.set(true);
            };
            if (!closeCallbacksOnOtherThread) { callbacks.run(); return; }
            var future = new FutureTask<Void>(callbacks, null);
            Thread thread = new Thread(future);
            thread.start();
            try { future.get(2, TimeUnit.SECONDS); }
            catch (Exception e) { throw new AssertionError("close must not hold the admission monitor across producer callbacks", e); }
        }
    }
}
