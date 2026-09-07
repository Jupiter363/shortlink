package com.jupiter.shortlink.redirect.event;

import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.contract.*;
import com.jupiter.shortlink.redirect.TestConfig;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.time.Duration;

class AsyncKafkaPublisherTest {
    @Test
    @SuppressWarnings("unchecked")
    void qualityReportsCoherentLongCountersAndCountsInflight() {
        MockProducer<String, String> clicks =
                new MockProducer<>(false, new StringSerializer(), new StringSerializer());
        try (AsyncKafkaPublisher publisher =
                new AsyncKafkaPublisher(
                        TestConfig.defaults(),
                        new SimpleMeterRegistry(),
                        clicks,
                        new MockProducer<>(true, new StringSerializer(), new StringSerializer()))) {
            assertTrue(publisher.click(click("quality", "")));
            assertFalse(publisher.click(click("large", "x".repeat(17000))));
            awaitCondition(() -> clicks.history().size() == 1);
            var lanes =
                    (java.util.Map<String, java.util.Map<String, Long>>)
                            publisher.quality().get("lanes");
            assertEquals(
                    java.util.Map.of(
                            "attempted",
                            2L,
                            "delivered",
                            0L,
                            "failed",
                            0L,
                            "rejected",
                            1L,
                            "pending",
                            1L),
                    lanes.get("click"));
            clicks.completeNext();
            lanes =
                    (java.util.Map<String, java.util.Map<String, Long>>)
                            publisher.quality().get("lanes");
            assertEquals(
                    java.util.Map.of(
                            "attempted",
                            2L,
                            "delivered",
                            1L,
                            "failed",
                            0L,
                            "rejected",
                            1L,
                            "pending",
                            0L),
                    lanes.get("click"));
        }
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition) {
        reactor.core.publisher.Flux.interval(Duration.ofMillis(10))
                .filter(ignored -> condition.getAsBoolean())
                .next()
                .block(Duration.ofSeconds(2));
    }

    private ClickEventV1 click(String id, String agent) {
        return new ClickEventV1(
                id,
                1,
                1000,
                "test",
                "1",
                5,
                "g",
                1,
                "s.example",
                "Ab",
                1,
                "uv",
                "1.2.3.4",
                agent,
                "",
                "req",
                "trace",
                1);
    }

    private GatewayRequestEventV1 result() {
        return new GatewayRequestEventV1(
                "decision",
                1,
                1000,
                "test",
                RequestSource.REDIRECT,
                DecisionStage.BUSINESS,
                "GET",
                302,
                "REDIRECT",
                "1",
                5L,
                "s.example",
                "Ab",
                1L,
                "r",
                "t");
    }

    @Test
    void queuedPlusInflightCountBoundDoesNotStarveIndependentResultLane() {
        MockProducer<String, String> clickProducer =
                new MockProducer<>(false, new StringSerializer(), new StringSerializer());
        MockProducer<String, String> resultProducer =
                new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        try (AsyncKafkaPublisher publisher =
                new AsyncKafkaPublisher(
                        TestConfig.defaults(),
                        new SimpleMeterRegistry(),
                        clickProducer,
                        resultProducer)) {
            assertTrue(publisher.click(click("a", "")));
            assertTrue(publisher.click(click("b", "")));
            awaitCondition(() -> clickProducer.history().size() == 2);
            assertFalse(publisher.click(click("c", "")));
            assertTrue(publisher.result(result()));
            awaitCondition(() -> resultProducer.history().size() == 1);
            clickProducer.completeNext();
            awaitCondition(() -> publisher.click(click("d", "")));
        }
    }

    @Test
    void oversizeEventRejectedAndRetryKeepsExactPayloadAndIdentity() {
        MockProducer<String, String> clicks =
                new MockProducer<>(false, new StringSerializer(), new StringSerializer());
        try (AsyncKafkaPublisher publisher =
                new AsyncKafkaPublisher(
                        TestConfig.defaults(),
                        new SimpleMeterRegistry(),
                        clicks,
                        new MockProducer<>(true, new StringSerializer(), new StringSerializer()))) {
            assertFalse(publisher.click(click("oversize", "x".repeat(17000))));
            assertTrue(publisher.click(click("same", "")));
            awaitCondition(() -> clicks.history().size() == 1);
            clicks.errorNext(new RuntimeException("ACK unknown"));
            awaitCondition(() -> clicks.history().size() == 2);
            assertEquals(clicks.history().get(0).key(), clicks.history().get(1).key());
            assertEquals(clicks.history().get(0).value(), clicks.history().get(1).value());
            clicks.completeNext();
        }
    }
}
