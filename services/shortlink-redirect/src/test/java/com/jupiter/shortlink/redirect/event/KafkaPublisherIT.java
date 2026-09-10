package com.jupiter.shortlink.redirect.event;

import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.contract.*;
import com.jupiter.shortlink.redirect.TestConfig;
import com.jupiter.shortlink.redirect.config.RedirectProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;

class KafkaPublisherIT {
    @Test
    void realBrokerReceivesSeparateClickAndResultContractsWithStableKeys() throws Exception {
        String bootstrap = System.getenv("SHORTLINK_KAFKA_TEST_BOOTSTRAP");
        assertNotNull(bootstrap, "Explicit isolated Kafka bootstrap required");
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrap))) {
            var names = admin.listTopics().names().get(10, java.util.concurrent.TimeUnit.SECONDS);
            List<NewTopic> missing = new ArrayList<>();
            for (String topic : List.of(Topics.CLICK_RAW, Topics.GATEWAY_REQUEST))
                if (!names.contains(topic))
                    missing.add(
                            new NewTopic(topic, 2, (short) 1)
                                    .configs(Map.of("message.timestamp.type", "LogAppendTime")));
            if (!missing.isEmpty())
                admin.createTopics(missing).all().get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        long occurredAt = System.currentTimeMillis();
        String id = EventIdentity.bind(occurredAt, UUID.randomUUID().toString());
        var p = TestConfig.defaults();
        var config =
                new RedirectProperties(
                        p.instanceId(),
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
        try (KafkaConsumer<String, String> consumer =
                        new KafkaConsumer<>(
                                Map.of(
                                        "bootstrap.servers",
                                        bootstrap,
                                        "group.id",
                                        "it-publisher-" + id,
                                        "auto.offset.reset",
                                        "latest",
                                        "enable.auto.commit",
                                        false,
                                        "key.deserializer",
                                        StringDeserializer.class.getName(),
                                        "value.deserializer",
                                        StringDeserializer.class.getName()));
                AsyncKafkaPublisher publisher =
                        new AsyncKafkaPublisher(config, new SimpleMeterRegistry())) {
            consumer.subscribe(List.of(Topics.CLICK_RAW, Topics.GATEWAY_REQUEST));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (consumer.assignment().isEmpty() && System.nanoTime() < deadline)
                consumer.poll(Duration.ofMillis(100));
            assertFalse(consumer.assignment().isEmpty());
            consumer.seekToEnd(consumer.assignment());
            for (var partition : consumer.assignment()) consumer.position(partition);
            var click =
                    new ClickEventV1(
                            id,
                            1,
                            occurredAt,
                            "test",
                            "1",
                            1,
                            "g",
                            1,
                            "it.example",
                            "Ab",
                            1,
                            "uv",
                            "1.2.3.4",
                            "",
                            "",
                            "r",
                            "t",
                            1);
            var result =
                    new GatewayRequestEventV1(
                            id,
                            1,
                            occurredAt,
                            "test",
                            RequestSource.REDIRECT,
                            DecisionStage.BUSINESS,
                            "GET",
                            302,
                            "REDIRECT",
                            "1",
                            1L,
                            "it.example",
                            "Ab",
                            1L,
                            "r",
                            "t");
            assertTrue(publisher.click(click));
            assertTrue(publisher.result(result));
            boolean gotClick = false, gotResult = false;
            deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (!(gotClick && gotResult) && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    if (record.topic().equals(Topics.CLICK_RAW)) {
                        var e = EventJson.read(record.value(), ClickEventV1.class);
                        if (e.eventId().equals(id)) {
                            gotClick = true;
                            assertTrue(EventIdentity.valid(e.eventId(), e.occurredAt()));
                            assertEquals(EventKeys.click(e, 16), record.key());
                            assertEquals(
                                    org.apache.kafka.common.record.TimestampType.LOG_APPEND_TIME,
                                    record.timestampType());
                        }
                    } else {
                        var e = EventJson.read(record.value(), GatewayRequestEventV1.class);
                        if (e.decisionId().equals(id)) {
                            gotResult = true;
                            assertTrue(EventIdentity.valid(e.decisionId(), e.occurredAt()));
                        }
                    }
                }
            }
            assertTrue(gotClick);
            assertTrue(gotResult);
        }
    }
}
