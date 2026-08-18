package com.jupiter.shortlink.agent.business.shortlink;

import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;

import java.util.Map;

public interface ShortLinkBusinessGateway {

    ToolResult get(String path, ToolContext context, Map<String, Object> queryParams);
}
