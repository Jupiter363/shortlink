package com.jupiter.shortlink.admin.common.biz.user;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.admin.account.AccountSession;
import com.jupiter.shortlink.admin.account.AccountSessionStore;
import com.jupiter.shortlink.admin.controller.UserController;
import com.jupiter.shortlink.admin.service.UserService;
import feign.RequestTemplate;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.*;
import java.util.Collections;
import java.util.List;

class ManagementIdentityTest {
    final String token = "integration-only-session-32-bytes";
    final TrustedManagementIdentity identities = mock(TrustedManagementIdentity.class);
    final AccountSessionStore sessions = mock(AccountSessionStore.class);
    UserTransmitFilter filter;

    @BeforeEach void setup() {
        var config = properties();
        filter = new UserTransmitFilter(identities, sessions, config, 8102);
        when(sessions.find("alice", token)).thenReturn(new AccountSession(101, "alice", 1, Long.MAX_VALUE));
        when(identities.verify("101", "alice", "1")).thenReturn(new UserInfoDTO("101", "alice", null, 1L));
    }
    @AfterEach void cleanup() { UserContext.removeUser(); }
    static AdminIngressProperties properties() {
        var p = new AdminIngressProperties();
        p.setAllowedHosts(List.of("admin.example"));
        p.setTrustedProxyCidrs(List.of("127.0.0.0/8"));
        return p;
    }
    MockHttpServletRequest trusted() {
        var r = new MockHttpServletRequest("GET", "/api/short-link/admin/v1/group");
        r.setRemoteAddr("127.0.0.1"); r.setLocalPort(8002);
        r.addHeader("Host", "admin.example"); r.addHeader("username", "alice"); r.addHeader("token", token);
        r.addHeader("X-Forwarded-For", "203.0.113.7, 127.0.0.2");
        r.addHeader("X-Forwarded-Proto", "https");
        return r;
    }
    void rejected(MockHttpServletRequest request, int status) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (r,s) -> { throw new AssertionError("Rejected request reached handler"); });
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(UserContext.getUserId()).isNull();
    }
    @Test void oldGatewayInternalTokenAndForgedIdentityCannotAuthenticate() throws Exception {
        var r = trusted(); r.removeHeader("username"); r.removeHeader("token");
        r.addHeader("X-Internal-Token", "old-service-secret-32-bytes");
        r.addHeader("x-shortlink-tenant-id", "101"); r.addHeader("x-shortlink-username", "alice");
        r.addHeader("x-shortlink-auth-version", "1");
        rejected(r, 401); verifyNoInteractions(sessions, identities);
    }
    @Test void onlySessionAuthorityEntersContextAndAllCallerIdentityHeadersAreHidden() throws Exception {
        var r = trusted(); r.addHeader("x-shortlink-tenant-id", "999");
        r.addHeader("x-agent-userid", "999"); r.addHeader("X-Internal-Token", "forged");
        r.addHeader("Authorization", "forged"); r.addHeader("X-Real-IP", "spoof");
        filter.doFilter(r, new MockHttpServletResponse(), (raw,s) -> {
            var actual = (HttpServletRequest)raw;
            assertThat(UserContext.getUserId()).isEqualTo("101");
            for (String h : List.of("username","token","x-shortlink-tenant-id","x-agent-userid","X-Internal-Token","Authorization","X-Real-IP")) {
                assertThat(actual.getHeader(h)).isNull();
                assertThat(Collections.list(actual.getHeaders(h))).isEmpty();
                assertThat(Collections.list(actual.getHeaderNames())).noneMatch(name -> name.equalsIgnoreCase(h));
            }
            assertThat(actual.getHeader("X-Forwarded-For")).isEqualTo("203.0.113.7");
        });
        verify(sessions).find("alice", token); verify(identities).verify("101", "alice", "1");
        assertThat(UserContext.getUserId()).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"username","token","Host","X-Forwarded-For","X-Forwarded-Proto","x-shortlink-tenant-id","x-agent-userid"})
    void duplicateIdentityOrContextIsRejectedBeforeRedis(String name) throws Exception {
        var r=trusted(); r.addHeader(name,"one"); r.addHeader(name,"two");
        rejected(r,400); verifyNoInteractions(sessions, identities);
    }
    @ParameterizedTest @ValueSource(strings={"bad,token-with-enough-bytes","token with spaces and enough bytes","tiny"})
    void malformedTokenFailsClosed(String tokenValue) throws Exception {
        var r=trusted();r.removeHeader("token");r.addHeader("token",tokenValue);
        rejected(r,401);verifyNoInteractions(sessions, identities);
    }
    @Test void actualPeerCannotBeSpoofedWithForwardedHeaders() throws Exception {
        var r=trusted();r.setRemoteAddr("198.51.100.6"); rejected(r,403);
        verifyNoInteractions(sessions, identities);
    }
    @Test void missingConfigurationFailsClosed() throws Exception {
        filter=new UserTransmitFilter(identities,sessions,new AdminIngressProperties(),8102);
        rejected(trusted(),503);verifyNoInteractions(sessions,identities);
    }
    @ParameterizedTest @ValueSource(strings={"/abc123456","/api/short-link/admin/v1//group","/api/short-link/admin/v1/%2egroup","/api/short-link/admin/v1/group;param=x","/internal/short-link-admin/v1/agent-tools/../groups"})
    void rawPathBoundaryRejectsAmbiguousOrUnroutedPaths(String path) throws Exception {
        var r=trusted();r.setRequestURI(path);rejected(r,path.equals("/abc123456")?404:400);
        verifyNoInteractions(sessions,identities);
    }
    @Test void wrongAndMalformedHostCannotReachSession() throws Exception {
        var r=trusted();r.removeHeader("Host");r.addHeader("Host","other.example");rejected(r,404);
        r.removeHeader("Host");r.addHeader("Host","bad,host");rejected(r,400);
        verifyNoInteractions(sessions,identities);
    }
    @Test void expiredSessionOrRevokedDatabaseVersionCannotEnterHandler() throws Exception {
        when(sessions.find("alice",token)).thenReturn(new AccountSession(101,"alice",1,1));
        rejected(trusted(),401);verifyNoInteractions(identities);
        when(sessions.find("alice",token)).thenReturn(new AccountSession(101,"alice",1,Long.MAX_VALUE));
        when(identities.verify("101","alice","1")).thenThrow(new IllegalArgumentException("revoked"));
        rejected(trusted(),401);
    }
    @Test void redisAndAuthorityFailuresAre503AndClearThreadIdentity() throws Exception {
        when(sessions.find("alice",token)).thenThrow(new IllegalStateException("Redis offline"));
        UserContext.setUser(new UserInfoDTO("other","other",null,1L));rejected(trusted(),503);
        doReturn(new AccountSession(101,"alice",1,Long.MAX_VALUE)).when(sessions).find("alice",token);
        when(identities.verify("101","alice","1")).thenThrow(new IllegalStateException("database offline"));
        rejected(trusted(),503);
    }
    @Test void handlerFailureIsNotMistakenForAuthenticationFailure() {
        assertThatThrownBy(() -> filter.doFilter(trusted(),new MockHttpServletResponse(),(r,s)-> {
            assertThat(UserContext.getUserId()).isEqualTo("101");throw new IllegalArgumentException("handler");
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("handler");
        assertThat(UserContext.getUserId()).isNull();
    }
    @Test void checkLoginUsesExactlyTheValidatedSessionWithoutSecondRedisOrServiceLookup() throws Exception {
        var r=trusted();r.setRequestURI("/api/short-link/v1/user/check-login");
        r.addParameter("username","alice");r.addParameter("token",token);
        var service=mock(UserService.class);
        filter.doFilter(r,new MockHttpServletResponse(),(raw,s)-> {
            assertThat(new UserController(service).checkLogin("alice",token,(HttpServletRequest)raw).getData()).isTrue();
        });
        verify(sessions,times(1)).find("alice",token);verifyNoInteractions(service);
    }
    @Test void logoutCannotUseDifferentOrDuplicateQueryCredentials() throws Exception {
        var r=trusted();r.setMethod("DELETE");r.setRequestURI("/api/short-link/admin/v1/user/logout");
        r.addParameter("username","bob");r.addParameter("token",token);rejected(r,401);
        r.setParameter("username","alice");r.addParameter("token",token);rejected(r,401);
    }
    @ParameterizedTest @ValueSource(strings={"none","username","token","duplicate"})
    void checkLoginStillRequiresCompleteUniqueQueryCredentials(String supplied) throws Exception {
        var r=trusted();r.setRequestURI("/api/short-link/v1/user/check-login");
        if (supplied.equals("username") || supplied.equals("duplicate")) r.addParameter("username","alice");
        if (supplied.equals("token") || supplied.equals("duplicate")) r.addParameter("token",token);
        if (supplied.equals("duplicate")) r.addParameter("token",token);
        rejected(r,401);
    }
    @Test void anotherVerifiedRouteCannotAuthorizeLogout() throws Exception {
        var service=mock(UserService.class);
        filter.doFilter(trusted(),new MockHttpServletResponse(),(raw,s)-> {
            assertThatThrownBy(() -> new UserController(service).logout((HttpServletRequest)raw))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        });
        verifyNoInteractions(service);
    }
    @Test void logoutRechecksQueryBindingOnAsyncRedispatch() throws Exception {
        var r=trusted();r.setMethod("DELETE");r.setRequestURI("/api/short-link/admin/v1/user/logout");
        filter.doFilter(r,new MockHttpServletResponse(),new MockFilterChain());
        r.setDispatcherType(DispatcherType.ASYNC);r.addParameter("username","bob");r.addParameter("token",token);
        var service=mock(UserService.class);
        filter.doFilter(r,new MockHttpServletResponse(),(raw,s)-> {
            assertThatThrownBy(() -> new UserController(service).logout((HttpServletRequest)raw))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        });
        verifyNoInteractions(service);
    }
    @Test void publicLoginPreservesBodyWhileStillCheckingIngress() throws Exception {
        var r=trusted();r.setMethod("POST");r.setRequestURI("/api/short-link/admin/v1/user/login");
        r.removeHeader("username");r.removeHeader("token");r.setContent("login body".getBytes());
        filter.doFilter(r,new MockHttpServletResponse(),(raw,s)-> {
            assertThat(raw.getInputStream().readAllBytes()).isEqualTo("login body".getBytes());
            assertThat(UserContext.getUserId()).isNull();
        });
        verifyNoInteractions(sessions,identities);r.setRemoteAddr("203.0.113.7");rejected(r,403);
    }
    @Test void healthRequiresSeparateManagementPortAndLoopbackPeer() throws Exception {
        var r=trusted();r.setRequestURI("/actuator/health");rejected(r,403);
        r.setLocalPort(8102);filter.doFilter(r,new MockHttpServletResponse(),new MockFilterChain());
        r.setRemoteAddr("192.0.2.3");rejected(r,403);verifyNoInteractions(sessions,identities);
    }
    @Test void asyncRedispatchRestoresContextWithoutReauthenticationAndRejectsMissingProof() throws Exception {
        var r=trusted();filter.doFilter(r,new MockHttpServletResponse(),new MockFilterChain());
        r.setDispatcherType(DispatcherType.ASYNC);
        filter.doFilter(r,new MockHttpServletResponse(),(raw,s)->assertThat(UserContext.getUserId()).isEqualTo("101"));
        verify(sessions,times(1)).find("alice",token);verify(identities,times(1)).verify("101","alice","1");
        var forged=trusted();forged.setDispatcherType(DispatcherType.ASYNC);rejected(forged,401);
        assertThat(UserContext.getUserId()).isNull();
    }
    @Test void forwardingReplacesStaleHeadersWithAuthenticatedPrincipal() {
        var template=new RequestTemplate();template.header("x-shortlink-tenant-id","999");
        template.header("x-shortlink-auth-version","999");UserContext.setUser(new UserInfoDTO("101","alice",null,2L));
        new ManagementFeignIdentity(token).apply(template);
        assertThat(template.headers().get("x-shortlink-tenant-id")).containsExactly("101");
        assertThat(template.headers().get("x-shortlink-auth-version")).containsExactly("2");
    }
}
