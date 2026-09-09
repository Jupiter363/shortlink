package com.jupiter.shortlink.redirect.event;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import com.jupiter.shortlink.contract.*;
import com.jupiter.shortlink.redirect.TestConfig;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class AsyncKafkaPublisherTest {
    @Test
    @SuppressWarnings("unchecked")
    void qualityReportsCoherentLongCountersAndCountsInflight() {
        MockProducer<byte[], byte[]> clicks =
                new MockProducer<>(false, new ByteArraySerializer(), new ByteArraySerializer());
        try (AsyncKafkaPublisher publisher =
                new AsyncKafkaPublisher(
                        TestConfig.defaults(),
                        new SimpleMeterRegistry(),
                        clicks,
                        new MockProducer<>(true, new ByteArraySerializer(), new ByteArraySerializer()))) {
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
        MockProducer<byte[], byte[]> clickProducer =
                new MockProducer<>(false, new ByteArraySerializer(), new ByteArraySerializer());
        MockProducer<byte[], byte[]> resultProducer =
                new MockProducer<>(true, new ByteArraySerializer(), new ByteArraySerializer());
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
        MockProducer<byte[], byte[]> clicks =
                new MockProducer<>(false, new ByteArraySerializer(), new ByteArraySerializer());
        try (AsyncKafkaPublisher publisher =
                new AsyncKafkaPublisher(
                        TestConfig.defaults(),
                        new SimpleMeterRegistry(),
                        clicks,
                        new MockProducer<>(true, new ByteArraySerializer(), new ByteArraySerializer()))) {
            assertFalse(publisher.click(click("oversize", "x".repeat(17000))));
            assertTrue(publisher.click(click("same", "")));
            awaitCondition(() -> clicks.history().size() == 1);
            clicks.errorNext(new RuntimeException("ACK unknown"));
            awaitCondition(() -> clicks.history().size() == 2);
            assertSame(clicks.history().get(0).key(), clicks.history().get(1).key());
            assertSame(clicks.history().get(0).value(), clicks.history().get(1).value());
            clicks.completeNext();
        }
    }

    @Test
    void clickAndResultWirePayloadsAndPartitionKeysMatchLegacyStringSerializer() {
        var clicks = new MockProducer<byte[], byte[]>(true, new ByteArraySerializer(), new ByteArraySerializer());
        var results = new MockProducer<byte[], byte[]>(true, new ByteArraySerializer(), new ByteArraySerializer());
        var config = TestConfig.defaults();
        var click = click("wire-contract", "café 汉字 \uD83D\uDE80 \uD800");
        var result = result();
        try (var publisher = new AsyncKafkaPublisher(config, new SimpleMeterRegistry(), clicks, results)) {
            assertTrue(publisher.click(click));
            assertTrue(publisher.result(result));
            awaitCondition(() -> clicks.history().size() == 1 && results.history().size() == 1);
            var legacy = new StringSerializer();
            var clickRecord = clicks.history().get(0);
            assertEquals(Topics.CLICK_RAW, clickRecord.topic());
            assertNull(clickRecord.partition(), "default partitioning still uses the encoded key");
            assertArrayEquals(legacy.serialize(Topics.CLICK_RAW, EventKeys.click(click, config.clickBuckets())), clickRecord.key());
            assertArrayEquals(legacy.serialize(Topics.CLICK_RAW, EventJson.write(click)), clickRecord.value());
            var resultRecord = results.history().get(0);
            assertEquals(Topics.GATEWAY_REQUEST, resultRecord.topic());
            assertNull(resultRecord.partition());
            assertArrayEquals(legacy.serialize(Topics.GATEWAY_REQUEST, EventKeys.result(result)), resultRecord.key());
            assertArrayEquals(legacy.serialize(Topics.GATEWAY_REQUEST, EventJson.write(result)), resultRecord.value());
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void productionFactoryUsesPassThroughByteSerializersAndKeepsDeliveryConfiguration() {
        List<List<?>> arguments = new ArrayList<>();
        try (var producers = mockConstruction(KafkaProducer.class, (producer, context) -> {
                    arguments.add(context.arguments());
                    when(producer.metrics()).thenReturn(Map.of());
                });
                var publisher = new AsyncKafkaPublisher(TestConfig.defaults(), new SimpleMeterRegistry())) {
            assertEquals(2, producers.constructed().size(), "each lane keeps its own producer");
            for (var constructor : arguments) {
                assertEquals(3, constructor.size());
                var settings = (Map<?, ?>) constructor.get(0);
                assertEquals(ByteArraySerializer.class.getName(), settings.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG));
                assertEquals(ByteArraySerializer.class.getName(), settings.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
                assertEquals("all", settings.get(ProducerConfig.ACKS_CONFIG));
                assertEquals(true, settings.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
                assertEquals(5, settings.get(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION));
                assertEquals(5, settings.get(ProducerConfig.LINGER_MS_CONFIG));
                assertEquals(500, settings.get(ProducerConfig.MAX_BLOCK_MS_CONFIG));
                assertEquals(10000, settings.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG));
                assertEquals(3000, settings.get(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG));
                assertEquals(TestConfig.defaults().kafkaQueueBytes(), settings.get(ProducerConfig.BUFFER_MEMORY_CONFIG));
                byte[] input = {0, 1, (byte) 0xff};
                assertInstanceOf(ByteArraySerializer.class, constructor.get(1));
                assertInstanceOf(ByteArraySerializer.class, constructor.get(2));
                assertSame(input, ((ByteArraySerializer) constructor.get(1)).serialize("topic", input));
                assertSame(input, ((ByteArraySerializer) constructor.get(2)).serialize("topic", input));
            }
        }
    }
}
