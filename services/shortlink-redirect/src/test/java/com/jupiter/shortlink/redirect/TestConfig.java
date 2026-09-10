package com.jupiter.shortlink.redirect;

import com.jupiter.shortlink.redirect.config.RedirectProperties;

import java.util.List;

public final class TestConfig {
    public static RedirectProperties defaults() {
        return new RedirectProperties(
                "test-instance",
                List.of("s.example"),
                List.of("127.0.0.0/8"),
                "internal-secret",
                "component-test-stable-hash-secret-32-bytes",
                "http://127.0.0.1:18001",
                1000,
                500,
                100,
                100,
                4,
                100,
                2,
                32768,
                16384,
                "127.0.0.1:19092",
                16,
                1000);
    }
}
