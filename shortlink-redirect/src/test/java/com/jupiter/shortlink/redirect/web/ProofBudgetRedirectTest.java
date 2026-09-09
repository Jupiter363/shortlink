package com.jupiter.shortlink.redirect.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.contract.ClickEventV1;
import com.jupiter.shortlink.contract.GatewayRequestEventV1;
import com.jupiter.shortlink.redirect.TestConfig;
import com.jupiter.shortlink.redirect.cache.CacheGeneration;
import com.jupiter.shortlink.redirect.cache.RedisRouteCache;
import com.jupiter.shortlink.redirect.cache.RouteResolver;
import com.jupiter.shortlink.redirect.event.RequestEventPublisher;
import com.jupiter.shortlink.redirect.risk.*;
import com.jupiter.shortlink.redirect.route.*;
import com.jupiter.shortlink.risk.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

class ProofBudgetRedirectTest {
    final AtomicLong now = new AtomicLong(1000);
    final Clock clock = mock(Clock.class);
    final RedisRouteCache redis = mock(RedisRouteCache.class);
    final RouteAuthority routeAuthority = mock(RouteAuthority.class);
    final PolicyAuthority policyAuthority = mock(PolicyAuthority.class);
    final RedisRiskRateLimiter rates = mock(RedisRiskRateLimiter.class);
    final RequestEventPublisher events = mock(RequestEventPublisher.class);

    RouteInfo route(long checked) {
        return new RouteInfo(
                "s.example",
                "Ab",
                5,
                "1",
                "g",
                checked == 1000 ? "https://old.example/path" : "https://new.example/path",
                "ACTIVE",
                null,
                checked == 1000 ? 1 : 2,
                1,
                checked,
                checked + 1000,
                1);
    }

    PolicySnapshot policy(long checked) {
        return new PolicySnapshot(
                "1:5",
                checked == 1000 ? 1 : 2,
                checked,
                null,
                checked + 1000,
                PolicyState.KNOWN_RESTRICTED,
                false,
                true,
                "UTC",
                List.of(),
                Set.of(),
                new RateLimitRule(10, 60));
    }

