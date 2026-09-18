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
 * Exposes authorized read-only analysis tools through Spring AI's method tool callback
 * infrastructure. Keeping the provider as a concrete bean lets graph nodes inject either {@link
 * MethodToolCallbackProvider} or obtain the generated callbacks through {@code getToolCallbacks()}
 * without maintaining a second hand-written tool registry.
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
            GetGroupAccessRecordsTool getGroupAccessRecordsTool,
            org.springframework.beans.factory.ObjectProvider<
                            com.jupiter.shortlink.agent.tool.shortlink.StatisticsQueryJobTools>
                    queryJobs,
            org.springframework.beans.factory.ObjectProvider<
                            com.jupiter.shortlink.agent.tool.shortlink.CampaignStatisticsTools>
                    campaignStatistics,
            org.springframework.beans.factory.ObjectProvider<
                            com.jupiter.shortlink.agent.tool.shortlink.DimensionBreakdownTool>
                    dimensionBreakdowns) {
        var jobTools = queryJobs.getIfAvailable();
        var objects = new java.util.ArrayList<Object>(java.util.List.of(listGroupsTool,
                pageShortLinksTool, getShortLinkStatsTool, getGroupStatsTool, getGroupAccessRecordsTool));
        if (jobTools != null) objects.add(jobTools);
        var statistics = campaignStatistics.getIfAvailable();
        if (statistics != null) objects.add(statistics);
        var dimensions = dimensionBreakdowns.getIfAvailable();
        if (dimensions != null) objects.add(dimensions);
        return MethodToolCallbackProvider.builder().toolObjects(objects.toArray()).build();
    }

    public MethodToolCallbackProvider agentToolCallbackProvider(
            ListGroupsTool listGroupsTool,
            PageShortLinksTool pageShortLinksTool,
            GetShortLinkStatsTool getShortLinkStatsTool,
            GetGroupStatsTool getGroupStatsTool,
            GetGroupAccessRecordsTool getGroupAccessRecordsTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(
                        listGroupsTool,
                        pageShortLinksTool,
                        getShortLinkStatsTool,
                        getGroupStatsTool,
                        getGroupAccessRecordsTool)
                .build();
    }
}
