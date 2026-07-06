package com.nageoffer.shortlink.agent.tool.core;

public interface AgentTool {

    ToolDescriptor descriptor();

    ToolResult execute(ToolContext context);
}
