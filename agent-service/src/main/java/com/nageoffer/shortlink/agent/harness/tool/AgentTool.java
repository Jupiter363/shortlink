package com.nageoffer.shortlink.agent.harness.tool;

public interface AgentTool {

    ToolDescriptor descriptor();

    ToolResult execute(ToolContext context);
}
