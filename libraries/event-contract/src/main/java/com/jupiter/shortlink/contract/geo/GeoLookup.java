package com.jupiter.shortlink.contract.geo;

/** Deterministic offline lookup; implementations must not include the input IP in errors or output. */
public interface GeoLookup extends AutoCloseable {
    GeoResult lookup(String ip);

    String version();

    @Override
    default void close() {
    }

    static GeoLookup notConfigured() {
        return new GeoLookup() {
            @Override
            public GeoResult lookup(String ip) {
                String status = switch (IpAddressPolicy.parse(ip).disposition()) {
                    case PUBLIC -> "NOT_CONFIGURED";
                    case NON_PUBLIC -> "NON_PUBLIC";
                    case INVALID -> "INVALID_IP";
                };
                return GeoResult.unknown(status, "");
            }

            @Override
            public String version() {
                return "";
            }
        };
    }
}
