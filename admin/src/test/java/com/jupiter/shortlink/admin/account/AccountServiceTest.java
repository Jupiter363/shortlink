package com.jupiter.shortlink.admin.account;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.biz.user.UserInfoDTO;
import com.jupiter.shortlink.admin.common.convention.exception.ClientException;
import com.jupiter.shortlink.admin.dao.entity.UserDO;
import com.jupiter.shortlink.admin.dao.mapper.UserMapper;
import com.jupiter.shortlink.admin.dto.req.UserLoginReqDTO;
import com.jupiter.shortlink.admin.dto.req.UserUpdateReqDTO;
import com.jupiter.shortlink.admin.service.impl.UserServiceImpl;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AccountServiceTest {
    private UserMapper mapper;
    private AccountSessionStore sessions;
    private AccountPasswordService passwords;
    private AccountIntentRepository intents;
    private UserServiceImpl service;

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "account-test"),
                UserDO.class);
        mapper = mock(UserMapper.class);
        sessions = mock(AccountSessionStore.class);
        passwords = new AccountPasswordService(2, 10);
        intents = mock(AccountIntentRepository.class);
        service = new UserServiceImpl(passwords, sessions, intents);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @AfterEach
    void clearPrincipal() {
        UserContext.removeUser();
    }

    @Test
    void anotherUsernameCannotBeUsedToReadOrUpdateAccount() {
        UserContext.setUser(new UserInfoDTO("1", "alice", null, 1L));
        UserUpdateReqDTO request = new UserUpdateReqDTO();
        request.setUsername("bob");
        assertThrows(ClientException.class, () -> service.update(request));
        assertThrows(ClientException.class, () -> service.getUserByUsername("bob"));
        verifyNoInteractions(mapper, intents);
    }

    @Test
    void versionRevokedBetweenPasswordCheckAndSessionIssueCannotLogIn() {
        UserDO before = account(1);
        before.setPassword(passwords.encode("password-1"));
        UserDO after = account(2);
        when(mapper.selectOne(any())).thenReturn(before, after);
        when(sessions.issue(1, "alice", 1)).thenReturn("new-token");
        UserLoginReqDTO request = new UserLoginReqDTO();
        request.setUsername("alice");
        request.setPassword("password-1");
        assertThrows(ClientException.class, () -> service.login(request));
        verify(sessions).revoke("alice", "new-token");
    }

    @Test
    void revokedVersionIsRejectedEvenIfRedisCleanupHasNotSucceeded() {
        when(sessions.find("alice", "old-token"))
                .thenReturn(new AccountSession(1, "alice", 1, Long.MAX_VALUE));
        when(mapper.selectOne(any())).thenReturn(account(2));
        assertFalse(service.checkLogin("alice", "old-token"));
    }

    @Test
    void logoutDeletesOnlyPresentedTokenAndIsIdempotent() {
        service.logout("alice", "token-1");
        service.logout("alice", "token-1");
        verify(sessions, times(2)).revoke("alice", "token-1");
        verifyNoMoreInteractions(sessions);
    }

    @Test
    void usernameExistenceAlwaysUsesDatabaseAuthority() {
        when(mapper.selectCount(any())).thenReturn(1L);
        assertTrue(service.hasUsername("alice"));
        verify(mapper).selectCount(any());
    }

    @Test
    void absentTrustedAuthVersionFailsClosed() {
        UserContext.setUser(new UserInfoDTO("1", "alice", null));
        assertThrows(ClientException.class, () -> service.getUserByUsername("alice"));
        verifyNoInteractions(mapper);
    }

    private static UserDO account(long version) {
        UserDO account = new UserDO();
        account.setId(1L);
        account.setUsername("alice");
        account.setAuthVersion(version);
        account.setPassword("hash");
        account.setDisabled(false);
        account.setDelFlag(0);
        return account;
    }
}
