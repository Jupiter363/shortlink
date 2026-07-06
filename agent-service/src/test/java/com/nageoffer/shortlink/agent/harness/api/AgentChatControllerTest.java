package com.nageoffer.shortlink.agent.harness.api;

import com.nageoffer.shortlink.agent.harness.runtime.AgentRunHarness;
import com.nageoffer.shortlink.agent.harness.runtime.AgentRunResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentChatControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AgentRunHarness harness = request -> new AgentRunResult(
                request.sessionId(),
                "trace-test",
                "这是 mock Agent 回复",
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
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
                  "message": "分析最近7天的短链表现"
                }
                """;

        mockMvc.perform(post("/internal/short-link-agent/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sessionId").value("session-1"))
                .andExpect(jsonPath("$.data.traceId").value("trace-test"))
                .andExpect(jsonPath("$.data.answer").value("这是 mock Agent 回复"))
                .andExpect(jsonPath("$.data.cards").isArray())
                .andExpect(jsonPath("$.data.pendingActions").isArray())
                .andExpect(jsonPath("$.data.dataSources").isArray())
                .andExpect(jsonPath("$.data.warnings").isArray());
    }
}
