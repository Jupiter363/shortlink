package com.jupiter.shortlink.membership;

import com.jupiter.shortlink.risk.HostNormalizer;

/** Receives the exact normalized route authority; scheme-specific port removal happens upstream. */
public record RouteAddress(String domainNorm, String shortUri) {
    public RouteAddress {
        if (domainNorm == null || domainNorm.length() > 253
                || !domainNorm.equals(new HostNormalizer().normalize(domainNorm, null)))
            throw new IllegalArgumentException("Membership domain must already be normalized");
        if (shortUri == null || !shortUri.matches("[A-Za-z0-9]{1,64}"))
            throw new IllegalArgumentException("Invalid membership short code");
    }
}
