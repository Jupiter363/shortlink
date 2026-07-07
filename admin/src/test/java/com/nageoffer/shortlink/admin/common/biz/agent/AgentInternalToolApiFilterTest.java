package com.nageoffer.shortlink.admin.common.biz.agent;

import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.config.AgentAdminConfiguration;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AgentInternalToolApiFilterTest {

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    @Test
    void rejectsMissingInternalTokenWhenTokenIsConfigured() throws Exception {
        AgentInternalToolApiFilter filter = filter("internal-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/short-link-admin/v1/agent-tools/groups");
        request.addHeader("X-Agent-Username", "zhangsan");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, chain((req, resp) -> chainCalled.set(true)));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chainCalled).isFalse();
        assertThat(UserContext.getUsername()).isNull();
    }

    @Test
    void rejectsWrongInternalTokenWhenTokenIsConfigured() throws Exception {
        AgentInternalToolApiFilter filter = filter("internal-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/short-link-admin/v1/agent-tools/groups");
        request.addHeader("X-Agent-Internal-Token", "wrong-token");
        request.addHeader("X-Agent-Username", "zhangsan");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, chain((req, resp) -> chainCalled.set(true)));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chainCalled).isFalse();
        assertThat(UserContext.getUsername()).isNull();
    }

    @Test
    void rejectsMissingTrustedUsername() throws Exception {
        AgentInternalToolApiFilter filter = filter("internal-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/short-link-admin/v1/agent-tools/groups");
        request.addHeader("X-Agent-Internal-Token", "internal-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, chain((req, resp) -> chainCalled.set(true)));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(chainCalled).isFalse();
        assertThat(UserContext.getUsername()).isNull();
    }

    @Test
    void blankInternalTokenRejectsRequestsUnlessDevModeIsExplicitlyEnabled() throws Exception {
        AgentInternalToolApiFilter filter = filter("");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/short-link-admin/v1/agent-tools/groups");
        request.addHeader("X-Agent-Username", "zhangsan");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, chain((req, resp) -> chainCalled.set(true)));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chainCalled).isFalse();
        assertThat(UserContext.getUsername()).isNull();
    }

    @Test
    void blankInternalTokenAllowsLocalDevelopmentOnlyWhenDevModeIsExplicitlyEnabled() throws Exception {
        AgentInternalToolApiFilter filter = filter("", true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/short-link-admin/v1/agent-tools/groups");
        request.addHeader("X-Agent-Username", "zhangsan");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, chain((req, resp) -> {
            chainCalled.set(true);
            assertThat(UserContext.getUsername()).isEqualTo("zhangsan");
        }));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chainCalled).isTrue();
        assertThat(UserContext.getUsername()).isNull();
    }

    @Test
    void setsTrustedUserContextForValidRequestAndClearsItAfterwards() throws Exception {
        AgentInternalToolApiFilter filter = filter("internal-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/short-link-admin/v1/agent-tools/groups");
        request.addHeader("X-Agent-Internal-Token", "internal-token");
        request.addHeader("X-Agent-Username", "zhangsan");
        request.addHeader("X-Agent-UserId", "1001");
        request.addHeader("X-Agent-RealName", "Zhang San");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, chain((req, resp) -> {
            chainCalled.set(true);
            assertThat(UserContext.getUsername()).isEqualTo("zhangsan");
            assertThat(UserContext.getUserId()).isEqualTo("1001");
            assertThat(UserContext.getRealName()).isEqualTo("Zhang San");
        }));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chainCalled).isTrue();
        assertThat(UserContext.getUsername()).isNull();
    }

    private AgentInternalToolApiFilter filter(String internalToken) {
        return filter(internalToken, false);
    }

    private AgentInternalToolApiFilter filter(String internalToken, boolean devMode) {
        AgentAdminConfiguration configuration = new AgentAdminConfiguration();
        configuration.setInternalToken(internalToken);
        configuration.setInternalTokenDevMode(devMode);
        return new AgentInternalToolApiFilter(configuration);
    }

    private FilterChain chain(FilterChain filterChain) {
        return filterChain;
    }
}
