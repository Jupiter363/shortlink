package com.jupiter.shortlink.redirect.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/** Routes only the original single-segment path through the existing business handler. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "shortlink.redirect",
        name = "functional-routing-enabled",
        havingValue = "true")
public class RedirectFunctionalRouting {
    @Bean
    RouterFunction<ServerResponse> shortlinkRedirectRoute(RedirectController redirect) {
        // Use Spring's PathPattern predicate so decoding and best-matching-pattern observation
        // remain /{shortUri}. Preserve the annotated endpoint's framework-generated OPTIONS;
        // all other methods enter the existing GET/HEAD/405 decision in the handler.
        return RouterFunctions.route(
                RequestPredicates.path("/{shortUri}"),
                request -> {
                    if (request.method() == HttpMethod.OPTIONS) {
                        return ServerResponse.ok().allow(HttpMethod.GET, HttpMethod.HEAD,
                                HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH,
                                HttpMethod.DELETE, HttpMethod.OPTIONS).build();
                    }
                    return ServerResponse.ok().build((exchange, context) ->
                            redirect.redirect(request.pathVariable("shortUri"), exchange));
                });
    }
}
