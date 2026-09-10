package com.jupiter.shortlink.risk;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.ProviderException;
import java.security.spec.AlgorithmParameterSpec;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.MacSpi;
import javax.crypto.spec.SecretKeySpec;

class RiskHashTest {
    private static final String SALT = "0123456789abcdef0123456789abcdef";
    private final RiskHash hash = new RiskHash(SALT);

    // Fixed vectors were computed independently with Python hmac/sha256 and >I UTF-8 lengths.
    @ParameterizedTest
    @CsvSource({
        "42,198.51.100.7,5f87da2cf3a22ac4689eba40fac9c9cc",
        "43,198.51.100.7,e481ce982764d08978dbf210a84501b9",
        "42,2001:DB8::1,62e23f2375a21877ccb5e29c45850ec7",
        "42,2001:0db8:0000:0000:0000:0000:0000:0001,62e23f2375a21877ccb5e29c45850ec7",
        "9999999999999999999,::FFFF:192.0.2.128,5d414e764ab210db2781900b4dd613ab",
        "9999999999999999999,192.0.2.128,5d414e764ab210db2781900b4dd613ab",
        "1,::192.0.2.1,c7487f44bc1addf995d8d1c394ff7270"
    })
    void versionOneFixedVectorsRemainCompatible(String tenant, String ip, String expected) {
        assertEquals("hmac-sha256-128-v1", RiskHash.VERSION);
        assertEquals(expected, hash.hash(tenant, ip));
    }

    @Test
    void utf8SaltAndLegacyMigrationApiRemainCompatible() {
        assertEquals("8ae9f4a85d94c43af89e0dd0fd07c1b2",
                new RiskHash("\u98ce\u9669-key-123456789").hash("42", "127.0.0.1"));
        assertEquals("419fe837e759ffbc9529bb01327ad6e4755393488feb7e095125dfed55220e7c",
                hash.hash("198.51.100.7"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0", "01", "-1", "+1", "1.0", " 1", "1 ", "1\n",
        "10000000000000000000", "\u0661", "1\u0662"})
    void onlyOriginalAsciiTenantGrammarIsAccepted(String tenant) {
        assertThrows(IllegalArgumentException.class, () -> hash.hash(tenant, "127.0.0.1"));
    }

    @Test
    void independentLengthPrefixedReferenceCoversVaryingComponentLengths() throws Exception {
        for (String tenant : new String[] {"1", "42", "1234567890", "9999999999999999999"}) {
            for (String ip : new String[] {"0.0.0.0", "1.2.3.4", "255.255.255.255",
                    "2001:db8:0:0:0:0:0:1", "0:0:0:0:0:0:0:0"}) {
                assertEquals(reference(tenant, ip), hash.hash(tenant, ip));
            }
        }
    }

    @Test
    void sharedInstanceConcurrentCallsNeverMixMacStateOrTenantIdentity() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 256; i++) {
                boolean firstTenant = i % 2 == 0;
                tasks.add(executor.submit(() -> {
                    for (int repeat = 0; repeat < 8; repeat++) {
                        assertEquals(firstTenant ? "5f87da2cf3a22ac4689eba40fac9c9cc"
                                        : "e481ce982764d08978dbf210a84501b9",
                                hash.hash(firstTenant ? "42" : "43", "198.51.100.7"));
                        assertEquals("62e23f2375a21877ccb5e29c45850ec7",
                                hash.hash("42", "2001:DB8::1"));
                    }
                }));
            }
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void invalidIpAndMissingSaltRemainRejected() {
        assertThrows(IllegalArgumentException.class, () -> hash.hash("42", "127.1"));
        assertThrows(IllegalArgumentException.class, () -> hash.hash("42", "::1%lo"));
        assertThrows(IllegalArgumentException.class, () -> new RiskHash(null));
        assertThrows(IllegalArgumentException.class, () -> new RiskHash(" \t"));
    }

