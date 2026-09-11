package com.jupiter.shortlink.admin.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jupiter.shortlink.admin.account.AccountSession;
import com.jupiter.shortlink.admin.account.AccountSessionStore;
import com.jupiter.shortlink.admin.common.biz.user.AdminIngressProperties;
import com.jupiter.shortlink.admin.common.biz.user.TrustedManagementIdentity;
import com.jupiter.shortlink.admin.common.biz.user.UserTransmitFilter;
import com.jupiter.shortlink.admin.common.convention.web.GlobalExceptionHandler;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AdminDependencyDiagnosticsTest {
    final Logger logger = (Logger) LoggerFactory.getLogger(AdminDependencyDiagnostics.class);
    final ListAppender<ILoggingEvent> records = new ListAppender<>();
    final String secret = "must-not-log-token-body-query";

    @BeforeEach void capture() {
        for (String name : List.of("FEIGN", "IDENTITY")) {
            var gate = (AdminDependencyDiagnostics.Gate) org.springframework.test.util.ReflectionTestUtils
                    .getField(AdminDependencyDiagnostics.class, name);
            gate.last.set(Long.MIN_VALUE); gate.suppressed.set(0);
        }
        records.start(); logger.addAppender(records);
    }
    @AfterEach void cleanup() { logger.detachAppender(records); records.stop(); }

    Request request() {
        return Request.create(Request.HttpMethod.POST, "http://untrusted.invalid/" + secret,
                Map.of("X-Internal-Token", List.of(secret)), secret.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8, null);
    }

    void assertSanitized(String expected) {
        assertThat(records.list).hasSize(1);
        assertThat(records.list.get(0).getFormattedMessage()).contains(expected)
                .doesNotContain(secret, "untrusted.invalid", "X-Internal-Token");
        assertThat(records.list.get(0).getThrowableProxy()).isNull();
    }

    @Test void transportFailurePreserves503AndLogsOnlyExceptionTypes() {
        var failure = new RetryableException(-1, secret, Request.HttpMethod.POST,
                new SocketException(secret), null, request());
        var result = new GlobalExceptionHandler().remoteException(failure);
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getBody().getCode()).isEqualTo("REMOTE_503");
        assertSanitized("stage=feign exception=RetryableException cause=SocketException upstream_status=-1 mapped_status=503");
    }

    @Test void remoteHttpFailurePreservesExistingStatusAndDoesNotLogPayload() {
        var response = Response.builder().status(409).reason(secret).request(request())
                .headers(Map.of()).body(secret, StandardCharsets.UTF_8).build();
        var result = new GlobalExceptionHandler().remoteException(FeignException.errorStatus(secret, response));
        assertThat(result.getStatusCode().value()).isEqualTo(409);
        assertThat(records.list).isEmpty();
    }

    @Test void identityDatabaseFailureIsDistinguishableWithoutLeakingPrincipal() throws Exception {
        var verifier = mock(TrustedManagementIdentity.class);
        when(verifier.verify("1", secret, "1")).thenThrow(new IllegalStateException(secret));
        var sessions = mock(AccountSessionStore.class);
        String sessionToken = secret + "-session";
        when(sessions.find(secret, sessionToken))
                .thenReturn(new AccountSession(1L, secret, 1L, Long.MAX_VALUE));
        var ingress = new AdminIngressProperties();
        ingress.setAllowedHosts(List.of("admin.example"));
        ingress.setTrustedProxyCidrs(List.of("127.0.0.0/8"));
        var request = new MockHttpServletRequest("POST", "/api/short-link/admin/v1/" + secret);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "admin.example");
        request.addHeader("username", secret);
        request.addHeader("token", sessionToken);
        request.addHeader("X-Internal-Token", secret + "-32bytes");
        request.addHeader("x-shortlink-tenant-id", "9999");
        request.addHeader("x-shortlink-username", "spoof");
        request.addHeader("x-shortlink-auth-version", "999");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new UserTransmitFilter(verifier, sessions, ingress, 8102).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
        verify(sessions).find(secret, sessionToken);
        verify(verifier).verify("1", secret, "1");
        assertSanitized("stage=identity exception=IllegalStateException cause=IllegalStateException mapped_status=503");
    }

    @Test void repeatedFailuresAreBoundedIndependentlyForEachStage() {
        for (int i = 0; i < 100; i++) {
            AdminDependencyDiagnostics.feignFailure(new SocketException(secret), -1, 503);
            AdminDependencyDiagnostics.identityUnavailable(new IllegalStateException(secret));
        }
        assertThat(records.list).hasSize(2);
        assertThat(records.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(secret);
            assertThat(event.getThrowableProxy()).isNull();
        });
        var gate = (AdminDependencyDiagnostics.Gate) org.springframework.test.util.ReflectionTestUtils
                .getField(AdminDependencyDiagnostics.class, "FEIGN");
        assertThat(gate.suppressed.get()).isEqualTo(99);
        gate.last.addAndGet(-java.util.concurrent.TimeUnit.SECONDS.toNanos(11));
        AdminDependencyDiagnostics.feignFailure(new SocketException(secret), -1, 503);
        assertThat(records.list).hasSize(3);
        assertThat(records.list.get(2).getFormattedMessage()).contains("suppressed=99");
        assertThat(gate.suppressed.get()).isZero();
    }
}
