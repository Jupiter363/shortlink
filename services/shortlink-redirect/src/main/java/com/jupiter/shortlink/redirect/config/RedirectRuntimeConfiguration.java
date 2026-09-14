package com.jupiter.shortlink.redirect.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.membership.JdbcRouteMembershipStore;
import com.jupiter.shortlink.redirect.cache.*;
import com.jupiter.shortlink.redirect.event.*;
import com.jupiter.shortlink.redirect.membership.*;
import com.jupiter.shortlink.redirect.risk.*;
import com.jupiter.shortlink.redirect.route.*;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.boot.actuate.health.*;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;

import javax.sql.DataSource;

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
    LocalRouteMembership routeMembership(
            DataSource dataSource,
            PlatformTransactionManager transactions,
            RouteMembershipProperties membership,
            MeterRegistry meters) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(1);
        return new LocalRouteMembership(
                new JdbcRouteMembershipStore(jdbc, transactions), membership, meters);
    }

    @Bean
    MembershipHintConsumer membershipHintConsumer(
            LocalRouteMembership membership,
            RouteMembershipProperties options,
            RedirectProperties p,
            KafkaTransportProperties transport) {
        return new MembershipHintConsumer(membership, options, p, transport.properties());
    }

    @Bean
    RouteResolver routeResolver(
            RedisRouteCache redis,
            RouteAuthority authority,
            CacheGeneration generation,
            ClusterOriginBudget budget,
            Clock clock,
            RedirectProperties p,
            LocalRouteMembership membership) {
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
                p.requestTimeoutMillis(),
                membership);
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
