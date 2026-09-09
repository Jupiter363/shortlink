package com.jupiter.shortlink.redirect.event;

import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.contract.*;
import com.jupiter.shortlink.redirect.TestConfig;
import com.jupiter.shortlink.redirect.config.RedirectProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

class AsyncKafkaPublisherUtf8Test {
    static Stream<String> unicodeValues() {
        return Stream.of("中文 café e\u0301", "\uD83D\uDE80", "\"\\\n\t\u0000",
                "\uD800", "\uDC00", null,
                ("x".repeat(2047) + "\uD83D\uDE80").substring(0, 2048),
                "x".repeat(3999) + "\uD83D\uDE80\uD800x\uDC00");
    }

    @ParameterizedTest
    @MethodSource("unicodeValues")
    void bothPublicLanesKeepExactLegacyKeyAndBodyBytes(String text) {
        var clicks = producer();
        var results = producer();
        var config = TestConfig.defaults();
        var click = click(text);
        var result = result(text);
        try (var publisher = new AsyncKafkaPublisher(config, new SimpleMeterRegistry(), clicks, results)) {
            assertTrue(publisher.click(click));
            assertTrue(publisher.result(result));
            await(() -> clicks.history().size() == 1 && results.history().size() == 1);
            assertRecord(clicks.history().get(0), Topics.CLICK_RAW,
                    EventKeys.click(click, config.clickBuckets()), click);
            assertRecord(results.history().get(0), Topics.GATEWAY_REQUEST, EventKeys.result(result), result);
            assertTrue(clicks.completeNext());
            assertTrue(results.completeNext());
            assertEquals(quality(1, 1, 0), lane(publisher, "click"));
            assertEquals(quality(1, 1, 0), lane(publisher, "result"));
        }
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void publicBytePathBudgetsExactSnapshotAndKeepsRetryReferences(boolean clickLane, boolean plainBmp) {
        var config = config(4, 2048, 1024);
        var clicks = producer();
        var results = producer();
        var selected = clickLane ? clicks : results;
        var meters = new SimpleMeterRegistry();
        String name = clickLane ? "click" : "result";
        String text = plainBmp ? "汉文e\u0301\"\\\n" : "汉\uD83D\uDE80e\u0301\uD800x\uDC00";
        Object base = clickLane ? click(text) : result(text);
        String key = key(base, config);
        String padded = text + "a".repeat(1024 - size(key, base));
        Object event = clickLane ? click(padded) : result(padded);
        Object oversize = clickLane ? click(padded + "a") : result(padded + "a");
        try (var publisher = new AsyncKafkaPublisher(config, meters, clicks, results)) {
            assertEquals(1024, size(key, event));
            assertTrue(offer(publisher, event));
            assertTrue(offer(publisher, event));
            await(() -> selected.history().size() == 2);
            assertEquals(2048, pendingBytes(meters, name));
            assertFalse(offer(publisher, event), "queued and in-flight bytes share the same budget");
            assertFalse(offer(publisher, oversize), "key plus body exceeds event limit by one byte");
            assertEquals(1, reason(meters, name, "bytes"));
            assertEquals(1, reason(meters, name, "event_size"));
            assertEquals(0, reason(meters, name, "slots"));

            var first = selected.history().get(0);
            var second = selected.history().get(1);
            assertNotSame(first.key(), second.key(), "each admission owns its key snapshot");
            assertNotSame(first.value(), second.value(), "each admission owns its body snapshot");
            assertTrue(selected.errorNext(new RuntimeException("ACK fixture failure")));
            await(() -> selected.history().size() == 3);
            var retry = selected.history().get(2);
            assertSame(first.key(), retry.key());
            assertSame(first.value(), retry.value());
            assertEquals(2048, pendingBytes(meters, name), "retry keeps its existing reservation");
            for (var record : selected.history()) {
                assertRecord(record, clickLane ? Topics.CLICK_RAW : Topics.GATEWAY_REQUEST, key, event);
            }
            assertTrue(selected.completeNext());
            assertEquals(1024, pendingBytes(meters, name));
            assertTrue(selected.completeNext());
            assertEquals(0, pendingBytes(meters, name));
            assertEquals(quality(4, 2, 2), lane(publisher, name));
        }
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void overlongUnicodeIsRejectedBeforeQueuePublication(boolean clickLane, boolean plainBmp) {
        var clicks = producer();
        var results = producer();
        var meters = new SimpleMeterRegistry();
        String name = clickLane ? "click" : "result";
        var config = config(2, 2048, 1024);
        String text = (plainBmp ? "汉文" : "汉\uD83D\uDE80\uD800x\uDC00").repeat(4096);
        Object event = clickLane ? click(text) : result(text);
        try (var publisher = new AsyncKafkaPublisher(config, meters, clicks, results)) {
            assertFalse(offer(publisher, event));
            assertEquals(size(key(event, config), event), meters.get("shortlink.events.diagnostic.first.rejection")
                    .tag("lane", name).tag("field", "event_bytes").gauge().value());
            assertEquals(0, pendingBytes(meters, name));
            assertTrue(clicks.history().isEmpty());
            assertTrue(results.history().isEmpty());
            assertEquals(quality(1, 0, 1), lane(publisher, name));
        }
    }

    @Test
    void mutableByteArrayAdmissionRemainsPrivate() {
        for (var method : AsyncKafkaPublisher.Lane.class.getDeclaredMethods()) {
            if (Arrays.asList(method.getParameterTypes()).contains(byte[].class)) {
                assertTrue(Modifier.isPrivate(method.getModifiers()), method.getName());
            }
        }
    }

    private static ClickEventV1 click(String text) {
        return new ClickEventV1("evt-utf8", 1, 1000, "test", "租户", 5,
                "g", 1, "s.example", "Ab", 1, null, "::1", text, null, "req", null, 1);
    }

    private static GatewayRequestEventV1 result(String text) {
        return new GatewayRequestEventV1("decision-租户", 1, 1000, "test",
                RequestSource.REDIRECT, DecisionStage.BUSINESS, "GET", 302, text, null, null,
                "s.example", "Ab", null, "req", null);
    }

    private static String key(Object event, RedirectProperties config) {
        return event instanceof ClickEventV1 click ? EventKeys.click(click, config.clickBuckets())
                : EventKeys.result((GatewayRequestEventV1) event);
    }

    private static boolean offer(AsyncKafkaPublisher publisher, Object event) {
        return event instanceof ClickEventV1 click ? publisher.click(click)
                : publisher.result((GatewayRequestEventV1) event);
    }

    private static int size(String key, Object event) {
        return key.getBytes(StandardCharsets.UTF_8).length
                + EventJson.write(event).getBytes(StandardCharsets.UTF_8).length;
    }

    private static void assertRecord(ProducerRecord<byte[], byte[]> record, String topic, String key, Object event) {
        assertEquals(topic, record.topic());
        assertNull(record.partition());
        assertArrayEquals(key.getBytes(StandardCharsets.UTF_8), record.key());
        assertArrayEquals(EventJson.write(event).getBytes(StandardCharsets.UTF_8), record.value());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> lane(AsyncKafkaPublisher publisher, String name) {
        return ((Map<String, Map<String, Long>>) publisher.quality().get("lanes")).get(name);
    }

    private static Map<String, Long> quality(long attempted, long delivered, long rejected) {
        return Map.of("attempted", attempted, "delivered", delivered, "failed", 0L,
                "rejected", rejected, "pending", 0L);
    }

    private static double pendingBytes(SimpleMeterRegistry meters, String lane) {
        return meters.get("shortlink.events.pending.bytes").tag("lane", lane).gauge().value();
    }

    private static double reason(SimpleMeterRegistry meters, String lane, String reason) {
        return meters.get("shortlink.events.rejected.reason").tag("lane", lane).tag("reason", reason).counter().count();
    }

    private static MockProducer<byte[], byte[]> producer() {
        return new MockProducer<>(false, new ByteArraySerializer(), new ByteArraySerializer());
    }

    private static void await(BooleanSupplier condition) {
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
}
