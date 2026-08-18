package com.jupiter.shortlink.agent.tool.shortlink;

import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class GetShortLinkStatsTool extends AbstractShortLinkBusinessTool {

    public GetShortLinkStatsTool(ShortLinkBusinessGateway gateway) {
        super(
                gateway,
                "get_short_link_stats",
                "Get aggregated stats for one short link in a date range.",
                schema()
        );
    }

    @Tool(
            name = "get_short_link_stats",
            description = "Get aggregated stats for one owned short link in a date range."
    )
    public ToolResult getShortLinkStats(
            @ToolParam(description = "Full short link URL.") String fullShortUrl,
            @ToolParam(description = "Short link group id; ownership is checked by the admin gateway.") String gid,
            @ToolParam(description = "Start date, yyyy-MM-dd.") String startDate,
            @ToolParam(description = "End date, yyyy-MM-dd.") String endDate,
            org.springframework.ai.chat.model.ToolContext toolContext
    ) {
        return executeFromSpringContext(
                toolContext,
                arguments(
                        "fullShortUrl", fullShortUrl,
                        "gid", gid,
                        "startDate", startDate,
                        "endDate", endDate
                )
        );
    }

    @Override
    public ToolResult execute(ToolContext context) {
        Map<String, Object> arguments = context.arguments();
        String fullShortUrl = requiredText(arguments, "fullShortUrl");
        if (fullShortUrl == null) {
            return missing("fullShortUrl");
        }
        String gid = requiredText(arguments, "gid");
        if (gid == null) {
            return missing("gid");
        }
        String startDate = requiredText(arguments, "startDate");
        if (startDate == null) {
            return missing("startDate");
        }
        String endDate = requiredText(arguments, "endDate");
        if (endDate == null) {
            return missing("endDate");
        }

        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("fullShortUrl", fullShortUrl);
        queryParams.put("gid", gid);
        queryParams.put("startDate", startDate);
        queryParams.put("endDate", endDate);
        return get("/internal/short-link-admin/v1/agent-tools/short-link/stats", context, queryParams);
    }

    private static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("fullShortUrl", Map.of("type", "string", "description", "Full short link URL."));
        properties.put("gid", Map.of("type", "string", "description", "Short link group id."));
        properties.put("startDate", Map.of("type", "string", "description", "Start date, yyyy-MM-dd."));
        properties.put("endDate", Map.of("type", "string", "description", "End date, yyyy-MM-dd."));
        return Map.of("type", "object", "properties", properties, "required", new String[]{"fullShortUrl", "gid", "startDate", "endDate"});
    }
}
