package com.nageoffer.shortlink.agent.harness.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig
@WebAppConfiguration
@ContextConfiguration(classes = AgentConsoleStaticResourceTest.StaticResourceConfig.class)
class AgentConsoleStaticResourceTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void agentConsoleIndexIsServedWithChatEndpoint() throws Exception {
        mockMvc.perform(get("/agent-console/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Short Link Agent Console")))
                .andExpect(content().string(containsString("/internal/short-link-agent/v1/chat")))
                .andExpect(content().string(containsString("Trace ID")))
                .andExpect(content().string(containsString("Warnings")))
                .andExpect(content().string(containsString("Insight Dashboard")))
                .andExpect(content().string(containsString("id=\"warningsPanel\"")))
                .andExpect(content().string(containsString("id=\"cardsPanel\"")))
                .andExpect(content().string(containsString("id=\"pendingActionsPanel\"")))
                .andExpect(content().string(containsString("id=\"accessRecordsPanel\"")))
                .andExpect(content().string(containsString("id=\"debugDataDetails\"")))
                .andExpect(content().string(containsString("renderCards")))
                .andExpect(content().string(containsString("renderPendingActions")))
                .andExpect(content().string(containsString("renderAccessRecords")))
                .andExpect(content().string(containsString("showLoading")))
                .andExpect(content().string(containsString("Sanitized data")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("rawData.records"))))
                .andExpect(content().string(containsString("Tool Calls")))
                .andExpect(content().string(containsString("Data Sources")));
    }

    @Configuration
    @EnableWebMvc
    static class StaticResourceConfig implements WebMvcConfigurer {

        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/agent-console/**")
                    .addResourceLocations("classpath:/static/agent-console/");
        }
    }
}
