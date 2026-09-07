package com.jupiter.shortlink.command.metadata;

import jakarta.annotation.PreDestroy;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.util.Timeout;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

@Service
public class SafeMetadataFetcher implements AutoCloseable {
    public record Metadata(String title, String favicon) {}

    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    interface Transport {
        Response get(URI uri, InetAddress[] addresses, int maxBytes, long remainingMillis)
                throws IOException;
    }

    record Response(int status, String location, String contentType, byte[] body) {}

    private final int maxBytes, maxRedirects;
    private final long timeoutMillis;
    private final Semaphore slots;
    private final ThreadPoolExecutor dns;
    private final ScheduledThreadPoolExecutor deadlines;
    private final Resolver resolver;
    private final Transport transport;

    @org.springframework.beans.factory.annotation.Autowired
    public SafeMetadataFetcher(
            @Value("${shortlink.metadata.fetch-concurrency:8}") int concurrency,
            @Value("${shortlink.metadata.max-body-bytes:1048576}") int maxBytes,
            @Value("${shortlink.metadata.max-redirects:3}") int redirects,
            @Value("${shortlink.metadata.fetch-timeout-millis:4000}") long timeoutMillis) {
        this(concurrency, maxBytes, redirects, timeoutMillis, InetAddress::getAllByName, null);
    }