    MockServerWebExchange request(HttpMethod method) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.method(method, "http://s.example/Ab")
                        .header("Host", "s.example")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-For", "198.51.100.1")
                        .remoteAddress(new InetSocketAddress("127.0.0.1", 1234))
                        .build());
    }

    RedirectController controller(long processingMillis) {
        when(clock.millis()).thenAnswer(ignored -> now.get());
        when(redis.get(anyString(), anyString(), anyLong())).thenReturn(Mono.empty());
        when(redis.put(any())).thenReturn(Mono.just(true));
        when(routeAuthority.find("s.example", "Ab", 1))
                .thenAnswer(ignored -> Mono.just(route(now.get())));
        when(policyAuthority.read("1", 5)).thenAnswer(ignored -> Mono.just(policy(now.get())));
        CacheGeneration generation = mock(CacheGeneration.class);
        when(generation.current()).thenReturn(1L);
        RouteResolver routes =
                new RouteResolver(
                        redis,
                        routeAuthority,
                        generation,
                        Mono::empty,
                        clock,
                        10,
                        2,
                        1000,
                        30000,
                        500);
        PolicyResolver policies =
                new PolicyResolver(policyAuthority, Mono::empty, clock, 10, 2, 1000, 500);
        // Preload both caches with a proof which remains valid for only 1 ms at request arrival.
        routes.resolve("s.example", "Ab").block();
        policies.resolve("1", 5).block();
        now.set(1999);
        when(redis.get("s.example", "Ab", 1)).thenReturn(Mono.just(route(1000)));
        when(rates.evaluate(eq("1:5"), any()))
                .thenAnswer(
                        invocation ->
                                Mono.fromSupplier(
                                        () -> {
                                            now.addAndGet(processingMillis);
                                            return invocation.getArgument(1);
                                        }));
        return new RedirectController(
                routes, policies, rates, events, TestConfig.defaults(), clock);
    }

    @Test
    void refreshesBothProofsBeforeChargingQuotaAndRedirectsToTheNewTarget() {
        var controller = controller(20);
        var exchange = request(HttpMethod.GET);
        controller.redirect("Ab", exchange).block();
        assertEquals(HttpStatus.FOUND, exchange.getResponse().getStatusCode());
        assertEquals(
                "https://new.example/path",
                exchange.getResponse().getHeaders().getLocation().toString());
        verify(routeAuthority, times(2)).find("s.example", "Ab", 1);
        verify(policyAuthority, times(2)).read("1", 5);
        verify(rates, times(1)).evaluate(eq("1:5"), argThat(value -> value.policyRevision() == 2));
        verify(events, times(1)).click(any(ClickEventV1.class));
        verify(events, times(1)).result(argThat(value -> value.status() == 302));
    }

    @Test
    void headRefreshesProofsButDoesNotCreateAClickOrCookie() {
        var controller = controller(20);
        var exchange = request(HttpMethod.HEAD);
        controller.redirect("Ab", exchange).block();
        assertEquals(HttpStatus.FOUND, exchange.getResponse().getStatusCode());
        assertTrue(exchange.getResponse().getCookies().isEmpty());
        assertEquals("", exchange.getResponse().getBodyAsString().defaultIfEmpty("").block());
        verify(events, never()).click(any());
        verify(events, times(1)).result(any(GatewayRequestEventV1.class));
        verify(rates, times(1)).evaluate(eq("1:5"), any());
    }

    @Test
    void finalProofCheckStillFailsClosedAfterAClockJumpAndNeverChargesQuotaTwice() {
        var controller = controller(1001);
        var exchange = request(HttpMethod.GET);
        controller.redirect("Ab", exchange).block();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertNull(exchange.getResponse().getHeaders().getLocation());
        verify(rates, times(1)).evaluate(eq("1:5"), any());
        verify(events, never()).click(any());
        verify(events)
                .result(
                        argThat(
                                value ->
                                        value.status() == 503
                                                && "ROUTE_PROOF_EXPIRED".equals(value.reason())));
    }

    @Test
    void freshNaturallyClippedPolicyIsUsableBeforeTransition() {
        var controller = controller(20);
        when(policyAuthority.read("1", 5))
                .thenReturn(
                        Mono.just(
                                new PolicySnapshot(
                                        "1:5",
                                        2,
                                        1999,
                                        2100L,
                                        2100,
                                        PolicyState.KNOWN_RESTRICTED,
                                        false,
                                        true,
                                        "UTC",
                                        List.of(),
                                        Set.of(),
                                        new RateLimitRule(10, 60))));
        var exchange = request(HttpMethod.GET);
        controller.redirect("Ab", exchange).block();
        assertEquals(HttpStatus.FOUND, exchange.getResponse().getStatusCode());
        verify(rates, times(1)).evaluate(eq("1:5"), any());
        verify(events).click(any());
    }

    @Test
    void naturalPolicyTransitionDuringProcessingStillFailsClosedWithoutRepeatingQuota() {
        var controller = controller(102);
        when(policyAuthority.read("1", 5))
                .thenReturn(
                        Mono.just(
                                new PolicySnapshot(
                                        "1:5",
                                        2,
                                        1999,
                                        2100L,
                                        2100,
                                        PolicyState.KNOWN_RESTRICTED,
                                        false,
                                        true,
                                        "UTC",
                                        List.of(),
                                        Set.of(),
                                        new RateLimitRule(10, 60))));
        var exchange = request(HttpMethod.GET);
        controller.redirect("Ab", exchange).block();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertNull(exchange.getResponse().getHeaders().getLocation());
        verify(rates, times(1)).evaluate(eq("1:5"), any());
        verify(events, never()).click(any());
        verify(events)
                .result(
                        argThat(
                                value ->
                                        value.status() == 503
                                                && !"ROUTE_PROOF_EXPIRED".equals(value.reason())));
    }

    @Test
    void disabledPolicyNearItsNaturalTransitionKeepsItsBusinessDenialStatus() {
        var controller = controller(20);
        when(policyAuthority.read("1", 5))
                .thenReturn(
                        Mono.just(
                                new PolicySnapshot(
                                        "1:5",
                                        2,
                                        1999,
                                        2100L,
                                        2100,
                                        PolicyState.KNOWN_RESTRICTED,
                                        true,
                                        true,
                                        "UTC",
                                        List.of(),
                                        Set.of(),
                                        null)));
        var exchange = request(HttpMethod.GET);
        controller.redirect("Ab", exchange).block();
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        verify(rates, times(1)).evaluate(eq("1:5"), argThat(value -> !value.allowed()));
        verify(events, never()).click(any());
    }

    @Test
    void clockRollbackCannotUseARouteWhoseAuthorityObservationIsNowInTheFuture() {
        var controller = controller(-100);
        when(policyAuthority.read("1", 5))
                .thenReturn(
                        Mono.just(
                                new PolicySnapshot(
                                        "1:5",
                                        2,
                                        1700,
                                        null,
                                        2700,
                                        PolicyState.KNOWN_RESTRICTED,
                                        false,
                                        true,
                                        "UTC",
                                        List.of(),
                                        Set.of(),
                                        new RateLimitRule(10, 60))));
        var exchange = request(HttpMethod.GET);
        controller.redirect("Ab", exchange).block();
        assertEquals(1899, now.get());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertNull(exchange.getResponse().getHeaders().getLocation());
        verify(rates, times(1)).evaluate(eq("1:5"), any());
        verify(events, never()).click(any());
    }
}
