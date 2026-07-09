package com.nageoffer.shortlink.admin.controller;

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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig
@WebAppConfiguration
@ContextConfiguration(classes = AgentAdminPageStaticResourceTest.StaticResourceConfig.class)
class AgentAdminPageStaticResourceTest {

    private static final Pattern CHAT_BODY_PATTERN = Pattern.compile("body:\\s*JSON\\.stringify\\(\\{(?<body>[\\s\\S]*?)\\}\\s*\\)");

    private static final Pattern OBJECT_KEY_PATTERN = Pattern.compile("(?m)^\\s*([A-Za-z_$][\\w$]*)\\s*:");

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void adminAgentPageIsServedAndUsesFormalAdminApiOnly() throws Exception {
        String page = mockMvc.perform(get("/agent-admin/index.html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(page, containsString("Short Link Agent"));
        assertThat(page, containsString("/api/short-link/admin/v1/agent/chat"));
        assertThat(page, containsString("/api/short-link/admin/v1/agent/health"));
        assertThat(page, containsString("localStorage.getItem(\"token\")"));
        assertThat(page, containsString("localStorage.getItem(\"username\")"));
        assertThat(page, containsString("headers[\"Token\"]"));
        assertThat(page, containsString("headers[\"Username\"]"));
        assertThat(page, containsString("agentTypeInput"));
        assertThat(page, containsString("campaign-analysis"));
        assertThat(page, containsString("security-risk"));
        assertThat(page, containsString("sessionId: sessionId()"));
        assertThat(page, containsString("agentType: agentType()"));
        assertThat(page, containsString("message: message"));
        assertThat(page, containsString("renderCards"));
        assertThat(page, containsString("renderTraceEvents"));
        assertThat(page, containsString("renderDataSources"));
        assertThat(page, not(containsString("username:")));
        assertThat(page, not(containsString("/internal/short-link-agent")));
        assertThat(page, not(containsString("X-Agent-Internal-Token")));
        assertThat(page, not(containsString("rawData.records")));
        assertEquals(List.of("sessionId", "agentType", "message"), extractChatBodyKeys(page));
    }

    private static List<String> extractChatBodyKeys(String page) {
        Matcher bodyMatcher = CHAT_BODY_PATTERN.matcher(page);
        assertThat("chat request body should use JSON.stringify with an object literal", bodyMatcher.find(), is(true));
        Matcher keyMatcher = OBJECT_KEY_PATTERN.matcher(bodyMatcher.group("body"));
        List<String> keys = new ArrayList<>();
        while (keyMatcher.find()) {
            keys.add(keyMatcher.group(1));
        }
        return keys;
    }

    @Configuration
    @EnableWebMvc
    static class StaticResourceConfig implements WebMvcConfigurer {

        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/agent-admin/**")
                    .addResourceLocations("classpath:/static/agent-admin/");
        }
    }
}
