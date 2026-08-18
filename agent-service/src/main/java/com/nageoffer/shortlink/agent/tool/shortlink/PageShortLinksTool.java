package com.nageoffer.shortlink.agent.tool.shortlink;

import com.nageoffer.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.nageoffer.shortlink.agent.harness.tool.ToolContext;
import com.nageoffer.shortlink.agent.harness.tool.ToolResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
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

    @Tool(
            name = "page_short_links",
            description = "Page short links in an owned group, optionally ordered by today or total metrics."
    )
    public ToolResult pageShortLinks(
            @ToolParam(description = "Short link group id.") String gid,
            @ToolParam(required = false, description = "Optional sort key: todayPv, todayUv, todayUip, totalPv, totalUv, totalUip.") String orderTag,
            @ToolParam(required = false, description = "Page number, defaults to 1.") Long current,
            @ToolParam(required = false, description = "Page size, defaults to 10.") Long size,
            org.springframework.ai.chat.model.ToolContext toolContext
    ) {
        return executeFromSpringContext(
                toolContext,
                arguments("gid", gid, "orderTag", orderTag, "current", current, "size", size)
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
