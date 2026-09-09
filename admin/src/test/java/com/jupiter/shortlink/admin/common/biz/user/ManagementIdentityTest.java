package com.jupiter.shortlink.admin.common.biz.user;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import feign.RequestTemplate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class ManagementIdentityTest {
    final String token = "integration-only-service-token-32-bytes";

    @AfterEach
    void cleanup() {
        UserContext.removeUser();
    }

    @Test
    void forgedUsernameWithoutServiceAuthenticationCannotReachDatabase() throws Exception {
        var verifier = mock(TrustedManagementIdentity.class);
        var request = new MockHttpServletRequest("GET", "/api/short-link/admin/v1/group");
        request.addHeader("username", "alice");
        request.addHeader("x-shortlink-tenant-id", "101");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new UserTransmitFilter(verifier, token).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(verifier);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void currentAuthorityFailureIsUnavailableAndCannotLeakThreadIdentity() throws Exception {
        var verifier = mock(TrustedManagementIdentity.class);
        when(verifier.verify("101", "alice", "1"))
                .thenThrow(new IllegalStateException("database offline"));
        var request = trusted();
        var response = new MockHttpServletResponse();
        UserContext.setUser(new UserInfoDTO("other", "other", null, 1L));
        new UserTransmitFilter(verifier, token).doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void identityIsRemovedEvenWhenHandlerFails() throws Exception {
        var verifier = mock(TrustedManagementIdentity.class);
        when(verifier.verify("101", "alice", "1"))
                .thenReturn(new UserInfoDTO("101", "alice", null, 1L));
        assertThatThrownBy(
                        () ->
                                new UserTransmitFilter(verifier, token)
                                        .doFilter(
                                                trusted(),
                                                new MockHttpServletResponse(),
                                                (r, s) -> {
                                                    assertThat(UserContext.getUserId())
                                                            .isEqualTo("101");
                                                    throw new jakarta.servlet.ServletException(
                                                            "fixture");
                                                }))
                .isInstanceOf(jakarta.servlet.ServletException.class);
        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void forwardingReplacesStaleHeadersWithTheAuthenticatedPrincipal() {
        var template = new RequestTemplate();
        template.header("x-shortlink-tenant-id", "999");
        template.header("x-shortlink-auth-version", "999");
        UserContext.setUser(new UserInfoDTO("101", "alice", null, 2L));
        new ManagementFeignIdentity(token).apply(template);
        assertThat(template.headers().get("x-shortlink-tenant-id")).containsExactly("101");
        assertThat(template.headers().get("x-shortlink-auth-version")).containsExactly("2");
    }

    MockHttpServletRequest trusted() {
        var r = new MockHttpServletRequest("GET", "/api/short-link/admin/v1/group");
        r.addHeader("X-Internal-Token", token);
        r.addHeader("x-shortlink-tenant-id", "101");
        r.addHeader("x-shortlink-username", "alice");
        r.addHeader("x-shortlink-auth-version", "1");
        return r;
    }
}
