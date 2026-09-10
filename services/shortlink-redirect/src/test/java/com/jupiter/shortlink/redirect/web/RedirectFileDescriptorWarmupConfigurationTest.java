package com.jupiter.shortlink.redirect.web;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.client.reactive.ReactorResourceFactory;

import reactor.netty.resources.LoopResources;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class RedirectFileDescriptorWarmupConfigurationTest {
    @Test
    void absentAndFalsePropertyRegisterNoWarmupOrIoHook() {
        for (String enabled : new String[] {null, "false"}) {
            try (var context = new AnnotationConfigApplicationContext()) {
                if (enabled != null) context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("test", Map.of("shortlink.redirect.fd-warmup.enabled", enabled)));
                // Even an invalid target is unused when disabled: no warmup construction or IO.
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("target",
                        Map.of("shortlink.redirect.fd-warmup.target-slots", "invalid-disabled-value")));
                context.register(RedirectFileDescriptorWarmupConfiguration.class);
                context.refresh();
                assertTrue(context.getBeansOfType(RedirectFileDescriptorWarmup.class).isEmpty());
                assertTrue(context.getBeansOfType(WebServerFactoryCustomizer.class).isEmpty());
            }
        }
    }

    @Test
    void enabledConfigurationBindsDefaultAndRejectsInvalidTargetWithoutStartingServer() {
        try (var context = context(Map.of("shortlink.redirect.fd-warmup.enabled", "true"))) {
            assertEquals(1, context.getBeansOfType(RedirectFileDescriptorWarmup.class).size());
            assertEquals(4096, context.getBean(RedirectFileDescriptorWarmup.class).targetSlots());
            assertEquals(1, context.getBeansOfType(WebServerFactoryCustomizer.class).size());
            assertTrue(context.getBeansOfType(NettyReactiveWebServerFactory.class).isEmpty());
        }
        for (String target : new String[] {"0", "1024", "2049", "16384", "true"}) {
            var context = new AnnotationConfigApplicationContext();
            try (context) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                        "shortlink.redirect.fd-warmup.enabled", "true", "shortlink.redirect.fd-warmup.target-slots", target)));
                context.register(RedirectFileDescriptorWarmupConfiguration.class);
                assertThrows(RuntimeException.class, context::refresh);
            }
        }
    }

    @Test
    void realNettyPortCannotListenBeforeWarmupAndOwnedCleanupComplete() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var access = new RedirectFileDescriptorWarmupTest.FakeAccess() {
            @Override public Closeable openNull() throws IOException {
                if (opens == 0) {
                    entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test latch timeout"); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                }
                return super.openNull();
            }
        };
        int port = unusedLocalPort();
        var resources = resources();
        var executor = Executors.newSingleThreadExecutor();
        WebServer server = null;
        java.util.concurrent.Future<WebServer> future = null;
        try {
            var factory = factory(port, resources);
            new RedirectFileDescriptorWarmupConfiguration().redirectFdWarmupCustomizer(
                    new RedirectFileDescriptorWarmup(4096, access, () -> 0)).customize(factory);
            future = executor.submit(() -> {
                WebServer created = factory.getWebServer((request, response) -> response.setComplete());
                assertEquals(access.opens, access.closes, "cleanup must precede WebServer construction/bind");
                created.start();
                return created;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(future.isDone());
            assertNotListening(port);
            release.countDown();
            server = future.get(10, TimeUnit.SECONDS);
            try (var socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 1000); }
            assertEquals(64, access.opens);
            assertEquals(64, access.closes);
        } finally {
            release.countDown();
            if (server == null && future != null) {
                try { server = future.get(10, TimeUnit.SECONDS); } catch (Exception ignored) { }
            }
            if (server != null) server.stop();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            resources.destroy();
        }
        assertNotListening(port);
    }

    @Test
    void failedWarmupPreventsRealNettyBindAndRepeatedFactoryCannotBypassFailure() throws Exception {
        var access = new RedirectFileDescriptorWarmupTest.FakeAccess();
        access.failOpen = 7;
        var warmup = new RedirectFileDescriptorWarmup(4096, access, () -> 0);
        int port = unusedLocalPort();
        var resources = resources();
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                var factory = factory(port, resources);
                new RedirectFileDescriptorWarmupConfiguration().redirectFdWarmupCustomizer(warmup).customize(factory);
                assertThrows(IllegalStateException.class,
                        () -> factory.getWebServer((request, response) -> response.setComplete()));
                assertNotListening(port);
            }
            assertEquals(6, access.opens);
            assertEquals(6, access.closes);
        } finally { resources.destroy(); }
    }

    private AnnotationConfigApplicationContext context(Map<String, Object> values) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", values));
        context.register(RedirectFileDescriptorWarmupConfiguration.class);
        context.refresh();
        return context;
    }

    private static ReactorResourceFactory resources() {
        var resources = new ReactorResourceFactory();
        resources.setUseGlobalResources(false);
        resources.setLoopResourcesSupplier(() -> LoopResources.create("fd-warmup-test", 1, true));
        resources.afterPropertiesSet();
        return resources;
    }

    private static NettyReactiveWebServerFactory factory(int port, ReactorResourceFactory resources) throws Exception {
        var factory = new NettyReactiveWebServerFactory(port);
        factory.setAddress(InetAddress.getByName("127.0.0.1"));
        factory.setResourceFactory(resources);
        factory.setLifecycleTimeout(Duration.ofSeconds(3));
        return factory;
    }

    private static int unusedLocalPort() throws IOException {
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
    }

    private static void assertNotListening(int port) {
        try (var socket = new Socket()) {
            assertThrows(IOException.class, () -> socket.connect(new InetSocketAddress("127.0.0.1", port), 200));
        } catch (IOException error) { throw new AssertionError(error); }
    }
}
