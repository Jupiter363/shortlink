package com.jupiter.shortlink.redirect.cache;

import com.jupiter.shortlink.redirect.membership.RouteMembershipGuard;
import com.jupiter.shortlink.redirect.route.RouteInfo;

/** Separates an authoritative route/NOT_FOUND value from a local membership denial. */
public sealed interface RouteResolution {
    record Route(RouteInfo value) implements RouteResolution {
        public Route {
            java.util.Objects.requireNonNull(value);
        }
    }

    record Absent(RouteMembershipGuard.AbsentProof proof, long cacheEpoch)
            implements RouteResolution {
        public Absent {
            java.util.Objects.requireNonNull(proof);
        }
    }

    static RouteResolution route(RouteInfo value) {
        return new Route(value);
    }
}
