package com.jupiter.shortlink.contract.geo;

/** A lookup/configuration failure is retryable infrastructure failure, not malformed event JSON. */
public final class GeoLookupException extends IllegalStateException {
    public GeoLookupException(String safeCode) {
        super(safeCode);
    }
}
