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

    @Test
    void unavailableRequestIdSourceFailsControllerConstruction() {
        IllegalStateException unavailable = new IllegalStateException("test DRBG unavailable");
        try (var ids = mockStatic(SecureRequestIds.class)) {
            ids.when(SecureRequestIds::verifyAvailable).thenThrow(unavailable);

            assertSame(
                    unavailable,
                    assertThrows(IllegalStateException.class, this::controller));

            ids.verify(SecureRequestIds::verifyAvailable, times(1));
            ids.verifyNoMoreInteractions();
            verifyNoInteractions(routes, policies, rate, events);
        }
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
    void trustedGatewayIdLinksGetEventsAndResponseWithoutChangingEventIdentities() {
        allowed();
        String gatewayId = "0123456789abcdef0123456789abcdef";
        var exchange = request(HttpMethod.GET).mutate()
                .request(builder -> builder.header("X-Request-ID", gatewayId)).build();

        controller().redirect("Ab", exchange).block();

        assertEquals(HttpStatus.FOUND, exchange.getResponse().getStatusCode());
        assertEquals(gatewayId, exchange.getResponse().getHeaders().getFirst("X-Request-ID"));
        var click = org.mockito.ArgumentCaptor.forClass(ClickEventV1.class);
        var result = org.mockito.ArgumentCaptor.forClass(GatewayRequestEventV1.class);
        verify(events).click(click.capture());
        verify(events).result(result.capture());
        assertEquals(gatewayId, click.getValue().requestId());
        assertEquals(gatewayId, click.getValue().traceId());
        assertEquals(gatewayId, result.getValue().requestId());
        assertEquals(gatewayId, result.getValue().traceId());
        assertNotEquals(assertBoundUuidV4(click.getValue().eventId(), click.getValue().occurredAt()),
                assertBoundUuidV4(result.getValue().decisionId(), result.getValue().occurredAt()));
    }

    @Test
    void headAndBusinessErrorKeepTheTrustedRequestId() {
        allowed();
        String gatewayId = "abcdef0123456789abcdef0123456789";
        var head = request(HttpMethod.HEAD).mutate()
                .request(builder -> builder.header("X-Request-ID", gatewayId)).build();
        controller().redirect("Ab", head).block();
        assertEquals(HttpStatus.FOUND, head.getResponse().getStatusCode());
        assertEquals(gatewayId, head.getResponse().getHeaders().getFirst("X-Request-ID"));
        verify(events, never()).click(any());
        verify(events).result(argThat(event -> gatewayId.equals(event.requestId()) && event.status() == 302));

        clearInvocations(events);
        when(policies.resolve("1", 5)).thenReturn(Mono.error(new IllegalStateException("unavailable")));
        var failed = request(HttpMethod.GET).mutate()
                .request(builder -> builder.header("X-Request-ID", gatewayId)).build();
        controller().redirect("Ab", failed).block();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failed.getResponse().getStatusCode());
        assertEquals(gatewayId, failed.getResponse().getHeaders().getFirst("X-Request-ID"));
        verify(events, never()).click(any());
        verify(events).result(argThat(event -> gatewayId.equals(event.requestId()) && event.status() == 503));
    }

    @Test
    void malformedAndRepeatedGatewayHeadersUseLocalIdsConsistently() {
        allowed();
        String good = "0123456789abcdef0123456789abcdef";
        List<String[]> headers = List.of(
                new String[] {good, good}, new String[] {good + "," + good},
                new String[] {good.toUpperCase(Locale.ROOT)}, new String[] {" " + good},
                new String[] {good + " "}, new String[] {good.substring(1)},
                new String[] {good + "0"}, new String[] {"g".repeat(32)}, new String[] {""});
        Set<UUID> localIds = new HashSet<>();
        for (String[] values : headers) {
            clearInvocations(events);
            var exchange = request(HttpMethod.GET).mutate()
                    .request(builder -> builder.header("X-Request-ID", values)).build();
            controller().redirect("Ab", exchange).block();
            assertEquals(HttpStatus.FOUND, exchange.getResponse().getStatusCode());
            String actual = exchange.getResponse().getHeaders().getFirst("X-Request-ID");
            assertTrue(localIds.add(assertUuidV4(actual)));
            verify(events).click(argThat(event -> actual.equals(event.requestId())));
            verify(events).result(argThat(event -> actual.equals(event.requestId())));
        }
    }

    @Test
    void untrustedPeerCannotChooseTheResponseOrEventRequestId() {
        String supplied = "0123456789abcdef0123456789abcdef";
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("http://s.example/Ab")
                .header("Host", "s.example")
                .header("X-Request-ID", supplied)
                .header("X-Forwarded-For", "127.0.0.1")
                .remoteAddress(new InetSocketAddress("192.0.2.8", 1234)).build());
        controller().redirect("Ab", exchange).block();
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        String actual = exchange.getResponse().getHeaders().getFirst("X-Request-ID");
        assertUuidV4(actual);
        assertNotEquals(supplied, actual);
        verifyNoInteractions(routes, policies, rate);
        verify(events, never()).click(any());
        verify(events).result(argThat(event -> actual.equals(event.requestId()) && event.status() == 403));
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
        assertTrue(cookie.getValue().matches("[0-9a-f]{12}4[0-9a-f]{3}[89ab][0-9a-f]{15}"));
        assertEquals(cookie.getValue(), click.getValue().uvId());
        assertUuidV4(click.getValue().requestId());
        assertEquals(click.getValue().requestId(), exchange.getResponse().getHeaders().getFirst("X-Request-ID"));
        assertEquals(click.getValue().requestId(), result.getValue().requestId());
        UUID clickNonce =
                assertBoundUuidV4(click.getValue().eventId(), click.getValue().occurredAt());
        UUID resultNonce =
                assertBoundUuidV4(result.getValue().decisionId(), result.getValue().occurredAt());
        assertNotEquals(clickNonce, resultNonce);
        assertNotEquals(click.getValue().requestId(), clickNonce.toString());
        assertNotEquals(click.getValue().requestId(), resultNonce.toString());
    }

    @Test
    void headRedirectsWithoutPvOrCookie() {
        allowed();
        var exchange = request(HttpMethod.HEAD);
        controller().redirect("Ab", exchange).block();
        assertEquals(HttpStatus.FOUND, exchange.getResponse().getStatusCode());
        assertTrue(exchange.getResponse().getCookies().isEmpty());
        verify(events, never()).click(any());
        var result = org.mockito.ArgumentCaptor.forClass(GatewayRequestEventV1.class);
        verify(events).result(result.capture());
        assertUuidV4(result.getValue().requestId());
        assertEquals(result.getValue().requestId(), exchange.getResponse().getHeaders().getFirst("X-Request-ID"));
        assertBoundUuidV4(result.getValue().decisionId(), result.getValue().occurredAt());
    }

    @Test
    void existingVisitorRemainsStableWhileNewRequestsHaveIndependentEventIds() {
        allowed();
        String visitor = "0123456789abcdef0123456789abcdef";
        for (int i = 0; i < 2; i++) {
            var exchange =
                    MockServerWebExchange.from(
                            MockServerHttpRequest.get("http://s.example/Ab")
                                    .header("Host", "s.example")
                                    .header("X-Forwarded-Proto", "https")
                                    .header("X-Forwarded-For", "198.51.100.1")
                                    .cookie(new HttpCookie("sl_uv", visitor))
                                    .remoteAddress(new InetSocketAddress("127.0.0.1", 1234))
                                    .build());
            controller().redirect("Ab", exchange).block();
            assertEquals(HttpStatus.FOUND, exchange.getResponse().getStatusCode());
            assertEquals(
                    "https://target.example/path",
                    exchange.getResponse().getHeaders().getLocation().toString());
            assertTrue(exchange.getResponse().getCookies().isEmpty());
        }
        var clicks = org.mockito.ArgumentCaptor.forClass(ClickEventV1.class);
        var results = org.mockito.ArgumentCaptor.forClass(GatewayRequestEventV1.class);
        verify(events, times(2)).click(clicks.capture());
        verify(events, times(2)).result(results.capture());
        Set<UUID> identities = new HashSet<>();
        for (int i = 0; i < 2; i++) {
            ClickEventV1 click = clicks.getAllValues().get(i);
            GatewayRequestEventV1 result = results.getAllValues().get(i);
            assertEquals(visitor, click.uvId());
            assertEquals(click.requestId(), result.requestId());
            assertTrue(identities.add(assertUuidV4(click.requestId())));
            assertTrue(identities.add(assertBoundUuidV4(click.eventId(), click.occurredAt())));
            assertTrue(identities.add(assertBoundUuidV4(result.decisionId(), result.occurredAt())));
        }
        assertEquals(6, identities.size());
    }

    private static UUID assertBoundUuidV4(String id, long occurredAt) {
        assertTrue(EventIdentity.valid(id, occurredAt));
        String prefix = "v1:" + occurredAt + ":";
        assertTrue(id.startsWith(prefix));
        return assertUuidV4(id.substring(prefix.length()));
    }

    private static UUID assertUuidV4(String id) {
        UUID parsed = UUID.fromString(id);
        assertEquals(id, parsed.toString());
        assertEquals(4, parsed.version());
        assertEquals(2, parsed.variant());
        return parsed;
    }

    @Test
    void policyTransitionDuringRateCheckStillPreventsRedirect() {
        allowed();
        when(routes.resolve("s.example", "Ab"))
                .thenReturn(Mono.just(new RouteInfo("s.example", "Ab", 5, "1", "g",
                        "https://target.example/path", "ACTIVE", null, 1, 1, 1000, 5000, 1)));
        when(policies.resolve("1", 5))
                .thenReturn(Mono.just(new PolicySnapshot("1:5", 1, 1000, 2000L, 5000,
                        PolicyState.KNOWN_ALLOWED, false, true, "UTC", List.of(), Set.of(), null)));
        var now = new java.util.concurrent.atomic.AtomicLong(1500);
        Clock advancingClock = mock(Clock.class);
        when(advancingClock.millis()).thenAnswer(ignored -> now.get());
        doAnswer(invocation -> Mono.defer(() -> {
            now.set(2000);
            return Mono.just(invocation.getArgument(1, RiskDecision.class));
        })).when(rate).evaluate(anyString(), any());

        var exchange = request(HttpMethod.GET);
        new RedirectController(routes, policies, rate, events, TestConfig.defaults(), advancingClock)
                .redirect("Ab", exchange).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertNull(exchange.getResponse().getHeaders().getLocation());
        assertTrue(exchange.getResponse().getCookies().isEmpty());
        verify(rate, times(1)).evaluate(eq("1:5"), any());
        verify(events, never()).click(any());
        verify(events, times(1)).result(any());
    }

    @Test
    void canonicalBlockedIpDoesNotLeakIntoTheNextRequest() {
        allowed();
        String blockedHash = new RiskHash(TestConfig.defaults().riskHashSalt())
                .hash("1", "198.51.100.1");
        when(policies.resolve("1", 5))
                .thenReturn(Mono.just(new PolicySnapshot("1:5", 1, 1000, null, 2000,
                        PolicyState.KNOWN_RESTRICTED, false, true, "UTC", List.of(),
                        Set.of(blockedHash), null)));
        var controller = controller();
        var blocked = request(HttpMethod.GET).mutate()
                .request(builder -> builder.headers(headers ->
                        headers.set("X-Forwarded-For", "::ffff:198.51.100.1"))).build();
        controller.redirect("Ab", blocked).block();
        assertEquals(HttpStatus.FORBIDDEN, blocked.getResponse().getStatusCode());
        assertNull(blocked.getResponse().getHeaders().getLocation());
        verify(events, never()).click(any());

        var allowed = request(HttpMethod.GET).mutate()
                .request(builder -> builder.headers(headers ->
                        headers.set("X-Forwarded-For", "198.51.100.2"))).build();
        controller.redirect("Ab", allowed).block();
        assertEquals(HttpStatus.FOUND, allowed.getResponse().getStatusCode());
        verify(events, times(1)).click(any());
        verify(events, times(2)).result(any());
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
