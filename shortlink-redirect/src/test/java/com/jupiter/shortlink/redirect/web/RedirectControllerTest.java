package com.jupiter.shortlink.redirect.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.contract.*;
import com.jupiter.shortlink.redirect.TestConfig;
import com.jupiter.shortlink.redirect.cache.RouteResolver;
import com.jupiter.shortlink.redirect.event.RequestEventPublisher;
import com.jupiter.shortlink.redirect.risk.*;
import com.jupiter.shortlink.redirect.route.RouteInfo;
import com.jupiter.shortlink.risk.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;

class RedirectControllerTest {
    final RouteResolver routes = mock(RouteResolver.class);
    final PolicyResolver policies = mock(PolicyResolver.class);
    final RedisRiskRateLimiter rate = mock(RedisRiskRateLimiter.class);
    final RequestEventPublisher events = mock(RequestEventPublisher.class);
    final Clock clock = Clock.fixed(Instant.ofEpochMilli(1500), ZoneOffset.UTC);

    RedirectController controller() {
        return new RedirectController(routes, policies, rate, events, TestConfig.defaults(), clock);
    }

    void allowed() {
        when(routes.resolve("s.example", "Ab"))
                .thenReturn(
                        Mono.just(
                                new RouteInfo(
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
                                        1)));
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
        when(rate.evaluate(anyString(), any())).thenAnswer(i -> Mono.just(i.getArgument(1)));
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

    @Test
    void getSetsCookieAndEnqueuesExactlyOneClickAndResult() {
        allowed();
        var exchange = request(HttpMethod.GET);
        Clock advancingClock = mock(Clock.class);
        var time = new java.util.concurrent.atomic.AtomicLong(1500);
        when(advancingClock.millis()).thenAnswer(ignored -> time.getAndIncrement());
        new RedirectController(
                        routes, policies, rate, events, TestConfig.defaults(), advancingClock)
                .redirect("Ab", exchange)
                .block();
        assertEquals(HttpStatus.FOUND, exchange.getResponse().getStatusCode());
        var cookie = exchange.getResponse().getCookies().getFirst("sl_uv");
        assertNotNull(cookie);
        assertEquals("/", cookie.getPath());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.isSecure());
        assertEquals(Duration.ofDays(30), cookie.getMaxAge());
        var click = org.mockito.ArgumentCaptor.forClass(ClickEventV1.class);
        var result = org.mockito.ArgumentCaptor.forClass(GatewayRequestEventV1.class);
        verify(events).click(click.capture());
        verify(events).result(result.capture());
        assertTrue(EventIdentity.valid(click.getValue().eventId(), click.getValue().occurredAt()));
        assertTrue(
                EventIdentity.valid(
                        result.getValue().decisionId(), result.getValue().occurredAt()));
        assertFalse(
                EventIdentity.valid(click.getValue().eventId(), click.getValue().occurredAt() + 1));
        assertFalse(
                EventIdentity.valid(
                        result.getValue().decisionId(), result.getValue().occurredAt() + 1));
        assertNotEquals(click.getValue().eventId(), result.getValue().decisionId());
    }

    @Test
    void headRedirectsWithoutPvOrCookie() {
        allowed();
        var exchange = request(HttpMethod.HEAD);
        controller().redirect("Ab", exchange).block();
        assertEquals(HttpStatus.FOUND, exchange.getResponse().getStatusCode());
        assertTrue(exchange.getResponse().getCookies().isEmpty());
        verify(events, never()).click(any());
        verify(events).result(any());
    }

    @Test
    void unknownPolicyReturns503WithoutClick() {
        allowed();
        when(policies.resolve("1", 5))
                .thenReturn(Mono.error(new IllegalStateException("unavailable")));
        var exchange = request(HttpMethod.GET);
        controller().redirect("Ab", exchange).block();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        verify(events, never()).click(any());
    }

    @Test
    void unsafeMethodsDoNotResolveOrEmitClicks() {
        var exchange = request(HttpMethod.POST);
        controller().redirect("Ab", exchange).block();
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, exchange.getResponse().getStatusCode());
        verifyNoInteractions(routes, policies);
    }

    @Test
    void unavailableLinkReturnsLocal404PageWhileHeadAndJsonHaveNoBody() {
        when(routes.resolve("s.example", "Ab"))
                .thenReturn(
                        Mono.just(
                                new RouteInfo(
                                        "s.example",
                                        "Ab",
                                        5,
                                        "1",
                                        "g",
                                        null,
                                        "DELETED",
                                        null,
                                        1,
                                        1,
                                        1000,
                                        2000,
                                        1)));
        var get = request(HttpMethod.GET);
        controller().redirect("Ab", get).block();
        assertEquals(HttpStatus.NOT_FOUND, get.getResponse().getStatusCode());
        assertNull(get.getResponse().getHeaders().getLocation());
        assertTrue(get.getResponse().getHeaders().getCacheControl().contains("no-store"));
        assertTrue(get.getResponse().getBodyAsString().block().contains("短链接不可用"));
        var head = request(HttpMethod.HEAD);
        controller().redirect("Ab", head).block();
        assertEquals(HttpStatus.NOT_FOUND, head.getResponse().getStatusCode());
        assertEquals("", head.getResponse().getBodyAsString().defaultIfEmpty("").block());
        var json = request(HttpMethod.GET);
        controller()
                .redirect(
                        "Ab",
                        json.mutate()
                                .request(
                                        builder ->
                                                builder.headers(
                                                        headers ->
                                                                headers.setAccept(
                                                                        List.of(
                                                                                MediaType
                                                                                        .APPLICATION_JSON))))
                                .build())
                .block();
        assertEquals(HttpStatus.NOT_FOUND, json.getResponse().getStatusCode());
        assertEquals("", json.getResponse().getBodyAsString().defaultIfEmpty("").block());
        verify(events, never()).click(any());
    }
}
