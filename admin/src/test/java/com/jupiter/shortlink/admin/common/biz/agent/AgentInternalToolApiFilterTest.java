package com.jupiter.shortlink.admin.common.biz.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.config.AgentAdminConfiguration;
import com.jupiter.shortlink.admin.dao.entity.UserDO;
import com.jupiter.shortlink.admin.dao.mapper.UserMapper;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;

import java.util.concurrent.atomic.AtomicBoolean;

class AgentInternalToolApiFilterTest {
    private static final String TOKEN = "test-internal-token-at-least-24-characters";
    private UserMapper accounts;
    private UserDO account;
    private AgentAdminConfiguration config;

    @BeforeEach
    void setUp() {
        accounts = mock(UserMapper.class);
        account = new UserDO();
        account.setId(1001L);
        account.setUsername("zhangsan");
        account.setRealName("DB real name");
        account.setAuthVersion(7L);
        account.setDisabled(false);
        account.setDelFlag(0);
        when(accounts.selectOne(any(Wrapper.class))).thenReturn(account);
        config = new AgentAdminConfiguration();
        config.setInternalToken(TOKEN);
    }

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    private AgentInternalToolApiFilter filter() {
        return new AgentInternalToolApiFilter(config, accounts, "system_agent");
    }

    private MockHttpServletRequest request() {
        var r =
                new MockHttpServletRequest(
                        "GET", "/internal/short-link-admin/v1/agent-tools/groups");
        r.addHeader("X-Agent-Internal-Token", TOKEN);
        r.addHeader("X-Agent-Username", "zhangsan");
        r.addHeader("X-Agent-UserId", "1001");
        r.addHeader("X-Agent-Auth-Version", "7");
        return r;
    }

    private MockHttpServletResponse denied(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        filter().doFilter(request, response, (a, b) -> called.set(true));
        assertThat(called).isFalse();
        assertThat(UserContext.getUsername()).isNull();
        return response;
    }

    @Test
    void missingAndWrongTokenAreRejectedBeforeDatabase() throws Exception {
        var missing = request();
        missing.removeHeader("X-Agent-Internal-Token");
        assertThat(denied(missing).getStatus()).isEqualTo(401);
        var wrong = request();
        wrong.removeHeader("X-Agent-Internal-Token");
        wrong.addHeader("X-Agent-Internal-Token", "wrong");
        assertThat(denied(wrong).getStatus()).isEqualTo(401);
        verifyNoInteractions(accounts);
    }

    @Test
    void absentOrWeakConfiguredSecretFailsClosedEvenWithLegacyDevMode() throws Exception {
        config.setInternalTokenDevMode(true);
        for (String token : new String[] {"", "short"}) {
            config.setInternalToken(token);
            assertThat(denied(request()).getStatus()).isEqualTo(401);
        }
        verifyNoInteractions(accounts);
    }

    @Test
    void missingOrMalformedUsernameIsRejectedBeforeDatabase() throws Exception {
        for (String username : new String[] {"", "bad user", "a"}) {
            var r = request();
            r.removeHeader("X-Agent-Username");
            r.addHeader("X-Agent-Username", username);
            assertThat(denied(r).getStatus()).isEqualTo(400);
        }
        verifyNoInteractions(accounts);
    }

    @Test
    void delegatedUserIdAndCredentialVersionMustBothMatchDatabase() throws Exception {
        var wrongId = request();
        wrongId.removeHeader("X-Agent-UserId");
        wrongId.addHeader("X-Agent-UserId", "999");
        assertThat(denied(wrongId).getStatus()).isEqualTo(401);
        var missingVersion = request();
        missingVersion.removeHeader("X-Agent-Auth-Version");
        assertThat(denied(missingVersion).getStatus()).isEqualTo(401);
        account.setAuthVersion(8L);
        assertThat(denied(request()).getStatus()).isEqualTo(401);
    }

    @Test
    void disabledDeletedOrMissingAccountCannotReachController() throws Exception {
        account.setDisabled(true);
        assertThat(denied(request()).getStatus()).isEqualTo(401);
        account.setDisabled(false);
        account.setDelFlag(1);
        assertThat(denied(request()).getStatus()).isEqualTo(401);
        when(accounts.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThat(denied(request()).getStatus()).isEqualTo(401);
    }

    @Test
    void databaseUnavailableFailsClosed() throws Exception {
        when(accounts.selectOne(any(Wrapper.class)))
                .thenThrow(
                        new org.springframework.dao.DataAccessResourceFailureException(
                                "database unavailable"));
        assertThat(denied(request()).getStatus()).isEqualTo(503);
    }

    @Test
    void trustedContextComesFromDatabaseAndIsAlwaysRemoved() throws Exception {
        var r = request();
        r.addHeader("X-Agent-RealName", "forged");
        var response = new MockHttpServletResponse();
        filter().doFilter(
                        r,
                        response,
                        (a, b) -> {
                            assertThat(UserContext.getUserId()).isEqualTo("1001");
                            assertThat(UserContext.getUsername()).isEqualTo("zhangsan");
                            assertThat(UserContext.getAuthVersion()).isEqualTo(7L);
                            assertThat(UserContext.getRealName()).isEqualTo("DB real name");
                        });
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(UserContext.getUserId()).isNull();
        assertThat(UserContext.getAuthVersion()).isNull();
    }

    @Test
    void exceptionalControllerCannotLeakPrincipalIntoNextRequest() {
        assertThatThrownBy(
                        () ->
                                filter().doFilter(
                                                request(),
                                                new MockHttpServletResponse(),
                                                (a, b) -> {
                                                    throw new ServletException("controller failed");
                                                }))
                .isInstanceOf(ServletException.class);
        assertThat(UserContext.getUsername()).isNull();
        assertThat(UserContext.getAuthVersion()).isNull();
    }

    @Test
    void systemPrincipalMustBeTheConfiguredAccount() throws Exception {
        var outside = request();
        outside.addHeader("X-Agent-Principal-Mode", "SYSTEM");
        assertThat(denied(outside).getStatus()).isEqualTo(403);
        var system = request();
        system.removeHeader("X-Agent-Username");
        system.addHeader("X-Agent-Username", "system_agent");
        system.addHeader("X-Agent-Principal-Mode", "SYSTEM");
        system.removeHeader("X-Agent-UserId");
        system.removeHeader("X-Agent-Auth-Version");
        account.setId(9001L);
        account.setUsername("system_agent");
        account.setAuthVersion(9L);
        filter().doFilter(
                        system,
                        new MockHttpServletResponse(),
                        (a, b) -> {
                            assertThat(UserContext.getUserId()).isEqualTo("9001");
                            assertThat(UserContext.getAuthVersion()).isEqualTo(9L);
                        });
        assertThat(UserContext.getUsername()).isNull();
    }

    @Test
    void contextPathDoesNotBypassInternalAuthentication() throws Exception {
        var r = request();
        r.setContextPath("/management");
        r.setRequestURI("/management/internal/short-link-admin/v1/agent-tools/groups");
        r.removeHeader("X-Agent-Internal-Token");
        assertThat(denied(r).getStatus()).isEqualTo(401);
    }
}
