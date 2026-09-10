package com.jupiter.shortlink.redirect.route;

public final class AuthorityUnavailableException extends RuntimeException {
    public AuthorityUnavailableException(String reason) {
        super(reason);
    }
}
