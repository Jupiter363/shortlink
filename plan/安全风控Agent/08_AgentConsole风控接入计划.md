# SecurityRisk Agent Console 接入计划

> **阶段定位：** 在不扩大生产入口、不新增外部依赖的前提下，让 `agent-service` 独立 Agent Console 可选择 `security-risk` Agent，支持本地联调第二条 Graph。
> **执行要求：** 先写失败测试锁定 Console 合同，再实现静态页面最小改动；完成后运行聚焦测试、`mvn -pl agent-service test`、敏感信息扫描、`git diff --check`，再提交并推送。

## 1. 背景

后端已经通过 `AgentRunHarness` 支持按 `agentType` 路由：

```text
agentType 为空：默认 campaign-analysis
agentType=campaign-analysis：智能投放与分析 Agent
agentType=security-risk：安全风控 Agent
其他值：拒绝，不回退到默认 Agent
```

但 Agent Console 目前只有 session、username、internal token 和 message 输入，无法在页面选择风控 Agent。用户如果只通过 Console 联调，只能走默认投放分析 Agent。

## 2. 目标

```text
1. Console 增加 Agent 选择控件。
2. 默认值仍为 campaign-analysis。
3. 可选择 security-risk。
4. 选择结果写入 sessionStorage，刷新后保留当前联调对象。
5. chat 请求体携带 agentType。
6. 不改变 admin/Gateway 正式生产入口。
7. 不让 Console 直接调用 admin internal tool API。
```

## 3. 非目标

```text
不新增风控专属页面路由。
不新增自动封禁、删除、冻结等写操作。
不改变 SecurityRisk Graph 响应结构。
不新增 MCP 或远程工具协议。
不要求完整 Docker 编排启动。
```

## 4. 实现方案

### 4.1 页面控件

在 `agent-service/src/main/resources/static/agent-console/index.html` 的 Chat 表单中增加：

```text
id=agentTypeInput
option campaign-analysis
option security-risk
```

### 4.2 前端状态

新增 `shortLinkAgentConsoleAgentType` sessionStorage key：

```text
setupContextInputs：读取上次选择，默认 campaign-analysis
agentType：读取控件值并写入 sessionStorage
persistContextInputs：持久化 agentType
change event：切换 Agent 时立即持久化
```

### 4.3 请求合同

`POST /internal/short-link-agent/v1/chat` 请求体新增：

```json
{
  "sessionId": "console-xxx",
  "agentType": "security-risk",
  "username": "agent-console",
  "message": "analyze gid default security risk"
}
```

## 5. TDD 任务清单

### Task 1：Console 静态资源合同

**文件：**

```text
Modify: agent-service/src/test/java/com/nageoffer/shortlink/agent/harness/api/AgentConsoleStaticResourceTest.java
Modify: agent-service/src/main/resources/static/agent-console/index.html
```

- [x] 写失败测试，要求页面包含 `id="agentTypeInput"`。
- [x] 写失败测试，要求页面包含 `security-risk` 选项。
- [x] 写失败测试，要求 chat 请求体包含 `agentType: agentType()`。
- [x] 写失败测试，要求 Agent 下拉框复用表单控件样式与 focus 样式。
- [x] 运行 `mvn -pl agent-service "-Dtest=AgentConsoleStaticResourceTest" test`，确认失败。
- [x] 实现 Agent 选择控件和请求体字段。
- [x] 实现 `select` 统一表单样式。
- [x] 复跑聚焦测试确认通过。

## 6. 验收标准

```text
1. Agent Console 默认仍选择 campaign-analysis。
2. Agent Console 可切换 security-risk。
3. chat 请求体携带 agentType。
4. internal token 逻辑保持不变。
5. Agent 下拉框与现有表单控件视觉一致。
6. cards、pendingActions、Graph Trace、Sanitized data 继续复用原展示逻辑。
7. 不新增未脱敏 raw response 主视图展示。
8. 聚焦测试和 agent-service 全量测试通过。
9. 敏感信息扫描无真实 API key/internal token 命中。
```
