package com.jupiter.shortlink.admin.account;

/**
 * Implementations must use the same commandId for all retries, including an unknown HTTP
 * acknowledgement.
 */
public interface DefaultGroupCommandClient {
    String initialize(AccountInitialization initialization);
}
