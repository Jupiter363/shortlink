package com.jupiter.shortlink.analytics.api;

public final class QueryFailure extends RuntimeException {
    public final String code;

    public QueryFailure(String code, String message) {
        super(message);
        this.code = code;
    }
}
