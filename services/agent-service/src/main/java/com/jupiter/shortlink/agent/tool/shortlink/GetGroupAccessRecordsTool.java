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
public class GetGroupAccessRecordsTool extends AbstractShortLinkBusinessTool {

    public GetGroupAccessRecordsTool(ShortLinkBusinessGateway gateway) {
        super(
                gateway,
                "get_group_access_records",
                "Page access records for a short link group in a date range.",
                schema());
    }

    @Tool(
            name = "get_group_access_records",
            description = "Page access records for an owned short link group in a date range.")
    public ToolResult getGroupAccessRecords(
            @ToolParam(
                            description =
                                    "Short link group id; ownership is checked by the admin"
                                        + " gateway.")
                    String gid,
            @ToolParam(description = "Start date, yyyy-MM-dd.") String startDate,
            @ToolParam(description = "End date, yyyy-MM-dd.") String endDate,
            @ToolParam(required = false, description = "Page number, defaults to 1.") Long current,
            @ToolParam(required = false, description = "Page size, defaults to 10.") Long size,
            @ToolParam(
                            required = false,
                            description =
                                    "Frozen snapshotId returned by the first page; required for"
                                        + " continuation.")
                    String snapshotId,
            @ToolParam(
                            required = false,
                            description =
                                    "Opaque nextCursor returned by the previous page; never invent"
                                        + " it.")
                    String cursor,
            org.springframework.ai.chat.model.ToolContext toolContext) {
        return executeFromSpringContext(
                toolContext,
                arguments(
                        "gid", gid,
                        "startDate", startDate,
                        "endDate", endDate,
                        "current", current,
                        "size", size,
                        "snapshotId", snapshotId,
                        "cursor", cursor));
    }

    public ToolResult getGroupAccessRecords(
            String gid,
            String startDate,
            String endDate,
            Long current,
            Long size,
            org.springframework.ai.chat.model.ToolContext context) {
        return getGroupAccessRecords(gid, startDate, endDate, current, size, null, null, context);
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
        if (size == null || size < 1 || size > 500) {
            return invalidPositiveLong("size");
        }
        String snapshot = requiredText(arguments, "snapshotId"),
                cursor = requiredText(arguments, "cursor");
        if (current > 1 && (snapshot == null || cursor == null))
            return ToolResult.failure(
                    "Continuation requires snapshotId and cursor from the prior page");

        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("gid", gid);
        queryParams.put("startDate", startDate);
        queryParams.put("endDate", endDate);
        queryParams.put("current", current);
        queryParams.put("size", size);
        if (snapshot != null) queryParams.put("snapshotId", snapshot);
        if (cursor != null) queryParams.put("cursor", cursor);
        return statistics(
                "/internal/short-link-admin/v1/agent-tools/group/access-records",
                context,
                queryParams,
                "ACCESS_RECORDS");
    }

    private static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("gid", Map.of("type", "string", "description", "Short link group id."));
        properties.put(
                "startDate", Map.of("type", "string", "description", "Start date, yyyy-MM-dd."));
        properties.put("endDate", Map.of("type", "string", "description", "End date, yyyy-MM-dd."));
        properties.put(
                "current", Map.of("type", "integer", "description", "Page number, defaults to 1."));
        properties.put(
                "size", Map.of("type", "integer", "description", "Page size, defaults to 10."));
        properties.put(
                "snapshotId",
                Map.of("type", "string", "description", "Frozen snapshot from the first page."));
        properties.put(
                "cursor",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Opaque continuation cursor from the previous page."));
        return Map.of(
                "type",
                "object",
                "properties",
                properties,
                "required",
                new String[] {"gid", "startDate", "endDate"});
    }
}
