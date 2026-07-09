# Agent Console 可视化交互增强实施计划

> **阶段定位：** 承接 `15_AgentConsole本地联调身份与Token计划.md`。本阶段只增强 `agent-service` 独立 Agent Console 的本地联调效率，不改变 Graph 响应 DTO、不新增后端 API、不绕过 admin/Gateway 生产权限边界。

## Goal

让 Console 更适合日常联调和复盘：

```text
选择预设问题 -> 快速填充 message
运行 Agent -> 返回 traceId / dataSources
复制 traceId -> 便于查 checkpoint 和日志
按工具过滤 dataSources -> 快速定位工具执行结果
```

## Scope

### In Scope

```text
Agent Console 增加 campaign-analysis / security-risk 两组预设问题；
预设问题点击后填充 messageInput，并按需切换 agentType；
Insight Dashboard 增加复制 traceId 按钮；
Sanitized data 增加 toolName 过滤输入；
dataSources[type=tool].executions 展示时可按工具名过滤；
补充静态资源测试锁定控件、函数和隐私边界。
```

### Out of Scope

```text
不新增后端接口；
不改变 /internal/short-link-agent/v1/chat 请求或响应结构；
不改变 admin 正式入口和 admin internal tool API；
不引入前端框架或构建链；
不展示未脱敏 rawData.records；
不把 token/key/password 写入代码、文档或 localStorage。
```

## Design

### 1. Preset Prompts

在 Chat 控制区增加 `Preset prompts` 区域。按钮使用 `data-agent-type` 和 `data-prompt` 保存 Agent 类型与消息文本。

```text
Campaign Analysis:
  分析 default 分组最近 7 天表现
  解释 default 分组最近访问异常

Security Risk:
  诊断 default 分组最近 24 小时风险
  检查 fullShortUrl 的访问风险
```

点击按钮时：

```text
agentTypeInput.value = data-agent-type
messageInput.value = data-prompt
persistContextInputs()
messageInput.focus()
```

### 2. TraceId Copy

在 `Trace ID` 文案旁增加 `Copy` 按钮。Console 保留最近一次响应的 `traceId`：

```text
currentTraceId = payload.traceId || ""
```

点击复制时：

```text
navigator.clipboard.writeText(currentTraceId)
dashboardStatus.textContent = "Trace ID copied."
```

若浏览器不支持 Clipboard API，则退化为提示 `Trace ID copy is unavailable.`。

### 3. Tool DataSource Filter

在 `Sanitized data` 的 `Data Sources` 调试块上方增加输入框：

```text
toolDataSourceFilterInput
```

Console 缓存最近一次 payload，并在渲染 dataSources 时调用：

```text
filteredDataSources(payload.dataSources)
```

过滤规则：

```text
只过滤 dataSources 中 type=tool 且 executions 为数组的条目；
按 execution.toolName / name / sourceTool 包含关键词匹配；
非 tool dataSource 原样保留；
关键词为空时展示完整脱敏 dataSources；
过滤只影响 Sanitized data 展示，不影响 cards、pendingActions、traceEvents。
```

## Acceptance

- [x] `/agent-console/index.html` 包含 `presetPromptsPanel` 和 `data-agent-type="security-risk"` 预设按钮。
- [x] Console JS 包含 `applyPresetPrompt` 并能写入 `agentTypeInput` 和 `messageInput`。
- [x] `/agent-console/index.html` 包含 `copyTraceIdButton`。
- [x] Console JS 包含 `copyTraceId`，并使用 `navigator.clipboard.writeText`。
- [x] `/agent-console/index.html` 包含 `toolDataSourceFilterInput`。
- [x] Console JS 包含 `filteredDataSources` 和 `renderFilteredDebugData`。
- [x] 工具过滤只影响 `dataSourcesOutput`，不改变 full response 展示。
- [x] Console 仍不包含 `rawData.records`。
- [x] 静态资源测试覆盖新增控件与函数。
- [x] 不提交真实 token/key/password。

## Verification

```text
mvn -pl agent-service "-Dtest=AgentConsoleStaticResourceTest" test
mvn -pl agent-service test
rg -n "sk-[A-Za-z0-9]{16,}|DEEPSEEK_API_KEY\s*[:=]\s*sk-|AGENT_INTERNAL_TOKEN\s*[:=]\s*[A-Za-z0-9_-]{16,}|X-Agent-Internal-Token\s*[:=]\s*[A-Za-z0-9_-]{16,}" agent-service admin plan .gitignore pom.xml
git diff --check
```
