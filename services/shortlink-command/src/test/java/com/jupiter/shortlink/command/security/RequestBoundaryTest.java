package com.jupiter.shortlink.command.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;

import java.nio.charset.StandardCharsets;

class RequestBoundaryTest {
    @Test
    void rejectsUntrustedCallerBeforeReadingOrParsingBody() throws Exception {
        var request = spy(new MockHttpServletRequest("POST", "/api/short-link/v1/create"));
        request.setContent("{broken json".getBytes(StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new InternalServiceFilter(
                        new CommandAuthorization(
                                mock(JdbcTemplate.class),
                                "integration-only-service-token-32-bytes"))
                .doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
        verify(request, never()).getInputStream();
    }

    @Test
    void chunkedBodyCannotBypassByteBudget() throws Exception {
        var request = spy(new MockHttpServletRequest("POST", "/api/short-link/v1/create"));
        request.setContent(new byte[256 * 1024 + 1]);
        doReturn(-1L).when(request).getContentLengthLong();
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new RequestBudgetFilter().doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsCompressedPayloadBeforeAllocation() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/short-link/v1/create");
        request.addHeader("Content-Encoding", "gzip");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new RequestBudgetFilter().doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(415);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void bodyAndConcurrencyPermitSurviveDownstreamFailure() throws Exception {
        var filter = new RequestBudgetFilter();
        for (int i = 0; i < 70; i++) {
            var request = new MockHttpServletRequest("POST", "/api/short-link/v1/create");
            request.setContent("{\"gid\":\"中文\"}".getBytes(StandardCharsets.UTF_8));
            var response = new MockHttpServletResponse();
            assertThatThrownBy(
                            () ->
                                    filter.doFilter(
                                            request,
                                            response,
                                            (in, out) -> {
                                                assertThat(
                                                                new String(
                                                                        in.getInputStream()
                                                                                .readAllBytes(),
                                                                        StandardCharsets.UTF_8))
                                                        .contains("中文");
                                                throw new jakarta.servlet.ServletException(
                                                        "downstream failure");
                                            }))
                    .isInstanceOf(jakarta.servlet.ServletException.class);
            assertThat(response.getStatus()).isNotEqualTo(503);
        }
    }
}
