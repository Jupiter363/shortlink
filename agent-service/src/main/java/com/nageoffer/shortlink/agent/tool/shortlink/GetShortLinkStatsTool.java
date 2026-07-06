package com.nageoffer.shortlink.agent.tool.shortlink;

import com.nageoffer.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.nageoffer.shortlink.agent.tool.core.ToolContext;
import com.nageoffer.shortlink.agent.tool.core.ToolResult;
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
        return get("/api/short-link/admin/v1/stats", context, queryParams);
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
