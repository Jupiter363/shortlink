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

    @Test
    void bothInstancesReceiveEveryRouteAndPolicyHint() throws Exception {
        String bootstrap = System.getenv("SHORTLINK_KAFKA_TEST_BOOTSTRAP");
        assertNotNull(bootstrap, "Explicit isolated Kafka required");
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrap))) {
            var names = admin.listTopics().names().get(10, java.util.concurrent.TimeUnit.SECONDS);
            List<NewTopic> missing = new ArrayList<>();
            for (String topic : List.of(Topics.ROUTE_CHANGE, Topics.RISK_POLICY_CHANGE))
                if (!names.contains(topic)) missing.add(new NewTopic(topic, 2, (short) 1));
            if (!missing.isEmpty())
                admin.createTopics(missing).all().get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        RouteResolver firstRoutes = mock(RouteResolver.class),
                secondRoutes = mock(RouteResolver.class);
        PolicyResolver firstPolicy = mock(PolicyResolver.class),
                secondPolicy = mock(PolicyResolver.class);
        AtomicInteger firstCount = new AtomicInteger(),
                secondCount = new AtomicInteger(),
                firstRisk = new AtomicInteger(),
                secondRisk = new AtomicInteger();
        when(firstRoutes.invalidate(anyString(), anyString(), anyLong()))
                .thenAnswer(
                        i -> {
                            if (id.equals(i.getArgument(1))) firstCount.incrementAndGet();
                            return Mono.empty();
                        });
        when(secondRoutes.invalidate(anyString(), anyString(), anyLong()))
                .thenAnswer(
                        i -> {
                            if (id.equals(i.getArgument(1))) secondCount.incrementAndGet();
                            return Mono.empty();
                        });
        doAnswer(
                        i -> {
                            if (("1:" + id).equals(i.getArgument(0))) firstRisk.incrementAndGet();
                            return null;
                        })
                .when(firstPolicy)
                .invalidate(anyString(), anyLong());
        doAnswer(
                        i -> {
                            if (("1:" + id).equals(i.getArgument(0))) secondRisk.incrementAndGet();
                            return null;
                        })
                .when(secondPolicy)
                .invalidate(anyString(), anyLong());
        ChangeFanout
                first = new ChangeFanout(config("it-a-" + id, bootstrap), firstRoutes, firstPolicy),
                second =
                        new ChangeFanout(
                                config("it-b-" + id, bootstrap), secondRoutes, secondPolicy);
        try (KafkaProducer<String, String> producer =
                new KafkaProducer<>(
                        Map.of(
                                "bootstrap.servers",
                                bootstrap,
                                "key.serializer",
                                StringSerializer.class.getName(),
                                "value.serializer",
                                StringSerializer.class.getName(),
                                "acks",
                                "all"))) {
            first.start();
            second.start();
            await(() -> first.connected() && second.connected());
            var route =
                    new RouteChangeV1(
                            id, 1, System.currentTimeMillis(), "1", 1, 2, "it.example", id);
            var risk =
                    new RiskPolicyChangeV1(
                            id,
                            1,
                            System.currentTimeMillis(),
                            "1",
                            "1:" + id,
                            "AGGREGATE",
                            2,
                            null);
            producer.send(
                            new ProducerRecord<>(
                                    Topics.ROUTE_CHANGE,
                                    EventKeys.route(route),
                                    EventJson.write(route)))
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            producer.send(
                            new ProducerRecord<>(
                                    Topics.RISK_POLICY_CHANGE,
                                    EventKeys.policy(risk),
                                    EventJson.write(risk)))
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            await(
                    () ->
                            firstCount.get() >= 1
                                    && secondCount.get() >= 1
                                    && firstRisk.get() >= 1
                                    && secondRisk.get() >= 1);
            assertTrue(firstCount.get() >= 1 && secondCount.get() >= 1);
        } finally {
            first.stop();
            second.stop();
        }
    }
}
