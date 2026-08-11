package com.nageoffer.shortlink.admin.authority.identity;

import com.nageoffer.shortlink.admin.authority.identity.api.AgentIdentityExceptionHandler;
import com.nageoffer.shortlink.admin.authority.identity.api.AgentTokenExchangeController;
import com.nageoffer.shortlink.admin.authority.identity.model.TokenExchangeResponse;
import com.nageoffer.shortlink.admin.authority.identity.service.AgentTokenExchangeService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentIdentityControllerMvcTest {

    @Test
    void tokenExchangeBindsRfc8693FormAndReturnsSnakeCaseContract() throws Exception {
        AgentTokenExchangeService service = mock(AgentTokenExchangeService.class);
        when(service.exchange(
                any(HttpServletRequest.class),
                eq(AgentTokenExchangeService.GRANT_TYPE),
                eq("runtime-token"),
                eq(AgentTokenExchangeService.SUBJECT_TOKEN_TYPE),
                eq("shortlink-authority"),
                eq("capability:stats:read")
        )).thenReturn(new TokenExchangeResponse(
                "authority-token",
                AgentTokenExchangeService.ISSUED_TOKEN_TYPE,
                "Bearer",
                120,
                "capability:stats:read"
        ));
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(post("/internal/short-link-admin/v1/agent-identity/token/exchange")
                        .contentType("application/x-www-form-urlencoded")
                        .param("grant_type", AgentTokenExchangeService.GRANT_TYPE)
                        .param("subject_token", "runtime-token")
                        .param("subject_token_type", AgentTokenExchangeService.SUBJECT_TOKEN_TYPE)
                        .param("audience", "shortlink-authority")
                        .param("scope", "capability:stats:read"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.access_token").value("authority-token"))
                .andExpect(jsonPath("$.issued_token_type").value(
                        AgentTokenExchangeService.ISSUED_TOKEN_TYPE
                ))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(120))
                .andExpect(jsonPath("$.scope").value("capability:stats:read"));
    }

    @Test
    void tokenExchangeReturnsProblemForMissingFormField() throws Exception {
        MockMvc mockMvc = mockMvc(mock(AgentTokenExchangeService.class));

        mockMvc.perform(post("/internal/short-link-admin/v1/agent-identity/token/exchange")
                        .contentType("application/x-www-form-urlencoded")
                        .header("X-Request-ID", "req-1")
                        .param("grant_type", AgentTokenExchangeService.GRANT_TYPE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOKEN_EXCHANGE_INVALID"))
                .andExpect(jsonPath("$.requestId").value("req-1"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    private MockMvc mockMvc(AgentTokenExchangeService service) {
        return MockMvcBuilders
                .standaloneSetup(new AgentTokenExchangeController(service))
                .setControllerAdvice(new AgentIdentityExceptionHandler())
                .build();
    }
}
