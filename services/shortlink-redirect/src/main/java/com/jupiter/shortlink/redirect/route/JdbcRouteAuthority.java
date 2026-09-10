package com.jupiter.shortlink.redirect.route;

import com.jupiter.shortlink.redirect.config.RedirectProperties;

import org.springframework.jdbc.core.JdbcTemplate;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;

public final class JdbcRouteAuthority implements RouteAuthority {
    private final JdbcTemplate jdbc;
    private final Scheduler scheduler;
    private final Clock clock;
    private final long ttl;
    private final Duration timeout;
    private final Semaphore permits;

    public JdbcRouteAuthority(
            JdbcTemplate jdbc, Scheduler scheduler, Clock clock, RedirectProperties properties) {
        this.jdbc = jdbc;
        this.scheduler = scheduler;
        this.clock = clock;
        ttl = properties.authorityTtlMillis();
        timeout = Duration.ofMillis(properties.requestTimeoutMillis());
        permits = new Semaphore(properties.originConcurrency());
    }

    @Override
    public Mono<RouteInfo> find(String domain, String shortUri, long generation) {
        return Mono.defer(
                () -> {
                    return Mono.fromCallable(
                                    () -> {
                                        if (!permits.tryAcquire())
                                            throw new AuthorityUnavailableException("ORIGIN_BUSY");
                                        long checkedAt = clock.millis();
                                        try {
                                            List<RouteInfo> rows =
                                                    jdbc.query(
                                                            "SELECT"
                                                                + " domain_norm,short_uri,link_id,tenant_id,current_gid,origin_url,route_status,expire_at,route_version,ownership_version"
                                                                + " FROM t_link_route WHERE"
                                                                + " domain_norm=? AND short_uri=?"
                                                                + " LIMIT 1",
                                                            (rs, row) -> {
                                                                Timestamp expiry =
                                                                        rs.getTimestamp(
                                                                                "expire_at");
                                                                return new RouteInfo(
                                                                        domain,
                                                                        shortUri,
                                                                        rs.getLong("link_id"),
                                                                        rs.getString("tenant_id"),
                                                                        rs.getString("current_gid"),
                                                                        rs.getString("origin_url"),
                                                                        rs.getString(
                                                                                "route_status"),
                                                                        expiry == null
                                                                                ? null
                                                                                : expiry.toInstant()
                                                                                        .toEpochMilli(),
                                                                        rs.getLong("route_version"),
                                                                        rs.getLong(
                                                                                "ownership_version"),
                                                                        checkedAt,
                                                                        checkedAt + ttl,
                                                                        generation);
                                                            },
                                                            domain,
                                                            shortUri);
                                            return rows.isEmpty()
                                                    ? new RouteInfo(
                                                            domain,
                                                            shortUri,
                                                            0,
                                                            null,
                                                            null,
                                                            null,
                                                            "NOT_FOUND",
                                                            null,
                                                            0,
                                                            0,
                                                            checkedAt,
                                                            checkedAt + ttl,
                                                            generation)
                                                    : rows.get(0);
                                        } finally {
                                            permits.release();
                                        }
                                    })
                            .subscribeOn(scheduler)
                            .timeout(timeout);
                });
    }
}
