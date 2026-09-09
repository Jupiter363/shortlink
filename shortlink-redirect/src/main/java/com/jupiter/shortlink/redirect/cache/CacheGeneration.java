package com.jupiter.shortlink.redirect.cache;

import reactor.core.publisher.Mono;

public interface CacheGeneration {
    long current();

    Mono<Long> refresh();
}