    SafeMetadataFetcher(
            int concurrency,
            int maxBytes,
            int redirects,
            long timeoutMillis,
            Resolver resolver,
            Transport transport) {
        if (concurrency < 1
                || concurrency > 32
                || maxBytes < 1024
                || maxBytes > 2097152
                || redirects < 0
                || redirects > 5
                || timeoutMillis < 100
                || timeoutMillis > 10000)
            throw new IllegalArgumentException("Invalid fetch budget");
        this.maxBytes = maxBytes;
        this.maxRedirects = redirects;
        this.timeoutMillis = timeoutMillis;
        this.resolver = resolver;
        slots = new Semaphore(concurrency);
        dns =
                new ThreadPoolExecutor(
                        concurrency,
                        concurrency,
                        0,
                        TimeUnit.MILLISECONDS,
                        new SynchronousQueue<>(),
                        r -> {
                            Thread t = new Thread(r, "metadata-dns");
                            t.setDaemon(true);
                            return t;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        deadlines =
                new ScheduledThreadPoolExecutor(
                        1,
                        r -> {
                            Thread t = new Thread(r, "metadata-deadline");
                            t.setDaemon(true);
                            return t;
                        });
        deadlines.setRemoveOnCancelPolicy(true);
        this.transport = transport == null ? this::http : transport;
    }

    public Metadata fetch(String url) throws IOException {
        if (!slots.tryAcquire()) throw new IOException("METADATA_FETCH_CAPACITY");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            URI current = FetchPolicy.uri(url);
            for (int hop = 0; hop <= maxRedirects; hop++) {
                InetAddress[] addresses = resolve(FetchPolicy.host(current), deadline);
                if (addresses.length < 1 || addresses.length > 16)
                    throw new IOException("DNS answer budget exceeded");
                for (InetAddress address : addresses) FetchPolicy.requirePublic(address);
                Response response =
                        transport.get(current, addresses.clone(), maxBytes, remaining(deadline));
                if (response.status() >= 300 && response.status() < 400) {
                    if (hop == maxRedirects || response.location() == null)
                        throw new IOException("Redirect budget exhausted");
                    current = FetchPolicy.uri(current.resolve(response.location()).toString());
                    continue;
                }
                if (response.status() != 200)
                    throw new IOException("Metadata HTTP status " + response.status());
                if (response.body().length > maxBytes)
                    throw new IOException("Metadata byte budget exceeded");
                if (response.contentType() == null
                        || !response.contentType()
                                .toLowerCase(java.util.Locale.ROOT)
                                .startsWith("text/html"))
                    throw new IOException("Metadata content is not HTML");
                remaining(deadline);
                Document doc =
                        Jsoup.parse(
                                new ByteArrayInputStream(response.body()),
                                null,
                                current.toString());
                String title = doc.title().strip();
                if (title.length() > 512) title = title.substring(0, 512);
                String favicon = null;
                var icon = doc.selectFirst("link[rel~=(?i)(^|\\s)(shortcut\\s+)?icon($|\\s)]");
                if (icon != null) {
                    try {
                        URI candidate =
                                FetchPolicy.uri(current.resolve(icon.attr("href")).toString());
                        // No extra favicon fetch and no third-party/private URL emitted as an image
                        // target.
                        if (candidate.getRawAuthority().equals(current.getRawAuthority())
                                && candidate.getScheme().equals(current.getScheme()))
                            favicon = candidate.toString();
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                remaining(deadline);
                return new Metadata(title, favicon);
            }
            throw new IOException("Redirect limit");
        } catch (IllegalArgumentException e) {
            throw new IOException("METADATA_TARGET_DENIED", e);
        } finally {
            slots.release();
        }
    }

    private InetAddress[] resolve(String host, long deadline) throws IOException {
        Future<InetAddress[]> result;
        try {
            result = dns.submit(() -> resolver.resolve(host));
        } catch (RejectedExecutionException e) {
            throw new IOException("DNS capacity exhausted", e);
        }
        try {
            return result.get(remaining(deadline), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("DNS interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("DNS unavailable within budget", e);
        } finally {
            result.cancel(true);
        }
    }

    Response http(URI uri, InetAddress[] pinned, int budget, long remaining) throws IOException {
        String approved = FetchPolicy.host(uri);
        DnsResolver resolver =
                new DnsResolver() {
                    public InetAddress[] resolve(String host) throws UnknownHostException {
                        if (!approved.equalsIgnoreCase(host))
                            throw new UnknownHostException("Unapproved DNS lookup");
                        return pinned.clone();
                    }

                    public String resolveCanonicalHostname(String host)
                            throws UnknownHostException {
                        if (!approved.equalsIgnoreCase(host))
                            throw new UnknownHostException("Unapproved canonical lookup");
                        return approved;
                    }
                };
        var connections =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(resolver)
                        .setMaxConnTotal(1)
                        .setMaxConnPerRoute(1)
                        .build();
        HttpGet request = new HttpGet(uri);
        request.setHeader("Accept", "text/html");
        request.setHeader("Accept-Encoding", "identity");
        request.setHeader("User-Agent", "ShortLink-Metadata/1.0");
        request.setConfig(
                RequestConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(Math.min(1500, remaining)))
                        .setResponseTimeout(Timeout.ofMilliseconds(remaining))
                        .setConnectionRequestTimeout(
                                Timeout.ofMilliseconds(Math.min(250, remaining)))
                        .build());
        ScheduledFuture<?> cancellation =
                deadlines.schedule(request::cancel, remaining, TimeUnit.MILLISECONDS);
        try (var client =
                HttpClients.custom()
                        .setConnectionManager(connections)
                        .disableRedirectHandling()
                        .disableAutomaticRetries()
                        .disableCookieManagement()
                        .disableContentCompression()
                        .build()) {
            return client.execute(
                    request,
                    response -> {
                        try {
                            Header location = response.getFirstHeader("Location"),
                                    type = response.getFirstHeader("Content-Type"),
                                    encoding = response.getFirstHeader("Content-Encoding");
                            if (encoding != null
                                    && !encoding.getValue().equalsIgnoreCase("identity"))
                                throw new IOException("Compressed metadata denied");
                            byte[] body = new byte[0];
                            if (response.getCode() == 200 && response.getEntity() != null) {
                                long declared = response.getEntity().getContentLength();
                                if (declared > budget)
                                    throw new IOException("Metadata byte budget exceeded");
                                try (InputStream input = response.getEntity().getContent()) {
                                    body = input.readNBytes(budget + 1);
                                    if (body.length > budget)
                                        throw new IOException("Metadata byte budget exceeded");
                                }
                            }
                            return new Response(
                                    response.getCode(),
                                    location == null ? null : location.getValue(),
                                    type == null ? null : type.getValue(),
                                    body);
                        } finally {
                            request.cancel(); /* Do not drain an unbounded redirect/error entity. */
                        }
                    });
        } finally {
            cancellation.cancel(false);
            connections.close();
        }
    }

    private static long remaining(long deadline) throws IOException {
        long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (left < 1) throw new IOException("Metadata deadline exceeded");
        return left;
    }

    @Override
    @PreDestroy
    public void close() {
        dns.shutdownNow();
        deadlines.shutdownNow();
    }
}
