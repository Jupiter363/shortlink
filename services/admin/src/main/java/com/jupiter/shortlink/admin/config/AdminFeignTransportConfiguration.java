package com.jupiter.shortlink.admin.config;

import feign.Client;
import feign.Retryer;
import feign.hc5.ApacheHttp5Client;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Explicit selection avoids depending on incidental HTTP libraries brought in by other starters. */
@Configuration(proxyBeanMethods = false)
public class AdminFeignTransportConfiguration {
    /** Adding the HC5 adapter must not silently switch workstation/test profiles to its defaults. */
    @Bean
    @Profile("!production")
    Client nonProductionFeignClient() {
        return new Client.Default(null, null);
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("production")
    public static class Production {
        @Bean
        Settings adminFeignTransportSettings(
                @Value("${shortlink.admin.transport.max-total:32}") int total,
                @Value("${shortlink.admin.transport.max-per-route:32}") int perRoute,
                @Value("${shortlink.admin.transport.lease-timeout-ms:250}") long lease,
                @Value("${shortlink.admin.transport.connect-timeout-ms:1000}") long connect,
                @Value("${shortlink.admin.transport.read-timeout-ms:5000}") long read,
                @Value("${shortlink.admin.transport.keep-alive-ms:1000}") long keepAlive,
                @Value("${shortlink.admin.transport.idle-evict-ms:1000}") long idle,
                @Value("${shortlink.admin.transport.validate-after-ms:200}") long validate,
                @Value("${shortlink.admin.transport.ttl-ms:30000}") long ttl) {
            return new Settings(total, perRoute, lease, connect, read, keepAlive, idle, validate, ttl);
        }

        @Bean(destroyMethod = "close")
        PoolingHttpClientConnectionManager adminFeignConnectionManager(
                Settings settings, ObjectProvider<MeterRegistry> registries) {
            var pool = pool(settings);
            registries.ifAvailable(registry -> {
                Gauge.builder("shortlink.admin.feign.pool.leased", pool,
                        p -> p.getTotalStats().getLeased()).register(registry);
                Gauge.builder("shortlink.admin.feign.pool.pending", pool,
                        p -> p.getTotalStats().getPending()).register(registry);
                Gauge.builder("shortlink.admin.feign.pool.available", pool,
                        p -> p.getTotalStats().getAvailable()).register(registry);
                Gauge.builder("shortlink.admin.feign.pool.max", pool,
                        PoolingHttpClientConnectionManager::getMaxTotal).register(registry);
            });
            return pool;
        }

        @Bean(destroyMethod = "close")
        CloseableHttpClient adminFeignHttpClient(
                @Qualifier("adminFeignConnectionManager") PoolingHttpClientConnectionManager pool,
                Settings settings) {
            return httpClient(pool, settings);
        }

        @Bean
        Client adminFeignClient(@Qualifier("adminFeignHttpClient") CloseableHttpClient httpClient) {
            return new ApacheHttp5Client(httpClient);
        }

        @Bean
        Retryer adminFeignRetryer() {
            // A failed POST may already have committed. Only the caller's durable request ID can
            // resolve that ambiguity; neither Feign nor HC5 may replay it automatically.
            return Retryer.NEVER_RETRY;
        }
    }

    public record Settings(int maxTotal, int maxPerRoute, long leaseTimeoutMillis,
            long connectTimeoutMillis, long readTimeoutMillis, long keepAliveMillis,
            long idleEvictMillis, long validateAfterMillis, long ttlMillis) {
        public Settings {
            if (maxTotal < 1 || maxTotal > 64 || maxPerRoute < 1 || maxPerRoute > maxTotal
                    || leaseTimeoutMillis < 1 || leaseTimeoutMillis > 1000
                    || connectTimeoutMillis < 1 || connectTimeoutMillis > 5000
                    || readTimeoutMillis < 1 || readTimeoutMillis > 10000
                    || keepAliveMillis < 1 || keepAliveMillis > 1000
                    || idleEvictMillis < 1 || idleEvictMillis > keepAliveMillis
                    || validateAfterMillis < 1 || validateAfterMillis > idleEvictMillis
                    || ttlMillis < keepAliveMillis || ttlMillis > 60000) {
                throw new IllegalArgumentException("Invalid bounded Admin transport settings");
            }
        }
    }

    static PoolingHttpClientConnectionManager pool(Settings settings) {
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setPoolConcurrencyPolicy(PoolConcurrencyPolicy.STRICT)
                .setMaxConnTotal(settings.maxTotal())
                .setMaxConnPerRoute(settings.maxPerRoute())
                .setConnectionTimeToLive(TimeValue.ofMilliseconds(settings.ttlMillis()))
                .setValidateAfterInactivity(TimeValue.ofMilliseconds(settings.validateAfterMillis()))
                .setDefaultSocketConfig(SocketConfig.custom()
                        .setSoTimeout(Timeout.ofMilliseconds(settings.readTimeoutMillis())).build())
                .build();
    }

    static CloseableHttpClient httpClient(PoolingHttpClientConnectionManager pool, Settings settings) {
        return HttpClients.custom()
                .setConnectionManager(pool)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(settings.leaseTimeoutMillis()))
                        .setConnectTimeout(Timeout.ofMilliseconds(settings.connectTimeoutMillis()))
                        .setResponseTimeout(Timeout.ofMilliseconds(settings.readTimeoutMillis()))
                        .build())
                .setKeepAliveStrategy((response, context) -> {
                    long advertised = DefaultConnectionKeepAliveStrategy.INSTANCE
                            .getKeepAliveDuration(response, context).toMilliseconds();
                    long bounded = advertised > 0
                            ? Math.min(advertised, settings.keepAliveMillis())
                            : settings.keepAliveMillis();
                    return TimeValue.ofMilliseconds(bounded);
                })
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofMilliseconds(settings.idleEvictMillis()))
                .build();
    }
}
