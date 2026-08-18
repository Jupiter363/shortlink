package com.jupiter.shortlink.agent.migration;

import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.agent.tool.registry.AgentToolCallbackConfiguration;
import com.jupiter.shortlink.agent.tool.shortlink.GetGroupAccessRecordsTool;
import com.jupiter.shortlink.agent.tool.shortlink.GetGroupStatsTool;
import com.jupiter.shortlink.agent.tool.shortlink.GetShortLinkStatsTool;
import com.jupiter.shortlink.agent.tool.shortlink.ListGroupsTool;
import com.jupiter.shortlink.agent.tool.shortlink.PageShortLinksTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolCallbackContractTest {

    private static final Set<String> TOOL_NAMES = Set.of(
            "list_groups",
            "page_short_links",
            "get_short_link_stats",
            "get_group_stats",
            "get_group_access_records"
    );

    @Test
    void methodToolCallbackProviderExposesExactlyTheFiveReadOnlyTools() {
        CapturingGateway gateway = new CapturingGateway();
        MethodToolCallbackProvider provider = provider(gateway);

        ToolCallback[] callbacks = provider.getToolCallbacks();

        assertThat(callbacks)
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrderElementsOf(TOOL_NAMES);
        assertThat(callbacks).hasSize(5);
    }

    @Test
    void modelSchemaContainsArgumentsButNeverTrustedIdentityFields() {
        MethodToolCallbackProvider provider = provider(new CapturingGateway());

        for (ToolCallback callback : provider.getToolCallbacks()) {
            String schema = callback.getToolDefinition().inputSchema();
            assertThat(schema)
                    .doesNotContain("username")
                    .doesNotContain("sessionId")
                    .doesNotContain("toolContext");
        }
    }

    @Test
    void forgedUsernameIsIgnoredAndForeignGidIsReturnedAsToolFailure() {
        CapturingGateway gateway = new CapturingGateway();
        ToolCallback callback = callback(provider(gateway), "get_group_stats");

        String response = callback.call("""
                {
                  "gid": "foreign-gid",
                  "startDate": "2026-08-01",
                  "endDate": "2026-08-07",
                  "username": "mallory",
                  "sessionId": "mallory-session"
                }
                """, new org.springframework.ai.chat.model.ToolContext(Map.of(
                "sessionId", "session-001",
                "username", "alice"
        )));

        assertThat(gateway.context.username()).isEqualTo("alice");
        assertThat(gateway.context.sessionId()).isEqualTo("session-001");
        assertThat(gateway.queryParams).containsEntry("gid", "foreign-gid");
        assertThat(response).contains("success").contains("false").contains("does not belong");
    }

    private MethodToolCallbackProvider provider(CapturingGateway gateway) {
        return new AgentToolCallbackConfiguration().agentToolCallbackProvider(
                new ListGroupsTool(gateway),
                new PageShortLinksTool(gateway),
                new GetShortLinkStatsTool(gateway),
                new GetGroupStatsTool(gateway),
                new GetGroupAccessRecordsTool(gateway)
        );
    }

    private ToolCallback callback(MethodToolCallbackProvider provider, String name) {
        return Arrays.stream(provider.getToolCallbacks())
                .filter(candidate -> name.equals(candidate.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();
    }

    private static class CapturingGateway implements ShortLinkBusinessGateway {

        private ToolContext context;
        private Map<String, Object> queryParams = Map.of();

        @Override
        public ToolResult get(String path, ToolContext context, Map<String, Object> queryParams) {
            this.context = context;
            this.queryParams = new LinkedHashMap<>(queryParams);
            if ("foreign-gid".equals(queryParams.get("gid"))) {
                return ToolResult.failure("gid does not belong to the authenticated user");
            }
            return ToolResult.success(Map.of("path", path));
        }
    }
}
