package com.jupiter.shortlink.redirect.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/** Default entry point; the functional route is enabled independently in the same artifact. */
@RestController
@ConditionalOnProperty(
        prefix = "shortlink.redirect",
        name = "functional-routing-enabled",
        havingValue = "false",
        matchIfMissing = true)
public final class RedirectAnnotatedRoute {
    private final RedirectController redirect;

    public RedirectAnnotatedRoute(RedirectController redirect) {
        this.redirect = redirect;
    }

    @RequestMapping("/{shortUri}")
    public Mono<Void> redirect(@PathVariable String shortUri, ServerWebExchange exchange) {
        return redirect.redirect(shortUri, exchange);
    }
}