    @Test
    void initializationIsLazyAndSuccessfulCallsReuseTheSameThreadState() throws Exception {
        var created = new AtomicInteger();
        var reusable = countingHash(SALT, created);
        assertEquals(0, created.get());
        assertThrows(IllegalArgumentException.class, () -> reusable.hash("01", "127.0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> reusable.hash("42", "127.1"));
        assertEquals(0, created.get(), "invalid inputs must not initialize the provider");
        for (int i = 0; i < 32; i++) {
            assertEquals(reference("42", "198.51.100.7"), reusable.hash("42", "198.51.100.7"));
            assertThrows(IllegalArgumentException.class, () -> reusable.hash("0", "127.0.0.1"));
            assertEquals(reference("43", "2001:db8:0:0:0:0:0:1"),
                    reusable.hash("43", "2001:DB8::1"));
        }
        assertEquals(1, created.get());
    }

    @Test
    void differentSaltsTenantsAndLegacyInputsCanAlternateOnOneThread() throws Exception {
        String secondSalt = "\u98ce\u9669-key-123456789";
        RiskHash[] instances = {hash, new RiskHash(secondSalt)};
        String[] salts = {SALT, secondSalt};
        String[] tenants = {"42", "43", "9999999999999999999"};
        String[] inputs = {"198.51.100.7", "2001:DB8::1", "::FFFF:192.0.2.128"};
        String[] canonical = {"198.51.100.7", "2001:db8:0:0:0:0:0:1", "192.0.2.128"};
        for (int repeat = 0; repeat < 32; repeat++) {
            for (int instance = 0; instance < instances.length; instance++) {
                for (int input = 0; input < inputs.length; input++) {
                    assertEquals(reference(salts[instance], tenants[input], canonical[input]),
                            instances[instance].hash(tenants[input], inputs[input]));
                    assertEquals(legacyReference(salts[instance], inputs[input]),
                            instances[instance].hash(inputs[input]));
                }
            }
        }
    }

    @Test
    void concurrentInstancesReuseOnlyTheirOwnCallingThreadState() throws Exception {
        int threadCount = 8;
        String secondSalt = "another-independent-key";
        var firstCreated = new AtomicInteger();
        var secondCreated = new AtomicInteger();
        var first = countingHash(SALT, firstCreated);
        var second = countingHash(secondSalt, secondCreated);
        String first42 = reference("42", "198.51.100.7");
        String first43 = reference("43", "2001:db8:0:0:0:0:0:1");
        String second42 = reference(secondSalt, "42", "198.51.100.7");
        String second43 = reference(secondSalt, "43", "2001:db8:0:0:0:0:0:1");
        String firstLegacy = legacyReference(SALT, "2001:DB8::1");
        String secondLegacy = legacyReference(secondSalt, "198.51.100.7");
        var ready = new CountDownLatch(threadCount);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(threadCount);
        try {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < threadCount; i++) {
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    for (int repeat = 0; repeat < 32; repeat++) {
                        assertEquals(first42, first.hash("42", "198.51.100.7"));
                        assertEquals(second43, second.hash("43", "2001:DB8::1"));
                        assertEquals(firstLegacy, first.hash("2001:DB8::1"));
                        assertEquals(second42, second.hash("42", "198.51.100.7"));
                        assertEquals(first43, first.hash("43", "2001:DB8::1"));
                        assertEquals(secondLegacy, second.hash("198.51.100.7"));
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
            assertEquals(threadCount, firstCreated.get());
            assertEquals(threadCount, secondCreated.get());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void factoryFailureRetainsCauseAndCanRetryOnTheSameThread() throws Exception {
        var failure = new NoSuchAlgorithmException("injected creation failure");
        var created = new AtomicInteger();
        var reusable = new RiskHash(SALT, () -> {
            if (created.incrementAndGet() == 1) throw failure;
            return Mac.getInstance("HmacSHA256");
        });
        var reported = assertThrows(IllegalStateException.class,
                () -> reusable.hash("42", "198.51.100.7"));
        assertEquals("Risk hash unavailable", reported.getMessage());
        assertSame(failure, reported.getCause());
        assertEquals(reference("43", "198.51.100.7"), reusable.hash("43", "198.51.100.7"));
        assertEquals(reference("42", "198.51.100.7"), reusable.hash("42", "198.51.100.7"));
        assertEquals(2, created.get());
    }

    @Test
    void initializationFailureDiscardsThePartiallyInitializedMac() throws Exception {
        var created = new AtomicInteger();
        var reusable = new RiskHash(SALT, () -> created.incrementAndGet() == 1
                ? new FailingMac(FailurePoint.INITIALIZE, null) : Mac.getInstance("HmacSHA256"));
        var reported = assertThrows(IllegalStateException.class,
                () -> reusable.hash("42", "198.51.100.7"));
        assertEquals("Risk hash unavailable", reported.getMessage());
        assertInstanceOf(InvalidKeyException.class, reported.getCause());
        assertEquals(reference("43", "198.51.100.7"), reusable.hash("43", "198.51.100.7"));
        assertEquals(2, created.get());
    }

    @ParameterizedTest
    @EnumSource(value = FailurePoint.class, names = {"UPDATE", "DO_FINAL"})
    void runtimeFailureAfterUpdatingStateIsDiscarded(FailurePoint point) throws Exception {
        var failure = new ProviderException("injected provider failure");
        verifyDiscardAfterFailure(point, failure);
    }

    @ParameterizedTest
    @EnumSource(value = FailurePoint.class, names = {"UPDATE", "DO_FINAL"})
    void errorAfterUpdatingStateIsDiscarded(FailurePoint point) throws Exception {
        var failure = new AssertionError("injected provider error");
        verifyDiscardAfterFailure(point, failure);
    }

    private void verifyDiscardAfterFailure(FailurePoint point, Throwable failure) throws Exception {
        var created = new AtomicInteger();
        var reusable = new RiskHash(SALT, () -> created.incrementAndGet() == 1
                ? new FailingMac(point, failure) : Mac.getInstance("HmacSHA256"));
        assertSame(failure, assertThrows(failure.getClass(),
                () -> reusable.hash("42", "198.51.100.7")));
        assertEquals(reference("43", "198.51.100.7"), hash.hash("43", "198.51.100.7"),
                "failure must not affect another instance on this thread");
        assertEquals(legacyReference(SALT, "2001:DB8::1"), reusable.hash("2001:DB8::1"));
        assertEquals(reference("43", "2001:db8:0:0:0:0:0:1"),
                reusable.hash("43", "2001:DB8::1"));
        assertEquals(reference("42", "198.51.100.7"), reusable.hash("42", "198.51.100.7"));
        assertEquals(2, created.get(), "failed mutable state must be replaced, then reused");
    }

    private static RiskHash countingHash(String salt, AtomicInteger created) {
        return new RiskHash(salt, () -> {
            created.incrementAndGet();
            return Mac.getInstance("HmacSHA256");
        });
    }

    private static String reference(String tenant, String canonicalIp) throws Exception {
        return reference(SALT, tenant, canonicalIp);
    }

    private static String reference(String salt, String tenant, String canonicalIp) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        for (String component : new String[] {"ip", tenant, canonicalIp}) {
            byte[] encoded = component.getBytes(StandardCharsets.UTF_8);
            output.writeInt(encoded.length);
            output.write(encoded);
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(bytes.toByteArray())).substring(0, 32);
    }

    private static String legacyReference(String salt, String input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }

    private enum FailurePoint { INITIALIZE, UPDATE, DO_FINAL }

    // Test-local provider: no Security.addProvider/global ordering changes affect other tests.
    private static final Provider TEST_PROVIDER = new Provider("RiskHashTest", "1", "Local fault injection") {};

    private static final class FailingMac extends Mac {
        private FailingMac(FailurePoint point, Throwable failure) throws GeneralSecurityException {
            super(new FailingMacSpi(point, failure), TEST_PROVIDER, "HmacSHA256");
        }
    }

    private static final class FailingMacSpi extends MacSpi {
        private final Mac delegate;
        private final FailurePoint point;
        private final Throwable failure;

        private FailingMacSpi(FailurePoint point, Throwable failure) throws GeneralSecurityException {
            delegate = Mac.getInstance("HmacSHA256");
            this.point = point;
            this.failure = failure;
        }

        @Override
        protected int engineGetMacLength() { return delegate.getMacLength(); }

        @Override
        protected void engineInit(Key key, AlgorithmParameterSpec params)
                throws InvalidKeyException, InvalidAlgorithmParameterException {
            delegate.init(key, params);
            if (point == FailurePoint.INITIALIZE) throw new InvalidKeyException("injected init failure");
        }

        @Override
        protected void engineUpdate(byte input) {
            delegate.update(input);
            if (point == FailurePoint.UPDATE) fail();
        }

        @Override
        protected void engineUpdate(byte[] input, int offset, int len) {
            delegate.update(input, offset, len);
            if (point == FailurePoint.UPDATE) fail();
        }

        @Override
        protected byte[] engineDoFinal() {
            if (point == FailurePoint.DO_FINAL) fail();
            return delegate.doFinal();
        }

        @Override
        protected void engineReset() { delegate.reset(); }

        private void fail() {
            if (failure instanceof Error error) throw error;
            throw (RuntimeException) failure;
        }
    }
}
