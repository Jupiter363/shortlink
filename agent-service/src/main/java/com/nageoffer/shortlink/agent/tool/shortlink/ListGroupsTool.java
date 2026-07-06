package com.nageoffer.shortlink.agent.tool.shortlink;

import com.nageoffer.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.nageoffer.shortlink.agent.tool.core.ToolContext;
import com.nageoffer.shortlink.agent.tool.core.ToolResult;
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

    @Override
    public ToolResult execute(ToolContext context) {
        return get("/api/short-link/admin/v1/group", context, Map.of());
    }
}
