package com.jupiter.shortlink.redirect.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jupiter.shortlink.redirect.TestConfig;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class AsyncKafkaPublisherObservationIsolationTest {
    @Test
    void outsideQueueGaugeCompletesWhileAnotherThreadOwnsAdmissionMonitor() throws Exception {
        var meters = new SimpleMeterRegistry();
        var producer = new SignallingProducer();
        var lane = new AsyncKafkaPublisher.Lane("click", producer, TestConfig.defaults(), meters);
        var executor = Executors.newSingleThreadExecutor();
        try {
            assertTrue(lane.offer("topic", "key", "body"));
            assertTrue(producer.sent.await(2, TimeUnit.SECONDS), "worker must remove and send the event");
            var outsideQueue = meters.get("shortlink.events.diagnostic.state")
                    .tag("lane", "click").tag("kind", "outside_queue").gauge();

            // Main owns the real admission monitor throughout both independent reads.
            // The timeout detects a lock dependency, not a response-time performance budget.
            synchronized (lane) {
                var readerStarted = new CountDownLatch(1);
                Future<Double> pendingRead = executor.submit(() -> {
                    readerStarted.countDown();
                    return outsideQueue.value();
                });
                assertTrue(readerStarted.await(2, TimeUnit.SECONDS));
                assertEquals(1.0, pendingRead.get(2, TimeUnit.SECONDS).doubleValue(),
                        "an unacknowledged event is outside the queue; the gauge must not wait for admission");
                assertEquals(Map.of("attempted", 1L, "delivered", 0L, "failed", 0L,
                        "rejected", 0L, "pending", 1L), lane.quality());

                assertTrue(producer.completeNext());
                Future<Double> settledRead = executor.submit(() -> {
                    return outsideQueue.value();
                });
                assertEquals(0.0, settledRead.get(2, TimeUnit.SECONDS).doubleValue());
                assertEquals(Map.of("attempted", 1L, "delivered", 1L, "failed", 0L,
                        "rejected", 0L, "pending", 0L), lane.quality());
            }
        } finally {
            // A timeout exits synchronized before cleanup, so even the old blocked reader
            // can finish. Always release an outstanding mock ACK and close the lane.
            executor.shutdownNow();
            try {
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            } finally {
                producer.completeNext();
                lane.close();
                meters.close();
            }
        }
    }

    private static final class SignallingProducer extends MockProducer<byte[], byte[]> {
        final CountDownLatch sent = new CountDownLatch(1);

        SignallingProducer() {
            super(false, new ByteArraySerializer(), new ByteArraySerializer());
        }

        @Override
        public Future<RecordMetadata> send(ProducerRecord<byte[], byte[]> record, Callback callback) {
            Future<RecordMetadata> result = super.send(record, callback);
            sent.countDown();
            return result;
        }
    }
}
