# Admin 管理端 Agent 页面接入实施计划

> **阶段定位：** 承接 `16_AgentConsole可视化交互增强计划.md`。本阶段把 Agent Chat 能力接入生产侧管理端页面源文件，页面只调用 admin 正式入口，不直接访问 `agent-service` internal API，不修改 ignored 的 nginx dist 产物或本地运行配置。

## Goal

提供一个可提交、可测试、可后续嵌入前端构建链的管理端 Agent 页面：

```text
Admin 用户登录态
  -> /agent-admin/index.html
  -> /api/short-link/admin/v1/agent/health
  -> /api/short-link/admin/v1/agent/chat
  -> admin UserContext
  -> agent-service internal chat
```

## Scope

### In Scope

```text
admin 模块新增静态页面 /agent-admin/index.html；
页面从 localStorage 读取现有前端登录态 token / username；
页面请求头使用 Token / Username，与现有 dist-link 前端保持一致；
chat 请求体只包含 sessionId、agentType、message，不包含 username；
页面支持 campaign-analysis / security-risk 切换；
页面展示 answer、cards、pendingActions、traceEvents、dataSources 和 full response；
补充 admin 静态资源契约测试；
更新计划索引和本阶段验收清单。
```

### Out of Scope

```text
不修改 nginx-nageoffer-1.25.4/ ignored 运行目录；
不修改压缩后的 dist-link JS；
不新增 Gateway/Nacos 路由配置；
不新增后端业务 API；
不实现 PendingAction 确认执行；
不保存 token 到新的 localStorage key；
不展示 rawData.records 或未脱敏访问明细。
```

## Design

### 1. Static Page Location

页面放在：

```text
admin/src/main/resources/static/agent-admin/index.html
```

Spring Boot 会按默认静态资源规则服务该页面。本阶段把它作为生产管理端页面源文件，后续若恢复原前端源码或构建链，可按此页面合同迁入正式路由。

### 2. Auth Boundary

页面不接收、不保存新的 internal token。它只复用现有管理端登录态：

```text
localStorage.getItem("token")
localStorage.getItem("username")
```

请求 header：

```text
Token: <login-token>
Username: <login-username>
```

通过 nginx/gateway 访问时，`TokenValidate` 校验登录 token 并向 admin 注入可信 `UserContext`。admin 仍忽略前端 body 中的 username。

### 3. API Contract

Health：

```http
GET /api/short-link/admin/v1/agent/health
```

Chat：

```http
POST /api/short-link/admin/v1/agent/chat
Content-Type: application/json
Token: <login-token>
Username: <login-username>

{
  "sessionId": "admin-agent-session",
  "agentType": "campaign-analysis",
  "message": "分析 default 分组最近 7 天表现"
}
```

关键约束：

```text
不发送 username 字段；
不调用 /internal/short-link-agent/**；
不发送 X-Agent-Internal-Token；
不把 token 展示在页面上。
```

## TDD Tasks

### Task 1: Stage Plan And Index

- [x] 新增本计划。
- [x] 更新 `00_计划文档索引.md`。

### Task 2: Static Resource Contract Test

- [x] 写失败测试，要求 `/agent-admin/index.html` 可服务。
- [x] 写失败测试，要求页面调用 `/api/short-link/admin/v1/agent/chat` 和 `/api/short-link/admin/v1/agent/health`。
- [x] 写失败测试，要求页面从 localStorage 读取 `token` / `username` 并写入 `Token` / `Username` header。
- [x] 写失败测试，要求 chat body 不包含 `username`。
- [x] 写失败测试，要求页面不包含 `/internal/short-link-agent`、`X-Agent-Internal-Token`、`rawData.records`。

### Task 3: Admin Agent Page

- [x] 新增 `/agent-admin/index.html`。
- [x] 实现 health/chat 请求。
- [x] 实现 Agent 类型切换和 session/message 输入。
- [x] 实现 answer/cards/pendingActions/traceEvents/dataSources/full response 展示。
- [x] 页面无 token 输入框，不暴露 internal token。

## Acceptance

- [x] `/agent-admin/index.html` 由 admin 静态资源服务。
- [x] 页面只调用 admin 正式 Agent API。
- [x] 页面复用现有 localStorage `token` / `username` 登录态。
- [x] 页面 chat body 不包含 `username`。
- [x] 页面不包含 `/internal/short-link-agent`。
- [x] 页面不包含 `X-Agent-Internal-Token`。
- [x] 页面不包含 `rawData.records`。
- [x] `AgentAdminPageStaticResourceTest` 通过。
- [x] `mvn -pl admin "-Dtest=AgentAdminPageStaticResourceTest" test` 通过。
- [x] `mvn -pl admin test` 通过。
- [x] 敏感信息扫描无真实 token/key/password。
- [x] 提交前验证项已完成；阶段 commit/push 由本阶段 Git 记录证明。

## Verification

```text
mvn -pl admin "-Dtest=AgentAdminPageStaticResourceTest" test
mvn -pl admin test
rg -n "sk-[A-Za-z0-9]{16,}|DEEPSEEK_API_KEY\s*[:=]\s*sk-|AGENT_INTERNAL_TOKEN\s*[:=]\s*sk-|X-Agent-Internal-Token\s*[:=]\s*[A-Za-z0-9_-]{16,}" admin agent-service plan .gitignore pom.xml
git diff --check
```
