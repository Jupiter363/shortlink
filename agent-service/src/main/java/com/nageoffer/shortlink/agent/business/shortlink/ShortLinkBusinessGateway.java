package com.nageoffer.shortlink.agent.business.shortlink;

import com.nageoffer.shortlink.agent.tool.core.ToolContext;
import com.nageoffer.shortlink.agent.tool.core.ToolResult;

import java.util.Map;

public interface ShortLinkBusinessGateway {

    ToolResult get(String path, ToolContext context, Map<String, Object> queryParams);
}
