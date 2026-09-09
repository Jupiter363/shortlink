package com.jupiter.shortlink.admin.account;

import com.jupiter.shortlink.admin.common.convention.exception.ClientException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** Password work has a dedicated concurrency budget and never shares redirect executors. */
@Component
public final class AccountPasswordService {
    private final PasswordEncoder encoder;
    private final Semaphore permits;
    private final String dummyHash;

    public AccountPasswordService(
            @Value("${shortlink.account.password-concurrency:4}") int concurrency,
            @Value("${shortlink.account.bcrypt-strength:12}") int strength) {
        if (concurrency < 1 || concurrency > 64 || strength < 10 || strength > 16) {
            throw new IllegalArgumentException("Invalid password work budget");
        }
        encoder =
                new DelegatingPasswordEncoder(
                        "bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(strength)));
        permits = new Semaphore(concurrency);
        dummyHash = encoder.encode("invalid-account-constant-work");
    }

    public String encode(String password) {
        validate(password);
        return budgeted(() -> encoder.encode(password));
    }

    public boolean matches(String raw, String encoded) {
        validate(raw);
        return budgeted(
                () -> {
                    // There is deliberately no plaintext or unmarked legacy-hash fallback.
                    boolean supported = encoded != null && encoded.startsWith("{bcrypt}");
                    try {
                        boolean matches = encoder.matches(raw, supported ? encoded : dummyHash);
                        return supported && matches;
                    } catch (IllegalArgumentException malformed) {
                        return false;
                    }
                });
    }

    public static void validate(String password) {
        if (password == null
                || password.length() < 8
                || password.length() > 72
                || password.indexOf('\0') >= 0
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ClientException("密码需至少8个字符，UTF-8编码不超过72字节且不能包含空字符");
        }
    }

    private <T> T budgeted(Supplier<T> operation) {
        if (!permits.tryAcquire()) {
            throw new ClientException("账号认证繁忙，请稍后重试");
        }
        try {
            return operation.get();
        } finally {
            permits.release();
        }
    }
}
