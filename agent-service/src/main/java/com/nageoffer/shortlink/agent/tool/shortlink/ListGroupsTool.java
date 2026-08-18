package com.nageoffer.shortlink.agent.tool.shortlink;

import com.nageoffer.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.nageoffer.shortlink.agent.harness.tool.ToolContext;
import com.nageoffer.shortlink.agent.harness.tool.ToolResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ListGroupsTool extends AbstractShortLinkBusinessTool {

    public ListGroupsTool(ShortLinkBusinessGateway gateway) {
        super(
                gateway,
                "list_groups",
                "List short link groups for the current user.",
                Map.of("type", "object", "properties", Map.of(), "required", new String[]{})
        );
    }

    @Tool(
            name = "list_groups",
            description = "List short link groups owned by the current authenticated user."
    )
    public ToolResult listGroups(org.springframework.ai.chat.model.ToolContext toolContext) {
        return executeFromSpringContext(toolContext, Map.of());
    }

    @Override
    public ToolResult execute(ToolContext context) {
        return get("/internal/short-link-admin/v1/agent-tools/groups", context, Map.of());
    }
}
