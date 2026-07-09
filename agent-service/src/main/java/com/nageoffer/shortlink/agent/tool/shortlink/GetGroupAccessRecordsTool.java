package com.nageoffer.shortlink.agent.tool.shortlink;

import com.nageoffer.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.nageoffer.shortlink.agent.harness.tool.ToolContext;
import com.nageoffer.shortlink.agent.harness.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class GetGroupAccessRecordsTool extends AbstractShortLinkBusinessTool {

    public GetGroupAccessRecordsTool(ShortLinkBusinessGateway gateway) {
        super(
                gateway,
                "get_group_access_records",
                "Page access records for a short link group in a date range.",
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
        String startDate = requiredText(arguments, "startDate");
        if (startDate == null) {
            return missing("startDate");
        }
        String endDate = requiredText(arguments, "endDate");
        if (endDate == null) {
            return missing("endDate");
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
        queryParams.put("startDate", startDate);
        queryParams.put("endDate", endDate);
        queryParams.put("current", current);
        queryParams.put("size", size);
        return get("/internal/short-link-admin/v1/agent-tools/group/access-records", context, queryParams);
    }

    private static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("gid", Map.of("type", "string", "description", "Short link group id."));
        properties.put("startDate", Map.of("type", "string", "description", "Start date, yyyy-MM-dd."));
        properties.put("endDate", Map.of("type", "string", "description", "End date, yyyy-MM-dd."));
        properties.put("current", Map.of("type", "integer", "description", "Page number, defaults to 1."));
        properties.put("size", Map.of("type", "integer", "description", "Page size, defaults to 10."));
        return Map.of("type", "object", "properties", properties, "required", new String[]{"gid", "startDate", "endDate"});
    }
}
