package com.nageoffer.shortlink.agent.harness.api;

import com.nageoffer.shortlink.agent.harness.runtime.AgentRunHarness;
import com.nageoffer.shortlink.agent.harness.runtime.AgentRunRequest;
import com.nageoffer.shortlink.agent.harness.runtime.AgentRunResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentChatControllerTest {

    private MockMvc mockMvc;

    private AtomicReference<AgentRunRequest> capturedRequest;

    @BeforeEach
    void setUp() {
        capturedRequest = new AtomicReference<>();
        AgentRunHarness harness = request -> {
            capturedRequest.set(request);
            return new AgentRunResult(
                    request.sessionId(),
                    "trace-test",
                    "mock-agent-answer",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(Map.of(
                            "traceId", "trace-test",
                            "nodeName", "intake",
                            "status", "success",
                            "timing", Map.of("durationMs", 1L)
                    )),
                    List.of()
            );
        };
        mockMvc = MockMvcBuilders
                .standaloneSetup(new HealthController(), new AgentChatController(harness))
                .build();
    }

    @Test
    void healthReturnsOkStatus() throws Exception {
        mockMvc.perform(get("/internal/short-link-agent/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("OK"))
                .andExpect(jsonPath("$.data.service").value("short-link-agent"));
    }

    @Test
    void chatReturnsStructuredAgentResult() throws Exception {
        String requestBody = """
                {
                  "sessionId": "session-1",
                  "username": "zhangsan",
                  "message": "analyze recent short link performance"
                }
                """;

        mockMvc.perform(post("/internal/short-link-agent/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sessionId").value("session-1"))
                .andExpect(jsonPath("$.data.traceId").value("trace-test"))
                .andExpect(jsonPath("$.data.answer").value("mock-agent-answer"))
                .andExpect(jsonPath("$.data.cards").isArray())
                .andExpect(jsonPath("$.data.pendingActions").isArray())
                .andExpect(jsonPath("$.data.toolCalls").isArray())
                .andExpect(jsonPath("$.data.dataSources").isArray())
                .andExpect(jsonPath("$.data.traceEvents").isArray())
                .andExpect(jsonPath("$.data.traceEvents[0].nodeName").value("intake"))
                .andExpect(jsonPath("$.data.warnings").isArray());
    }

    @Test
    void chatUsesTrustedUsernameHeaderBeforeBodyUsername() throws Exception {
        String requestBody = """
                {
                  "sessionId": "session-1",
                  "username": "spoofed-user",
                  "message": "analyze campaign"
                }
                """;

        mockMvc.perform(post("/internal/short-link-agent/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Agent-Username", "trusted-user")
                        .content(requestBody))
                .andExpect(status().isOk());

        assertThat(capturedRequest.get().username()).isEqualTo("trusted-user");
    }

    @Test
    void chatFallsBackToBodyUsernameForLocalConsole() throws Exception {
        String requestBody = """
                {
                  "sessionId": "session-1",
                  "username": "agent-console",
                  "message": "analyze campaign"
                }
                """;

        mockMvc.perform(post("/internal/short-link-agent/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk());

        assertThat(capturedRequest.get().username()).isEqualTo("agent-console");
    }

    @Test
    void chatPassesAgentTypeToHarness() throws Exception {
        String requestBody = """
                {
                  "sessionId": "session-1",
                  "username": "agent-console",
                  "agentType": "security-risk",
                  "message": "analyze security risk"
                }
                """;

        mockMvc.perform(post("/internal/short-link-agent/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk());

        assertThat(capturedRequest.get().agentType()).isEqualTo("security-risk");
    }
}
