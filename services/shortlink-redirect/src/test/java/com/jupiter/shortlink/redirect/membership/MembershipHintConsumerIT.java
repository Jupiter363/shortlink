package com.jupiter.shortlink.redirect.membership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.contract.Topics;
import com.jupiter.shortlink.redirect.TestConfig;
import com.jupiter.shortlink.redirect.config.RedirectProperties;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks only Kafka's wake-up contract; neither Kafka offsets nor payloads grant denial authority.
 */
@EnabledIfEnvironmentVariable(named = "BLOOM_HINT_IT_BOOTSTRAP", matches = ".+")
class MembershipHintConsumerIT {
    @Test
    @SuppressWarnings("unchecked")
    void ownKafkaRegistrationNoticeWakesTheIndependentMembershipSynchronizer() throws Exception {
        String bootstrap = System.getenv("BLOOM_HINT_IT_BOOTSTRAP");
        assertEquals(
                "127.0.0.1:29092", bootstrap, "Only the dedicated disposable Kafka is accepted");
        String nonce = UUID.randomUUID().toString();
        String value =
                "{\"namespace\":\"routes\",\"generation\":\""
                        + nonce
                        + "\",\"revision\":1,\"memberCount\":1}";
        CountDownLatch assigned = new CountDownLatch(1);
        CountDownLatch hinted = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicBoolean ownNoticePolled = new AtomicBoolean();
        AtomicBoolean created = new AtomicBoolean();
        LocalRouteMembership membership = mock(LocalRouteMembership.class);
        doAnswer(
                        call -> {
                            if (ownNoticePolled.get()) hinted.countDown();
                            return null;
                        })
                .when(membership)
                .hint();
        RouteMembershipProperties options =
                new RouteMembershipProperties(
                        true, 100, .0001, .001, 1048576, 2, 100, 250, 10000, "", true);
        RedirectProperties defaults = TestConfig.defaults();
        RedirectProperties config =
                new RedirectProperties(
                        "bloom-it-" + nonce,
                        defaults.allowedHosts(),
                        defaults.trustedProxyCidrs(),
                        defaults.internalToken(),
                        defaults.riskHashSalt(),
                        defaults.commandBaseUrl(),
                        defaults.authorityTtlMillis(),
                        defaults.requestTimeoutMillis(),
                        defaults.redisTimeoutMillis(),
                        defaults.cacheEntries(),
                        defaults.originConcurrency(),
                        defaults.clusterOriginRate(),
                        defaults.kafkaQueueCapacity(),
                        defaults.kafkaQueueBytes(),
                        defaults.eventMaxBytes(),
                        bootstrap,
                        defaults.clickBuckets(),
                        defaults.generationPollMillis());
        MembershipHintConsumer hints =
                new MembershipHintConsumer(
                        membership,
                        options,
                        config,
                        Map.of(),
                        settings -> {
                            KafkaConsumer<String, String> real = new KafkaConsumer<>(settings);
                            Consumer<String, String> observed =
                                    (Consumer<String, String>)
                                            Proxy.newProxyInstance(
                                                    Consumer.class.getClassLoader(),
                                                    new Class<?>[] {Consumer.class},
                                                    (proxy, method, args) -> {
                                                        if (method.getName().equals("subscribe")
                                                                && args.length == 1
                                                                && args[0]
                                                                        instanceof Collection<?>) {
                                                            real.subscribe(
                                                                    (Collection<String>) args[0],
                                                                    new ConsumerRebalanceListener() {
                                                                        public void
                                                                                onPartitionsRevoked(
                                                                                        Collection<
                                                                                                        TopicPartition>
                                                                                                partitions) {}

                                                                        public void
                                                                                onPartitionsAssigned(
                                                                                        Collection<
                                                                                                        TopicPartition>
                                                                                                partitions) {
                                                                            // Freeze an actual
                                                                            // broker cut before
                                                                            // publishing this
                                                                            // test's notice.
                                                                            Map<
                                                                                            TopicPartition,
                                                                                            Long>
                                                                                    cut =
                                                                                            real
                                                                                                    .endOffsets(
                                                                                                            partitions,
                                                                                                            Duration
                                                                                                                    .ofSeconds(
                                                                                                                            3));
                                                                            cut.forEach(real::seek);
                                                                            assigned.countDown();
                                                                        }
                                                                    });
                                                            return null;
                                                        }
                                                        try {
                                                            Object result =
                                                                    method.invoke(real, args);
                                                            if (result
                                                                    instanceof
                                                                    ConsumerRecords<?, ?> records) {
                                                                for (ConsumerRecord<?, ?> record :
                                                                        records)
                                                                    if (nonce.equals(record.key())
                                                                            && value.equals(
                                                                                    record.value()))
                                                                        ownNoticePolled.set(true);
                                                            }
                                                            return result;
                                                        } catch (
                                                                InvocationTargetException failure) {
                                                            throw failure.getCause();
                                                        } finally {
                                                            if (method.getName().equals("close"))
                                                                closed.countDown();
                                                        }
                                                    });
                            created.set(true);
                            return observed;
                        });
        KafkaProducer<String, String> producer = null;
        try {
            hints.start();
            assertTrue(
                    assigned.await(15, TimeUnit.SECONDS),
                    "Real Kafka partition assignment did not complete");
            Properties settings = new Properties();
            settings.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            settings.put(
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            settings.put(
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            settings.put(ProducerConfig.ACKS_CONFIG, "all");
            settings.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
            settings.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
            settings.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
            producer = new KafkaProducer<>(settings);
            producer.send(new ProducerRecord<>(Topics.ROUTE_MEMBERSHIP, nonce, value))
                    .get(10, TimeUnit.SECONDS);
            assertTrue(
                    hinted.await(10, TimeUnit.SECONDS),
                    "The unique acknowledged notice did not wake membership sync");
            assertTrue(ownNoticePolled.get());
            verify(membership, atLeastOnce()).hint();
        } finally {
            hints.stop();
            if (producer != null) producer.close(Duration.ofSeconds(2));
            if (created.get())
                assertTrue(
                        closed.await(5, TimeUnit.SECONDS),
                        "Kafka consumer did not close within its bound");
        }
    }
}
