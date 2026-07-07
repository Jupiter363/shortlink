package com.nageoffer.shortlink.agent.tool.shortlink;

import com.nageoffer.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.nageoffer.shortlink.agent.tool.core.ToolContext;
import com.nageoffer.shortlink.agent.tool.core.ToolResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class PageShortLinksTool extends AbstractShortLinkBusinessTool {

    public PageShortLinksTool(ShortLinkBusinessGateway gateway) {
        super(
                gateway,
                "page_short_links",
                "Page short links in a group, optionally ordered by today or total metrics.",
                schema()
        );
    }

    @Override
    public ToolResult execute(ToolContext context) {
        Map<String, Object> arguments = context.arguments();
        String gid = requiredText(arguments, "gid");
        if (gid == null) {
            return missing("gid");
        }
        Long current = positiveLong(arguments, "current", 1L);
        if (current == null || current < 1) {
            return invalidPositiveLong("current");
        }
        Long size = positiveLong(arguments, "size", 10L);
        if (size == null || size < 1) {
            return invalidPositiveLong("size");
        }

        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("gid", gid);
        putIfText(queryParams, "orderTag", arguments.get("orderTag"));
        queryParams.put("current", current);
        queryParams.put("size", size);
        return get("/internal/short-link-admin/v1/agent-tools/short-links/page", context, queryParams);
    }

    private static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("gid", Map.of("type", "string", "description", "Short link group id."));
        properties.put("orderTag", Map.of("type", "string", "description", "Optional sort key: todayPv, todayUv, todayUip, totalPv, totalUv, totalUip."));
        properties.put("current", Map.of("type", "integer", "description", "Page number, defaults to 1."));
        properties.put("size", Map.of("type", "integer", "description", "Page size, defaults to 10."));
        return Map.of("type", "object", "properties", properties, "required", new String[]{"gid"});
    }
}
