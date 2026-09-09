# Agent Console 本地联调身份与 Token 实施计划

> **For agentic workers:** 本计划承接阶段 14 的真实本机闭环。DeepSeek + MySQL checkpoint + admin/project/internal tool API 已经跑通，本阶段解决独立 Agent Console 在本地复现真实用户/gid 分析时的操作缺口。

## Goal

让 `agent-service` 独立 Agent Console 可以用于本地真实联调：

```text
输入调试 session / username / internal token
  -> 调用 /internal/short-link-agent/v1/health
  -> 调用 /internal/short-link-agent/v1/chat
  -> 可复用真实 admin internal tool API 鉴权链路
  -> 不把 token 写入仓库或配置文件
```

## Scope

### In Scope

```text
Agent Console 增加 session 输入；
Agent Console 增加 username 输入，默认 agent-console；
Agent Console 增加 internal token 密码输入；
health/chat 请求统一通过 buildHeaders 注入可选 X-Agent-Internal-Token；
session 持久化到 localStorage，username/token 仅持久化到 sessionStorage；
补充静态资源测试，确保控件、Header 注入逻辑和 Sanitized data 展示仍存在。
```

### Out of Scope

```text
不改变 admin 正式入口；
不改变 /internal/short-link-admin/** tool API；
不把 Agent Console 配成公网入口；
不提交真实 DeepSeek key、MySQL 密码或 internal token；
不在 Console 展示未脱敏 rawData.records。
```

## Design

### 1. Console 调试身份

在 `Chat` 控制区增加三个字段：

```text
Session
Username
Internal token
```

`Session` 用于复用或隔离 Graph checkpoint 会话。`Username` 用于本地 Console 直接调用 agent-service 时传入调试用户上下文。`Internal token` 用于 agent-service 配置了 `AGENT_INTERNAL_TOKEN` 时继续访问内部 health/chat API。

### 2. 存储策略

```text
sessionId:
  localStorage(shortLinkAgentConsoleSessionId)
  允许跨浏览器会话复用

username:
  sessionStorage(shortLinkAgentConsoleUsername)
  只在当前浏览器会话内复用

internalToken:
  sessionStorage(shortLinkAgentConsoleInternalToken)
  只在当前浏览器会话内复用
  输入为空时主动 removeItem
```

该策略避免把 token 写入文件或长期本地存储，同时保留本地调试效率。

### 3. 请求头收口

新增前端函数：

```text
buildHeaders(headers)
```

`health` 和 `chat` 都通过它生成请求头。如果 token 输入为空，不发送 `X-Agent-Internal-Token`；如果 token 存在，则补充该 Header。

## Acceptance

- [x] `/agent-console/index.html` 包含 `sessionInput`、`usernameInput`、`internalTokenInput`。
- [x] Console JS 包含 `buildHeaders`，并能注入 `X-Agent-Internal-Token`。
- [x] Console 使用 `sessionStorage` 保存 username/token。
- [x] Console chat body 不再固定写死 `agent-console`，而是使用输入的 username。
- [x] 静态资源测试先失败再通过，覆盖本阶段新增行为。
- [x] 不提交真实 token/key/password。

## Verification

```text
mvn -pl agent-service "-Dtest=AgentConsoleStaticResourceTest" test
```

已验证结果：

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Next

后续可以继续推进两条线：

```text
1. Console 可视化交互增强：预设问题、复制 traceId、按工具过滤数据源；
2. 生产侧管理端页面接入：通过 admin 正式入口展示 Agent Chat，而不是直接暴露 agent-service internal API。
```
