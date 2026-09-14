package com.jupiter.shortlink.redirect.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.contract.ClickEventV1;
import com.jupiter.shortlink.contract.EventIdentity;
import com.jupiter.shortlink.contract.GatewayRequestEventV1;
import com.jupiter.shortlink.redirect.TestConfig;
import com.jupiter.shortlink.redirect.cache.RouteResolution;
import com.jupiter.shortlink.redirect.cache.RouteResolver;
import com.jupiter.shortlink.redirect.config.RedirectProperties;
import com.jupiter.shortlink.redirect.event.RequestEventPublisher;
import com.jupiter.shortlink.redirect.risk.PolicyResolver;
import com.jupiter.shortlink.redirect.risk.RedisRiskRateLimiter;
import com.jupiter.shortlink.redirect.route.RouteInfo;
import com.jupiter.shortlink.risk.PolicySnapshot;
import com.jupiter.shortlink.risk.PolicyState;
import com.jupiter.shortlink.risk.RiskDecision;

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.filter.reactive.ServerHttpObservationFilter;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Same HTTP fixtures exercise both mappings, WebFilters and the shared business handler. */
class RedirectRoutingParityTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sampledTimingAndCorrelationSurviveGetHeadAndDeferredBusinessErrors(boolean functional) {
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
            try (var f = new Fixture(functional, true)) {
                String requestId = "0123456789abcdef0123456789abcdef";
                f.request(method, "/Ab")
                        .header("X-Request-ID", requestId)
                        .exchange()
                        .expectStatus()
                        .isFound()
                        .expectHeader()
                        .valueEquals("X-Request-ID", requestId)
                        .expectHeader()
                        .value(
                                "X-Shortlink-Handler-Nanos",
                                value -> {
                                    assertTrue(value.matches("[0-9]+"));
                                    assertTrue(Long.parseLong(value) >= 0);
                                });
                verify(f.events).result(argThat(event -> requestId.equals(event.requestId())));
                if (method == HttpMethod.GET)
                    verify(f.events).click(argThat(event -> requestId.equals(event.requestId())));
                else verify(f.events, never()).click(any());
            }
        }
        try (var f = new Fixture(functional, true)) {
            String requestId = "abcdef0123456789abcdef0123456789";
            when(f.routes.resolve("s.example", "Ab"))
                    .thenReturn(
                            Mono.defer(() -> Mono.error(new IllegalStateException("unavailable"))));
            f.request(HttpMethod.GET, "/Ab")
                    .header("X-Request-ID", requestId)
                    .exchange()
                    .expectStatus()
                    .isEqualTo(503)
                    .expectHeader()
                    .valueEquals("X-Request-ID", requestId)
                    .expectHeader()
                    .valueMatches("X-Shortlink-Handler-Nanos", "[0-9]+");
            verify(f.events)
                    .result(
                            argThat(
                                    event ->
                                            requestId.equals(event.requestId())
                                                    && event.status() == 503));
            verify(f.events, never()).click(any());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disabledTimingPreservesCorrelationWithoutAFilterBean(boolean functional) {
        try (var f = new Fixture(functional, false)) {
            assertTrue(f.context.getBeansOfType(RedirectRequestTimingFilter.class).isEmpty());
            String requestId = "0123456789abcdef0123456789abcdef";
            f.request(HttpMethod.HEAD, "/Ab")
                    .header("X-Request-ID", requestId)
                    .exchange()
                    .expectStatus()
                    .isFound()
                    .expectHeader()
                    .valueEquals("X-Request-ID", requestId)
                    .expectHeader()
                    .doesNotExist("X-Shortlink-Handler-Nanos");
        }
    }

    @Test
    void missingPropertyKeepsOnlyTheAnnotatedEntryPoint() {
        try (var f = new Fixture(null)) {
            assertEquals(1, f.context.getBeansOfType(RedirectAnnotatedRoute.class).size());
            assertTrue(f.context.getBeansOfType(RouterFunction.class).isEmpty());
            f.request(HttpMethod.HEAD, "/Ab").exchange().expectStatus().isFound();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exclusiveMappingPreservesGetHeadCookiesAndRealObservationTags(boolean functional) {
        try (var f = new Fixture(functional)) {
            assertEquals(
                    functional ? 0 : 1,
                    f.context.getBeansOfType(RedirectAnnotatedRoute.class).size());
            assertEquals(functional ? 1 : 0, f.context.getBeansOfType(RouterFunction.class).size());
            var get =
                    f.request(HttpMethod.GET, "/Ab")
                            .exchange()
                            .expectStatus()
                            .isFound()
                            .expectHeader()
                            .location("https://target.example/path")
                            .expectHeader()
                            .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store, private")
                            .expectBody()
                            .isEmpty();
            var cookie = get.getResponseCookies().getFirst("sl_uv");
            assertNotNull(cookie);
            assertTrue(cookie.getValue().matches("[a-f0-9]{32}"));
            assertTrue(cookie.isHttpOnly());
            assertTrue(cookie.isSecure());
            assertEquals("Lax", cookie.getSameSite());
            assertEquals("/", cookie.getPath());
            assertEquals(Duration.ofDays(30), cookie.getMaxAge());
            var head =
                    f.request(HttpMethod.HEAD, "/Ab")
                            .exchange()
                            .expectStatus()
                            .isFound()
                            .expectHeader()
                            .location("https://target.example/path")
                            .expectBody()
                            .isEmpty();
            assertTrue(head.getResponseCookies().isEmpty());
            var clicks = ArgumentCaptor.forClass(ClickEventV1.class);
            var results = ArgumentCaptor.forClass(GatewayRequestEventV1.class);
            verify(f.events).click(clicks.capture());
            verify(f.events, times(2)).result(results.capture());
            assertEquals(cookie.getValue(), clicks.getValue().uvId());
            assertEquals("198.51.100.1", clicks.getValue().clientIp());
            assertEquals(clicks.getValue().requestId(), results.getAllValues().get(0).requestId());
            assertNotEquals(
                    results.getAllValues().get(0).requestId(),
                    results.getAllValues().get(1).requestId());
            assertTrue(
                    EventIdentity.valid(
                            clicks.getValue().eventId(), clicks.getValue().occurredAt()));
            for (var event : results.getAllValues())
                assertTrue(EventIdentity.valid(event.decisionId(), event.occurredAt()));
            await(
                    () ->
                            f.meters
                                            .find("http.server.requests")
                                            .tags(
                                                    "method",
                                                    "GET",
                                                    "status",
                                                    "302",
                                                    "uri",
                                                    "/{shortUri}")
                                            .timer()
                                    != null);
            assertEquals(
                    1,
                    f.meters
                            .get("http.server.requests")
                            .tags("method", "GET", "status", "302", "uri", "/{shortUri}")
                            .timer()
                            .count());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existingVisitorIsReusedAndHttpReplacementCookieIsNotSecure(boolean functional) {
        try (var f = new Fixture(functional)) {
            String visitor = "0123456789abcdef0123456789abcdef";
            f.request(HttpMethod.GET, "/Ab")
                    .cookie("sl_uv", visitor)
                    .exchange()
                    .expectStatus()
                    .isFound()
                    .expectCookie()
                    .doesNotExist("sl_uv");
            verify(f.events).click(argThat(e -> visitor.equals(e.uvId())));
            var response =
                    f.request(HttpMethod.GET, "/Ab")
                            .cookie("sl_uv", "invalid")
                            .headers(h -> h.set("X-Forwarded-Proto", "http"))
                            .exchange()
                            .expectStatus()
                            .isFound()
                            .expectBody()
                            .isEmpty();
            assertFalse(response.getResponseCookies().getFirst("sl_uv").isSecure());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidSingleSegmentAndUnsafeMethodKeepOriginalResponses(boolean functional) {
        try (var f = new Fixture(functional)) {
            for (String path :
                    List.of("/bad-name", "/" + "a".repeat(33), "/%41b", "/Ab;v=1", "/%2F")) {
                f.request(HttpMethod.GET, path).exchange().expectStatus().isNotFound();
            }
            f.request(HttpMethod.POST, "/Ab").exchange().expectStatus().isEqualTo(405);
            verifyNoInteractions(f.routes, f.policies, f.rates);
            verify(f.events, never()).click(any());
            verify(f.events, times(5)).result(argThat(e -> e.status() == 404));
            verify(f.events)
                    .result(
                            argThat(
                                    e ->
                                            e.status() == 405
                                                    && "METHOD_NOT_ALLOWED".equals(e.reason())));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void hostAndProxyValidationStillRunBeforeAuthorityReads(boolean functional) {
        try (var f = new Fixture(functional)) {
            f.network.peer = "192.0.2.9";
            f.request(HttpMethod.GET, "/Ab")
                    .headers(h -> h.set("X-Forwarded-For", "127.0.0.1"))
                    .exchange()
                    .expectStatus()
                    .isForbidden();
            f.network.peer = "127.0.0.1";
            f.request(HttpMethod.GET, "/Ab")
                    .headers(h -> h.set(HttpHeaders.HOST, "unknown.example"))
                    .exchange()
                    .expectStatus()
                    .isNotFound();
            f.request(HttpMethod.GET, "/Ab")
                    .headers(h -> h.set("X-Forwarded-Proto", "ftp"))
                    .exchange()
                    .expectStatus()
                    .isBadRequest();
            f.request(HttpMethod.GET, "/Ab")
                    .headers(h -> h.set("X-Forwarded-For", "bad-ip"))
                    .exchange()
                    .expectStatus()
                    .isBadRequest();
            verifyNoInteractions(f.routes, f.policies, f.rates);
            verify(f.events, never()).click(any());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lifecycle404BodyAndSecurityHeadersAreUnchanged(boolean functional) {
        try (var f = new Fixture(functional)) {
            f.request(HttpMethod.GET, "/Ab").exchange().expectStatus().isFound();
            clearInvocations(f.events);
            when(f.routes.resolve("s.example", "Ab"))
                    .thenReturn(Mono.just(f.route("DELETED", null)));
            var get =
                    f.request(HttpMethod.GET, "/Ab")
                            .exchange()
                            .expectStatus()
                            .isNotFound()
                            .expectHeader()
                            .doesNotExist(HttpHeaders.LOCATION)
                            .expectHeader()
                            .valueEquals(
                                    "Content-Security-Policy",
                                    "default-src 'none'; frame-ancestors 'none'")
                            .expectHeader()
                            .valueEquals("X-Content-Type-Options", "nosniff")
                            .expectHeader()
                            .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store, private")
                            .expectBody()
                            .returnResult();
            assertTrue(
                    new String(get.getResponseBody(), StandardCharsets.UTF_8).contains("短链接不可用"));
            f.request(HttpMethod.HEAD, "/Ab")
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody()
                    .isEmpty();
            f.request(HttpMethod.GET, "/Ab")
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody()
                    .isEmpty();
            verify(f.events, never()).click(any());
            verify(f.events, times(3)).result(argThat(e -> e.status() == 404));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void policyDenyAndGlobalQuotaDecisionsAreNotBypassed(boolean functional) {
        try (var f = new Fixture(functional)) {
            when(f.policies.resolve("1", 5)).thenReturn(Mono.just(f.policy(true, 2000)));
            f.request(HttpMethod.GET, "/Ab").exchange().expectStatus().isForbidden();
            verify(f.events).result(argThat(e -> "LINK_DISABLED".equals(e.reason())));
            when(f.policies.resolve("1", 5)).thenReturn(Mono.just(f.policy(false, 2000)));
            when(f.rates.evaluate(eq("1:5"), any()))
                    .thenReturn(Mono.just(new RiskDecision(429, "RATE_LIMITED", 1, null)));
            f.request(HttpMethod.GET, "/Ab").exchange().expectStatus().isEqualTo(429);
            verify(f.events, never()).click(any());
            verify(f.events).result(argThat(e -> e.status() == 429));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void authorityFailureAndFinalRouteProofExpiryStayFailClosed(boolean functional) {
        try (var f = new Fixture(functional)) {
            when(f.policies.resolve("1", 5))
                    .thenReturn(Mono.error(new IllegalStateException("test unavailable")));
            f.request(HttpMethod.GET, "/Ab").exchange().expectStatus().isEqualTo(503);
            verify(f.events).result(argThat(e -> "AUTHORITY_UNAVAILABLE".equals(e.reason())));
            when(f.policies.resolve("1", 5)).thenReturn(Mono.just(f.policy(false, 3000)));
            when(f.rates.evaluate(eq("1:5"), any()))
                    .thenAnswer(
                            i ->
                                    Mono.fromSupplier(
                                            () -> {
                                                f.now.set(2000);
                                                return i.getArgument(1);
                                            }));
            f.request(HttpMethod.GET, "/Ab")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(503)
                    .expectHeader()
                    .doesNotExist(HttpHeaders.LOCATION);
            verify(f.events).result(argThat(e -> "ROUTE_PROOF_EXPIRED".equals(e.reason())));
            verify(f.events, never()).click(any());
            verify(f.rates).evaluate(eq("1:5"), any());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void finalPolicyTransitionIsRecheckedAfterQuota(boolean functional) {
        try (var f = new Fixture(functional)) {
            when(f.policies.resolve("1", 5)).thenReturn(Mono.just(f.policy(false, 1600)));
            when(f.rates.evaluate(eq("1:5"), any()))
                    .thenAnswer(
                            i ->
                                    Mono.fromSupplier(
                                            () -> {
                                                f.now.set(1600);
                                                return i.getArgument(1);
                                            }));
            f.request(HttpMethod.GET, "/Ab")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(503)
                    .expectHeader()
                    .doesNotExist(HttpHeaders.LOCATION);
            verify(f.events).result(argThat(e -> "POLICY_UNKNOWN".equals(e.reason())));
            verify(f.events, never()).click(any());
            verify(f.rates).evaluate(eq("1:5"), any());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void applicationFiltersAndInternalControllerRemainInTheDispatchChain(boolean functional) {
        try (var f = new Fixture(functional)) {
            f.network.block = true;
            f.request(HttpMethod.GET, "/Ab")
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectHeader()
                    .valueEquals("X-Test-Filter", "rejected");
            verifyNoInteractions(f.routes, f.policies, f.rates, f.events);
            f.network.block = false;
            when(f.events.quality()).thenReturn(Map.of("probe", true));
            f.request(HttpMethod.GET, "/internal/v1/events/quality")
                    .exchange()
                    .expectStatus()
                    .isForbidden();
            f.request(HttpMethod.GET, "/internal/v1/events/quality")
                    .header("X-Internal-Token", TestConfig.defaults().internalToken())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .json("{\"probe\":true}");
            for (String path :
                    List.of("/internal/other", "/actuator/unmapped", "/Ab/extra", "/Ab/")) {
                f.request(HttpMethod.GET, path).exchange().expectStatus().isNotFound();
            }
            verify(f.events, never()).click(any());
            verify(f.events, never()).result(any());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void frameworkOptionsAndCorsPreflightDoNotBecomeRedirectEvents(boolean functional) {
        try (var f = new Fixture(functional)) {
            var options =
                    f.request(HttpMethod.OPTIONS, "/Ab")
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectBody()
                            .isEmpty();
            assertEquals(
                    Set.of(
                            HttpMethod.GET,
                            HttpMethod.HEAD,
                            HttpMethod.POST,
                            HttpMethod.PUT,
                            HttpMethod.PATCH,
                            HttpMethod.DELETE,
                            HttpMethod.OPTIONS),
                    options.getResponseHeaders().getAllow());
            f.request(HttpMethod.OPTIONS, "/Ab")
                    .header(HttpHeaders.ORIGIN, "https://foreign.example")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                    .exchange()
                    .expectStatus()
                    .isForbidden();
            verifyNoInteractions(f.routes, f.policies, f.rates, f.events);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    static class WebConfiguration {}

    private static final class Fixture implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final RouteResolver routes = mock(RouteResolver.class);
        final PolicyResolver policies = mock(PolicyResolver.class);
        final RedisRiskRateLimiter rates = mock(RedisRiskRateLimiter.class);
        final RequestEventPublisher events = mock(RequestEventPublisher.class);
        final AtomicLong now = new AtomicLong(1500);
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final NetworkFixtureFilter network = new NetworkFixtureFilter();
        final WebTestClient client;

        Fixture(Boolean functional) {
            this(functional, null);
        }

        Fixture(Boolean functional, Boolean timing) {
            if (functional != null)
                context.getEnvironment()
                        .getPropertySources()
                        .addFirst(
                                new MapPropertySource(
                                        "routing-test",
                                        Map.of(
                                                "shortlink.redirect.functional-routing-enabled",
                                                functional)));
            if (timing != null)
                context.getEnvironment()
                        .getPropertySources()
                        .addFirst(
                                new MapPropertySource(
                                        "timing-test",
                                        Map.of(
                                                "shortlink.redirect.request-timing-enabled",
                                                timing)));
            lenient()
                    .doAnswer(
                            call ->
                                    routes.resolve(call.getArgument(0), call.getArgument(1))
                                            .map(RouteResolution::route))
                    .when(routes)
                    .resolveGuarded(anyString(), anyString());
            Clock clock = mock(Clock.class);
            when(clock.millis()).thenAnswer(i -> now.get());
            when(routes.resolve("s.example", "Ab"))
                    .thenReturn(Mono.just(route("ACTIVE", "https://target.example/path")));
            when(policies.resolve("1", 5)).thenReturn(Mono.just(policy(false, 2000)));
            when(rates.evaluate(anyString(), any())).thenAnswer(i -> Mono.just(i.getArgument(1)));
            var observation = ObservationRegistry.create();
            observation
                    .observationConfig()
                    .observationHandler(new DefaultMeterObservationHandler(meters));
            context.registerBean(RouteResolver.class, () -> routes);
            context.registerBean(PolicyResolver.class, () -> policies);
            context.registerBean(RedisRiskRateLimiter.class, () -> rates);
            context.registerBean(RequestEventPublisher.class, () -> events);
            context.registerBean(RedirectProperties.class, TestConfig::defaults);
            context.registerBean(Clock.class, () -> clock);
            context.registerBean(NetworkFixtureFilter.class, () -> network);
            context.registerBean(
                    ServerHttpObservationFilter.class,
                    () -> new ServerHttpObservationFilter(observation));
            context.register(
                    WebConfiguration.class,
                    RedirectController.class,
                    RedirectAnnotatedRoute.class,
                    RedirectFunctionalRouting.class,
                    EventQualityController.class,
                    RedirectRequestTimingFilter.class);
            context.refresh();
            client =
                    WebTestClient.bindToApplicationContext(context)
                            .configureClient()
                            .responseTimeout(Duration.ofSeconds(3))
                            .build();
        }

        WebTestClient.RequestBodySpec request(HttpMethod method, String rawPath) {
            return client.method(method)
                    .uri(URI.create("http://s.example" + rawPath))
                    .header(HttpHeaders.HOST, "s.example")
                    .header("X-Forwarded-Proto", "https")
                    .header("X-Forwarded-For", "198.51.100.1");
        }

        RouteInfo route(String status, String target) {
            return new RouteInfo(
                    "s.example", "Ab", 5, "1", "g", target, status, null, 1, 1, 1000, 2000, 1);
        }

        PolicySnapshot policy(boolean disabled, long until) {
            return new PolicySnapshot(
                    "1:5",
                    1,
                    1000,
                    until == 1600 ? 1600L : null,
                    until,
                    disabled ? PolicyState.KNOWN_RESTRICTED : PolicyState.KNOWN_ALLOWED,
                    disabled,
                    true,
                    "UTC",
                    List.of(),
                    Set.of(),
                    null);
        }

        public void close() {
            context.close();
            meters.close();
        }
    }

    /**
     * Mock connector cannot choose a socket peer; the test filter supplies only that transport
     * fact.
     */
    private static final class NetworkFixtureFilter implements WebFilter, Ordered {
        volatile String peer = "127.0.0.1";
        volatile boolean block;

        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            if (block) {
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                exchange.getResponse().getHeaders().set("X-Test-Filter", "rejected");
                return exchange.getResponse().setComplete();
            }
            var request =
                    new ServerHttpRequestDecorator(exchange.getRequest()) {
                        @Override
                        public InetSocketAddress getRemoteAddress() {
                            return new InetSocketAddress(peer, 1234);
                        }
                    };
            return chain.filter(exchange.mutate().request(request).build());
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        reactor.core.publisher.Flux.interval(Duration.ofMillis(2))
                .filter(i -> condition.getAsBoolean())
                .next()
                .block(Duration.ofSeconds(2));
    }
}
