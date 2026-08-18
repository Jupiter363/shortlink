package com.jupiter.shortlink.agent.tool.registry;

import com.jupiter.shortlink.agent.tool.shortlink.GetGroupAccessRecordsTool;
import com.jupiter.shortlink.agent.tool.shortlink.GetGroupStatsTool;
import com.jupiter.shortlink.agent.tool.shortlink.GetShortLinkStatsTool;
import com.jupiter.shortlink.agent.tool.shortlink.ListGroupsTool;
import com.jupiter.shortlink.agent.tool.shortlink.PageShortLinksTool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the five read-only short-link tools through Spring AI's method tool
 * callback infrastructure.  Keeping the provider as a concrete bean lets graph
 * nodes inject either {@link MethodToolCallbackProvider} or obtain the generated
 * callbacks through {@code getToolCallbacks()} without maintaining a second
 * hand-written tool registry.
 */
@Configuration(proxyBeanMethods = false)
public class AgentToolCallbackConfiguration {

    public static final String PROVIDER_BEAN_NAME = "agentToolCallbackProvider";

    @Bean(name = PROVIDER_BEAN_NAME)
    public MethodToolCallbackProvider agentToolCallbackProvider(
            ListGroupsTool listGroupsTool,
            PageShortLinksTool pageShortLinksTool,
            GetShortLinkStatsTool getShortLinkStatsTool,
            GetGroupStatsTool getGroupStatsTool,
            GetGroupAccessRecordsTool getGroupAccessRecordsTool
    ) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(
                        listGroupsTool,
                        pageShortLinksTool,
                        getShortLinkStatsTool,
                        getGroupStatsTool,
                        getGroupAccessRecordsTool
                )
                .build();
    }
}
