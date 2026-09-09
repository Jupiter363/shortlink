package com.jupiter.shortlink.command.metadata;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class SafeMetadataFetcherTest {
    @Test
    void productionConstructorResolvesItsConfiguredBudgets() {
        try (var context =
                new org.springframework.context.annotation.AnnotationConfigApplicationContext(
                        SafeMetadataFetcher.class)) {
            assertNotNull(context.getBean(SafeMetadataFetcher.class));
        }
    }

    private InetAddress publicIp() throws Exception {
        return InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 34});
    }

    @Test
    void privateSpecialAndTransitionAddressesAreDenied() throws Exception {
        for (String ip :
                List.of(
                        "127.0.0.1",
                        "10.2.3.4",
                        "100.64.0.1",
                        "169.254.169.254",
                        "172.16.0.1",
                        "192.168.1.1",
                        "198.18.1.1",
                        "192.0.2.1",
                        "203.0.113.2",
                        "::1",
                        "fc00::1",
                        "fe80::1",
                        "2001:db8::1",
                        "2002:7f00:1::1",
                        "3fff::1"))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> FetchPolicy.requirePublic(InetAddress.getByName(ip)),
                    ip);
        FetchPolicy.requirePublic(publicIp());
        FetchPolicy.requirePublic(InetAddress.getByName("2606:4700:4700::1111"));
    }

    @Test
    void allDnsAnswersMustBePublicBeforeTransport() throws Exception {
        AtomicInteger transport = new AtomicInteger();
        InetAddress good = publicIp();
        try (var fetcher =
                new SafeMetadataFetcher(
                        1,
                        1024,
                        2,
                        1000,
                        h -> new InetAddress[] {good, InetAddress.getByName("127.0.0.1")},
                        (u, a, b, t) -> {
                            transport.incrementAndGet();
                            return null;
                        })) {
            assertThrows(IOException.class, () -> fetcher.fetch("https://example.org"));
            assertEquals(0, transport.get());
        }
    }

    @Test
    void pinnedAddressesAndEveryRedirectAreChecked() throws Exception {
        AtomicInteger lookups = new AtomicInteger(), requests = new AtomicInteger();
        InetAddress good = publicIp();
        try (var fetcher =
                new SafeMetadataFetcher(
                        1,
                        1024,
                        2,
                        1000,
                        h -> {
                            lookups.incrementAndGet();
                            return new InetAddress[] {
                                h.equals("example.org") ? good : InetAddress.getByName("127.0.0.1")
                            };
                        },
                        (u, a, b, t) -> {
                            assertEquals(good, a[0]);
                            requests.incrementAndGet();
                            return new SafeMetadataFetcher.Response(
                                    302, "http://internal.example/admin", "text/html", new byte[0]);
                        })) {
            assertThrows(IOException.class, () -> fetcher.fetch("https://example.org"));
            assertEquals(2, lookups.get());
            assertEquals(1, requests.get());
        }
    }

    @Test
    void htmlIsFetchedOnceAndForeignIconIsNotEmitted() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        InetAddress good = publicIp();
        try (var fetcher =
                new SafeMetadataFetcher(
                        1,
                        2048,
                        2,
                        1000,
                        h -> new InetAddress[] {good},
                        (u, a, b, t) -> {
                            calls.incrementAndGet();
                            assertNull(u.getFragment());
                            return new SafeMetadataFetcher.Response(
                                    200,
                                    null,
                                    "text/html; charset=utf-8",
                                    "<title>Hello 中文</title><link rel='icon' href='http://127.0.0.1/a'>"
                                            .getBytes(StandardCharsets.UTF_8));
                        })) {
            var result = fetcher.fetch("https://example.org/path#client");
            assertEquals("Hello 中文", result.title());
            assertNull(result.favicon());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void redirectAndBodyLimitsApplyToUntrustedTransport() throws Exception {
        InetAddress good = publicIp();
        try (var fetcher =
                new SafeMetadataFetcher(
                        1,
                        1024,
                        1,
                        1000,
                        h -> new InetAddress[] {good},
                        (u, a, b, t) ->
                                new SafeMetadataFetcher.Response(
                                        302, "/loop", "text/html", new byte[0]))) {
            assertThrows(IOException.class, () -> fetcher.fetch("https://example.org"));
        }
        try (var fetcher =
                new SafeMetadataFetcher(
                        1,
                        1024,
                        1,
                        1000,
                        h -> new InetAddress[] {good},
                        (u, a, b, t) ->
                                new SafeMetadataFetcher.Response(
                                        200, null, "text/html", new byte[1025]))) {
            assertThrows(IOException.class, () -> fetcher.fetch("https://example.org"));
        }
    }

    @Test
    void dnsTimeoutAndSaturatedFetchSlotsHaveBoundedFailure() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        InetAddress good = publicIp();
        try (var fetcher =
                new SafeMetadataFetcher(
                        1,
                        1024,
                        1,
                        150,
                        h -> {
                            entered.countDown();
                            try {
                                release.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return new InetAddress[] {good};
                        },
                        (u, a, b, t) -> null)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> first =
                        executor.submit(
                                () ->
                                        assertThrows(
                                                IOException.class,
                                                () -> fetcher.fetch("https://example.org")));
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                assertThrows(IOException.class, () -> fetcher.fetch("https://example.org"));
                first.get(1, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void urlPolicyRejectsCredentialsPortsAndSchemes() {
        for (String url :
                List.of(
                        "file:///etc/passwd",
                        "https://user:pass@example.org",
                        "http://example.org:8080",
                        "https://localhost/",
                        "http://[fe80::1%25eth0]/",
                        "http://example.org/ bad"))
            assertThrows(IllegalArgumentException.class, () -> FetchPolicy.uri(url));
    }
}
