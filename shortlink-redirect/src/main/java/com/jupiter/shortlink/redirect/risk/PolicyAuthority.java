package com.jupiter.shortlink.redirect.risk;

import com.jupiter.shortlink.risk.PolicySnapshot;

import reactor.core.publisher.Mono;

public interface PolicyAuthority {
    Mono<PolicySnapshot> read(String tenantId, long linkId);

    Mono<Boolean> ready();
}
