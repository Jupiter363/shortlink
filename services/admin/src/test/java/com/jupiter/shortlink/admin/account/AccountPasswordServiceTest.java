package com.jupiter.shortlink.admin.account;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.fastjson2.JSON;
import com.jupiter.shortlink.admin.common.convention.exception.ClientException;
import com.jupiter.shortlink.admin.dao.entity.UserDO;

import org.junit.jupiter.api.Test;

class AccountPasswordServiceTest {
    private final AccountPasswordService passwords = new AccountPasswordService(2, 10);

    @Test
    void saltsEachHashAndHasNoPlaintextFallback() {
        String first = passwords.encode("correct-password");
        String second = passwords.encode("correct-password");
        assertTrue(first.startsWith("{bcrypt}"));
        assertNotEquals(first, second);
        assertTrue(passwords.matches("correct-password", first));
        assertFalse(passwords.matches("wrong-password", first));
        assertFalse(passwords.matches("correct-password", "correct-password"));
        assertFalse(passwords.matches("correct-password", "{noop}correct-password"));
    }

    @Test
    void rejectsBcryptByteTruncationAndNulEquivalence() {
        assertThrows(ClientException.class, () -> passwords.encode("密".repeat(25)));
        assertThrows(ClientException.class, () -> passwords.matches("x".repeat(73), "{bcrypt}bad"));
        assertThrows(ClientException.class, () -> passwords.encode("correct\0password"));
        assertThrows(ClientException.class, () -> passwords.encode("short"));
    }

    @Test
    void sessionRoundTripContainsOnlyPrincipalVersionAndExpiry() {
        AccountSession original = new AccountSession(8, "account", 3, 123_456L);
        String serialized = JSON.toJSONString(original);
        assertEquals(original, JSON.parseObject(serialized, AccountSession.class));
        assertEquals(4, JSON.parseObject(serialized).size());
        assertFalse(serialized.contains("password"));
        UserDO user = new UserDO();
        user.setPassword("credential-must-not-appear");
        assertFalse(user.toString().contains("credential-must-not-appear"));
    }
}
