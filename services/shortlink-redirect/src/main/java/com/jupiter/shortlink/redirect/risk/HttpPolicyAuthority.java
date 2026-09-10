package com.jupiter.shortlink.redirect.risk;

import com.jupiter.shortlink.redirect.config.RedirectProperties;
import com.jupiter.shortlink.risk.PolicySnapshot;

import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

import java.time.Duration;

public final class HttpPolicyAuthority implements PolicyAuthority {
    private final WebClient client;
    private final Duration timeout;

    public HttpPolicyAuthority(WebClient.Builder builder, RedirectProperties properties) {
        client =
                builder.baseUrl(properties.commandBaseUrl())
                        .defaultHeader("X-Internal-Token", properties.internalToken())
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(262144))
                        .build();
        timeout = Duration.ofMillis(properties.requestTimeoutMillis());
    }

    public Mono<PolicySnapshot> read(String tenantId, long linkId) {
        return client.get()
                .uri(
                        "/internal/short-link-command/v1/risk/resources/{tenant}/{link}",
                        tenantId,
                        linkId)
                .retrieve()
                .bodyToMono(PolicySnapshot.class)
                .timeout(timeout);
    }

    public Mono<Boolean> ready() {
        return client.get()
                .uri("/internal/short-link-command/v1/risk/ready")
                .retrieve()
                .bodyToMono(Ready.class)
                .map(Ready::ready)
                .timeout(timeout)
                .onErrorReturn(false);
    }

    record Ready(boolean ready) {}
}
