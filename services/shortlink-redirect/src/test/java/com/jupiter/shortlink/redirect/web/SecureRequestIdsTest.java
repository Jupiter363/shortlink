package com.jupiter.shortlink.redirect.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.security.DrbgParameters;
import java.security.NoSuchAlgorithmException;
import java.security.ProviderException;
import java.security.SecureRandom;
import java.security.SecureRandomParameters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class SecureRequestIdsTest {
    @BeforeEach
    @AfterEach
    void clearCallingThreadRandom() throws Exception {
        randoms().remove();
    }

    @ParameterizedTest
    @CsvSource({
        "00000000000000000000000000000000, 00000000-0000-4000-8000-000000000000",
        "ffffffffffffffffffffffffffffffff, ffffffff-ffff-4fff-bfff-ffffffffffff",
        "000102030405060708090a0b0c0d0e0f, 00010203-0405-4607-8809-0a0b0c0d0e0f"
    })
    void preservesRandomBitsExceptUuidVersionAndVariant(String randomHex, String expected)
            throws Exception {
        byte[] entropy = HexFormat.of().parseHex(randomHex);
        SecureRandom source = mock(SecureRandom.class);
        doAnswer(
                        call -> {
                            byte[] destination = call.getArgument(0);
                            assertEquals(16, destination.length);
                            DrbgParameters.NextBytes parameters = call.getArgument(1);
                            assertEquals(128, parameters.getStrength());
                            assertFalse(parameters.getPredictionResistance());
                            assertNull(parameters.getAdditionalInput());
                            System.arraycopy(entropy, 0, destination, 0, entropy.length);
                            return null;
                        })
                .when(source)
                .nextBytes(any(byte[].class), any(SecureRandomParameters.class));
        randoms().set(source);

        UUID id = SecureRequestIds.randomUuid();

        assertEquals(UUID.fromString(expected), id);
        assertEquals(expected, id.toString());
        assertEquals(4, id.version());
        assertEquals(2, id.variant());
        verify(source, times(1))
                .nextBytes(any(byte[].class), any(SecureRandomParameters.class));
        verifyNoMoreInteractions(source);
        assertEquals(randomHex, HexFormat.of().formatHex(entropy));
    }

    @Test
    void actualSunProviderSupportsRequiredStrengthAndReseeding() throws Exception {
        UUID first = SecureRequestIds.randomUuid();
        SecureRandom source = randoms().get();
        assertEquals("DRBG", source.getAlgorithm());
        assertEquals("SUN", source.getProvider().getName());
        DrbgParameters.Instantiation parameters =
                assertInstanceOf(DrbgParameters.Instantiation.class, source.getParameters());
        assertTrue(parameters.getStrength() >= 128);
        assertTrue(parameters.getCapability().supportsReseeding());
        assertNull(parameters.getPersonalizationString());
        source.reseed(DrbgParameters.reseed(false, null));
        UUID afterReseed = SecureRequestIds.randomUuid();
        assertSame(source, randoms().get());
        assertNotEquals(first, afterReseed);
        assertEquals(4, afterReseed.version());
        assertEquals(2, afterReseed.variant());
    }

    @Test
    void concurrentThreadsOwnDifferentGeneratorsAndProduceDistinctValidIds() throws Exception {
        int workers = 8;
        int perWorker = 256;
        var executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Generated>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    ThreadLocal<SecureRandom> locals = randoms();
                                    locals.remove();
                                    ready.countDown();
                                    assertTrue(start.await(10, TimeUnit.SECONDS));
                                    try {
                                        Set<UUID> ids = new HashSet<>();
                                        for (int n = 0; n < perWorker; n++) {
                                            UUID id = SecureRequestIds.randomUuid();
                                            assertEquals(4, id.version());
                                            assertEquals(2, id.variant());
                                            assertEquals(id, UUID.fromString(id.toString()));
                                            assertTrue(ids.add(id));
                                        }
                                        SecureRandom own = locals.get();
                                        assertSame(own, locals.get());
                                        return new Generated(own, ids);
                                    } finally {
                                        locals.remove();
                                    }
                                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            Set<SecureRandom> sources = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<UUID> all = new HashSet<>();
            for (Future<Generated> future : futures) {
                Generated generated = future.get(15, TimeUnit.SECONDS);
                assertTrue(sources.add(generated.source()));
                for (UUID id : generated.ids()) assertTrue(all.add(id));
            }
            assertEquals(workers, sources.size());
            assertEquals(workers * perWorker, all.size());
            // This guards accidental identical state/outputs; it is not an entropy certification.
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void unavailableDrbgDoesNotFallbackToAnotherAlgorithm() throws Exception {
        try (MockedStatic<SecureRandom> factory = mockStatic(SecureRandom.class)) {
            factory.when(
                            () ->
                                    SecureRandom.getInstance(
                                            eq("DRBG"),
                                            any(SecureRandomParameters.class),
                                            eq("SUN")))
                    .thenThrow(new NoSuchAlgorithmException("test provider unavailable"));

            IllegalStateException failure =
                    assertThrows(IllegalStateException.class, SecureRequestIds::randomUuid);

            assertEquals("Required request ID DRBG unavailable", failure.getMessage());
            assertInstanceOf(NoSuchAlgorithmException.class, failure.getCause());
            factory.verify(
                    () ->
                            SecureRandom.getInstance(
                                    eq("DRBG"), any(SecureRandomParameters.class), eq("SUN")),
                    times(1));
            factory.verifyNoMoreInteractions();
        }
    }

    @Test
    void entropyFailureIsPropagatedWithoutReturningAnId() throws Exception {
        SecureRandom source = mock(SecureRandom.class);
        ProviderException unavailable = new ProviderException("test entropy unavailable");
        doThrow(unavailable)
                .when(source)
                .nextBytes(any(byte[].class), any(SecureRandomParameters.class));
        randoms().set(source);

        assertSame(unavailable, assertThrows(ProviderException.class, SecureRequestIds::randomUuid));
        verify(source, times(1))
                .nextBytes(any(byte[].class), any(SecureRandomParameters.class));
        verifyNoMoreInteractions(source);
    }

    @Test
    void startupCheckActuallyRequestsEntropyAndPropagatesFailure() throws Exception {
        SecureRandom source = mock(SecureRandom.class);
        ProviderException unavailable = new ProviderException("test startup entropy unavailable");
        doThrow(unavailable)
                .when(source)
                .nextBytes(any(byte[].class), any(SecureRandomParameters.class));
        randoms().set(source);

        assertSame(
                unavailable,
                assertThrows(ProviderException.class, SecureRequestIds::verifyAvailable));
        verify(source, times(1))
                .nextBytes(argThat(bytes -> bytes.length == 16), any(SecureRandomParameters.class));
        verifyNoMoreInteractions(source);
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<SecureRandom> randoms() throws ReflectiveOperationException {
        Field field = SecureRequestIds.class.getDeclaredField("RANDOM");
        field.setAccessible(true);
        return (ThreadLocal<SecureRandom>) field.get(null);
    }

    private record Generated(SecureRandom source, Set<UUID> ids) {}
}
