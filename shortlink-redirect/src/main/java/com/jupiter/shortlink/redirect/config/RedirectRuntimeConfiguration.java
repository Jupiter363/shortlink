package com.jupiter.shortlink.redirect.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.redirect.cache.*;
import com.jupiter.shortlink.redirect.event.*;
import com.jupiter.shortlink.redirect.risk.*;
import com.jupiter.shortlink.redirect.route.*;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.boot.actuate.health.*;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;

@Configuration
public class RedirectRuntimeConfiguration {
    @Bean
    Clock redirectClock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "dispose")
    Scheduler authorityScheduler(RedirectProperties p) {
        return Schedulers.newBoundedElastic(
                p.originConcurrency(), 2, "redirect-authority", 60, true);
    }

    @Bean
    RouteAuthority routeAuthority(
            JdbcTemplate jdbc, Scheduler authorityScheduler, Clock clock, RedirectProperties p) {
        jdbc.setQueryTimeout(Math.max(1, (int) Math.ceil(p.requestTimeoutMillis() / 1000.0)));
        return new JdbcRouteAuthority(jdbc, authorityScheduler, clock, p);
    }

    @Bean
    GenerationCoordinator cacheGeneration(
            ReactiveStringRedisTemplate redis,
            JdbcTemplate jdbc,
            Scheduler authorityScheduler,
            Clock clock,
            RedirectProperties p) {
        return new GenerationCoordinator(redis, jdbc, authorityScheduler, clock, p);
    }

    @Bean
    RedisRouteCache redisRouteCache(
            ReactiveStringRedisTemplate redis,
            ObjectMapper json,
            Clock clock,
            RedirectProperties p) {
        return new RedisRouteCache(
                redis,
                json,
                clock,
                p.redisTimeoutMillis(),
                Math.max(30000, p.requestTimeoutMillis() + p.authorityTtlMillis() + 1000));
    }

    @Bean
    ClusterOriginBudget clusterOriginBudget(
            ReactiveStringRedisTemplate redis, Clock clock, RedirectProperties p) {
        return new ClusterOriginBudget(redis, clock, p.clusterOriginRate(), p.redisTimeoutMillis());
    }

    @Bean
    RouteResolver routeResolver(
            RedisRouteCache redis,
            RouteAuthority authority,
            CacheGeneration generation,
            ClusterOriginBudget budget,
            Clock clock,
            RedirectProperties p) {
        return new RouteResolver(
                redis,
                authority,
                generation,
                budget::acquire,
                clock,
                p.cacheEntries(),
                p.originConcurrency() * 2,
                p.authorityTtlMillis(),
                30000,
                p.requestTimeoutMillis());
    }

    @Bean
    PolicyAuthority policyAuthority(WebClient.Builder builder, RedirectProperties p) {
        return new HttpPolicyAuthority(builder, p);
    }

    @Bean
    PolicyResolver policyResolver(
            PolicyAuthority authority,
            ClusterOriginBudget budget,
            Clock clock,
            RedirectProperties p) {
        return new PolicyResolver(
                authority,
                budget::acquire,
                clock,
                p.cacheEntries(),
                p.originConcurrency() * 2,
                p.authorityTtlMillis(),
                p.requestTimeoutMillis());
    }

    @Bean
    RedisRiskRateLimiter riskRateLimiter(
            ReactiveStringRedisTemplate redis, Clock clock, RedirectProperties p) {
        return new RedisRiskRateLimiter(redis, clock, p.redisTimeoutMillis());
    }

    @Bean(destroyMethod = "close")
    RequestEventPublisher eventPublisher(
            RedirectProperties p, MeterRegistry meters, KafkaTransportProperties transport) {
        return new AsyncKafkaPublisher(p, meters, transport.properties());
    }

    @Bean
    ChangeFanout changeFanout(
            RedirectProperties p,
            RouteResolver routes,
            PolicyResolver policies,
            KafkaTransportProperties transport) {
        return new ChangeFanout(p, routes, policies, transport.properties());
    }

    @Bean
    ReactiveHealthIndicator redirectAuthorityHealth(
            CacheGeneration generation, PolicyAuthority policies) {
        return () ->
                Mono.defer(
                                () -> {
                                    generation.current();
                                    return policies.ready()
                                            .map(
                                                    ready ->
                                                            ready
                                                                    ? Health.up().build()
                                                                    : Health.down().build());
                                })
                        .onErrorReturn(Health.down().build());
    }
}
