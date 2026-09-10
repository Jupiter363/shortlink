package com.jupiter.shortlink.admin.account;

public interface AccountSessionStore {
    String issue(long tenantId, String username, long authVersion);

    AccountSession find(String username, String token);

    void revoke(String username, String token);

    void revokeBefore(String username, long authVersion);
}
