package com.jupiter.shortlink.redirect.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.contract.*;
import com.jupiter.shortlink.redirect.TestConfig;
import com.jupiter.shortlink.redirect.cache.*;
import com.jupiter.shortlink.redirect.event.RequestEventPublisher;
import com.jupiter.shortlink.redirect.membership.RouteMembershipGuard;
import com.jupiter.shortlink.redirect.risk.*;
import com.jupiter.shortlink.redirect.route.*;
import com.jupiter.shortlink.risk.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class BloomRedirectControllerTest {
    final RedisRouteCache redis = mock(RedisRouteCache.class);
    final RouteAuthority authority = mock(RouteAuthority.class);
    final PolicyResolver policies = mock(PolicyResolver.class);
    final RedisRiskRateLimiter rates = mock(RedisRiskRateLimiter.class);
    final RequestEventPublisher events = mock(RequestEventPublisher.class);
    final AtomicBoolean valid = new AtomicBoolean(true);
    final AtomicBoolean positive = new AtomicBoolean(false);
    final AtomicInteger checks = new AtomicInteger();
    final AtomicLong epoch = new AtomicLong(1);
    final Clock clock = Clock.fixed(Instant.ofEpochMilli(1500), ZoneOffset.UTC);
    final RouteMembershipGuard.AbsentProof proof = new RouteMembershipGuard.AbsentProof() {};
    final RouteMembershipGuard guard =
            new RouteMembershipGuard() {
                public Check check(String domain, String uri) {
                    checks.incrementAndGet();
                    return positive.get()
                            ? Check.maybe()
                            : valid.get() ? Check.absent(proof) : Check.unknown();
                }

                public boolean valid(AbsentProof captured) {
                    return valid.get() && captured == proof;
                }
            };

    RouteResolver resolver() {
        when(redis.get(anyString(), anyString(), anyLong())).thenReturn(Mono.empty());
        when(redis.put(any())).thenReturn(Mono.just(true));
        return new RouteResolver(
                redis,
                authority,
                new CacheGeneration() {
                    public long current() {
                        return epoch.get();
                    }

                    public Mono<Long> refresh() {
                        return Mono.just(epoch.get());
                    }
                },
                Mono::empty,
                clock,
                10,
                1,
                1000,
                30000,
                100,
                guard);
    }

    RouteInfo route() {
        return new RouteInfo(
                "s.example",
                "Ab",
                5,
                "1",
                "g",
                "https://target.example/path",
                "ACTIVE",
                null,
                1,
                1,
                1000,
                2000,
                epoch.get());
    }

    void allowed() {
        when(authority.find(eq("s.example"), eq("Ab"), anyLong()))
                .thenAnswer(ignored -> Mono.just(route()));
        when(policies.resolve("1", 5))
                .thenReturn(
                        Mono.just(
                                new PolicySnapshot(
                                        "1:5",
                                        1,
                                        1000,
                                        null,
                                        2000,
                                        PolicyState.KNOWN_ALLOWED,
                                        false,
                                        true,
                                        "UTC",
                                        List.of(),
                                        Set.of(),
                                        null)));
        when(rates.evaluate(anyString(), any())).thenAnswer(call -> Mono.just(call.getArgument(1)));
    }

    MockServerWebExchange request() {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("http://s.example/Ab")
                        .header("Host", "s.example")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-For", "198.51.100.1")
                        .remoteAddress(new InetSocketAddress("127.0.0.1", 1234))
                        .build());
    }

    RedirectController controller(RouteResolver routes) {
        return new RedirectController(
                routes, policies, rates, events, TestConfig.defaults(), clock);
    }

    @Test
    void reliableNegativeSkipsRedisFlightsAndAuthorityAndEmitsOnlyRequestOutcome() {
        var routes = resolver();
        var exchange = request();
        controller(routes).redirect("Ab", exchange).block();
        assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
        assertTrue(exchange.getResponse().isCommitted());
        verifyNoInteractions(redis, authority, policies, rates);
        verify(events, never()).click(any());
        verify(events)
                .result(
                        argThat(
                                event ->
                                        event.status() == 404
                                                && event.linkId() == null
                                                && event.source() == RequestSource.REDIRECT
                                                && event.stage() == DecisionStage.BUSINESS));
        assertTrue(exchange.getResponse().getBodyAsString().block().contains("短链接不可用"));
    }

    @Test
    void lateCommitHookExpiresProofAndUsesOriginalRouteChainOnceWithoutA404Event() {
        var routes = resolver();
        allowed();
        var exchange = request();
        exchange.getResponse().beforeCommit(() -> Mono.fromRunnable(() -> valid.set(false)));
        controller(routes).redirect("Ab", exchange).block();
        assertEquals(HttpStatus.FOUND, exchange.getResponse().getStatusCode());
        assertEquals(
                "https://target.example/path",
                exchange.getResponse().getHeaders().getLocation().toString());
        verify(authority, times(1)).find("s.example", "Ab", 1);
        verify(events, times(1)).click(any());
        verify(events).result(argThat(event -> event.status() == 302));
        verify(events, never()).result(argThat(event -> event.status() == 404));
        assertEquals(1, checks.get());
    }

    @Test
    void cacheGenerationChangeAtCommitAlsoInvalidatesNegative() {
        var routes = resolver();
        allowed();
        var exchange = request();
        exchange.getResponse().beforeCommit(() -> Mono.fromRunnable(() -> epoch.set(2)));
        controller(routes).redirect("Ab", exchange).block();
        assertEquals(HttpStatus.FOUND, exchange.getResponse().getStatusCode());
        verify(authority).find("s.example", "Ab", 2);
        verify(events, never()).result(argThat(event -> event.status() == 404));
    }

    @Test
    void expiryFallbackFailureProduces503InsteadOfExtendingDenial() {
        var routes = resolver();
        var exchange = request();
        when(authority.find("s.example", "Ab", 1))
                .thenReturn(Mono.error(new AuthorityUnavailableException("test")));
        exchange.getResponse().beforeCommit(() -> Mono.fromRunnable(() -> valid.set(false)));
        controller(routes).redirect("Ab", exchange).block();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        verify(events).result(argThat(event -> event.status() == 503));
        verify(events, never()).result(argThat(event -> event.status() == 404));
        verify(events, never()).click(any());
    }

    @Test
    void positiveStillRunsPolicyAndThenL1HitPerformsNoBloomCheck() {
        var routes = resolver();
        allowed();
        positive.set(true);
        controller(routes).redirect("Ab", request()).block();
        controller(routes).redirect("Ab", request()).block();
        assertEquals(1, checks.get());
        verify(policies, times(2)).resolve("1", 5);
        verify(authority, times(1)).find("s.example", "Ab", 1);
        verify(events, times(2)).click(any());
    }

    @Test
    void originalDeadlineIncludesCommitDelayAndFallback() {
        var routes = resolver();
        allowed();
        var exchange = request();
        long timeout = TestConfig.defaults().requestTimeoutMillis();
        exchange.getResponse()
                .beforeCommit(
                        () ->
                                Mono.delay(Duration.ofMillis(timeout - 10))
                                        .doOnNext(ignored -> valid.set(false))
                                        .then());
        when(authority.find("s.example", "Ab", 1))
                .thenReturn(Mono.delay(Duration.ofMillis(20)).map(ignored -> route()));
        StepVerifier.withVirtualTime(() -> controller(routes).redirect("Ab", exchange))
                .thenAwait(Duration.ofMillis(timeout + 1))
                .verifyComplete();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        verify(events, never()).click(any());
        verify(events, never()).result(argThat(event -> event.status() == 404));
    }
}
