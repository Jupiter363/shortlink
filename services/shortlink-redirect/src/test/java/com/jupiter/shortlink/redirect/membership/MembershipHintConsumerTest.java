package com.jupiter.shortlink.redirect.membership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.redirect.TestConfig;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.errors.WakeupException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class MembershipHintConsumerTest {
    @Test
    @SuppressWarnings("unchecked")
    void lateConsumerConstructionCannotOverwriteOrStopARestartedConsumer() throws Exception {
        LocalRouteMembership membership = mock(LocalRouteMembership.class);
        Consumer<String, String> oldConsumer = mock(Consumer.class);
        Consumer<String, String> newConsumer = mock(Consumer.class);
        var oldConstructing = new CountDownLatch(1);
        var finishOld = new CountDownLatch(1);
        var oldClosed = new CountDownLatch(1);
        var newPolling = new CountDownLatch(1);
        var wakeNew = new CountDownLatch(1);
        var newClosed = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            oldClosed.countDown();
                            return null;
                        })
                .when(oldConsumer)
                .close(any(Duration.class));
        doAnswer(
                        call -> {
                            newClosed.countDown();
                            return null;
                        })
                .when(newConsumer)
                .close(any(Duration.class));
        doAnswer(
                        call -> {
                            wakeNew.countDown();
                            return null;
                        })
                .when(newConsumer)
                .wakeup();
        when(newConsumer.poll(any(Duration.class)))
                .thenAnswer(
                        call -> {
                            newPolling.countDown();
                            assertTrue(wakeNew.await(5, TimeUnit.SECONDS));
                            throw new WakeupException();
                        });
        var creations = new AtomicInteger();
        var options =
                new RouteMembershipProperties(
                        true, 100, .0001, .001, 1048576, 2, 100, 250, 10000, "", true);
        var consumer =
                new MembershipHintConsumer(
                        membership,
                        options,
                        TestConfig.defaults(),
                        Map.of(),
                        settings -> {
                            if (creations.incrementAndGet() == 1) {
                                oldConstructing.countDown();
                                try {
                                    assertTrue(finishOld.await(5, TimeUnit.SECONDS));
                                } catch (InterruptedException interrupted) {
                                    throw new IllegalStateException(interrupted);
                                }
                                return oldConsumer;
                            }
                            return newConsumer;
                        });
        try {
            consumer.start();
            assertTrue(oldConstructing.await(5, TimeUnit.SECONDS));
            consumer.stop();
            consumer.start();
            assertTrue(newPolling.await(5, TimeUnit.SECONDS));
            finishOld.countDown();
            assertTrue(oldClosed.await(5, TimeUnit.SECONDS));
            assertTrue(consumer.isRunning());
            verify(oldConsumer, never()).subscribe(anyCollection());
            consumer.stop();
            assertTrue(newClosed.await(5, TimeUnit.SECONDS));
            verify(newConsumer).wakeup();
            verifyNoInteractions(membership);
        } finally {
            finishOld.countDown();
            wakeNew.countDown();
            consumer.stop();
        }
    }
}
