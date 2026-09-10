package com.jupiter.shortlink.redirect.route;

import reactor.core.publisher.Mono;

public interface RouteAuthority {
    Mono<RouteInfo> find(String domain, String shortUri, long generation);
}
