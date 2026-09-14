package com.jupiter.shortlink.membership;

@FunctionalInterface
public interface RegistrationObserver {
    /** Runs in the registration transaction: failures roll back both registration and its notice. */
    void onRegistered(RegistrationNotice notice);
}
