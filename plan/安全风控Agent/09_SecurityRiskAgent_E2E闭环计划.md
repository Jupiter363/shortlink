# SecurityRisk Agent E2E 闭环计划

> **阶段定位：** 在已有 Graph 单测与 Console agentType 接入之后，补齐 `agentType=security-risk` 从 Chat 入口到 admin internal tool API、LLM stub、MySQL checkpoint 的自动化 E2E 闭环。
> **执行要求：** 先写失败 E2E 测试，再做最小生产修复；完成后运行聚焦测试、`mvn -pl agent-service test`、敏感信息扫描、`git diff --check`，再提交并推送。

## 1. 背景

当前风控 Agent 已具备：

```text
1. AgentRunHarness 支持 agentType=security-risk 路由。
2. SecurityRisk Graph 单元测试覆盖工具调用、风险卡片、脱敏和 checkpoint。
3. Agent Console 可选择 security-risk。
```

但还缺少一条自动化闭环证明：

```text
/internal/short-link-agent/v1/chat
  -> InternalAgentApiFilter
  -> AgentRunHarness
  -> SecurityRisk Graph
  -> admin internal tool API
  -> LLM prompt
  -> MySQL checkpoint
  -> AgentRunResult
```

## 2. 目标

```text
1. E2E 测试明确传入 agentType=security-risk。
2. 默认 campaign-analysis E2E 保持不变。
3. security-risk 请求不能误走 campaign-analysis。
4. 请求必须携带并验证 X-Agent-Internal-Token。
5. 请求必须使用 X-Agent-Username 作为可信用户。
6. 出站 admin internal tool API 必须携带 X-Agent-Username 和 X-Agent-Internal-Token。
7. 风控 Graph 至少调用 get_group_stats 与 get_group_access_records。
8. 响应包含 risk_signal、pendingActions、security-risk-graph dataSource、traceEvents。
9. checkpoint 落 H2 MySQL 模式表，并以 graphName=security-risk-graph 区分。
10. 响应、LLM prompt、checkpoint 不包含原始 IP、user 或前端伪造 username。
```

## 3. 实现方案

### 3.1 E2E 测试

在 `AgentChatE2eTest` 中新增 `chatRoutesSecurityRiskAgentThroughAdminInternalApiAndCheckpointEndToEnd`：

```text
1. 使用 MockRestServiceServer 模拟 admin internal tool API。
2. 使用 H2 MySQL 模式验证 t_agent_graph_checkpoint。
3. 使用 CapturingLlmChatClient 捕获 LLM prompt。
4. 使用 InternalAgentApiFilter 验证 internal token。
5. 使用 DefaultAgentRunHarness 验证 agentType 路由。
```

### 3.2 生产修复

红灯暴露两个风控 checkpoint/trace 一致性问题：

```text
1. checkpoint_save trace event 未携带 checkpointVersion。
2. SecurityRisk checkpoint JSON 未保存 traceEvents。
```

修复方式：

```text
1. 复用 Campaign Analysis Graph 的 metadata traceEvent 模式。
2. checkpoint 保存成功时将 checkpointVersion 写入 trace event。
3. checkpointJson 增加已脱敏 traceEvents。
```

## 4. TDD 任务清单

### Task 1：SecurityRisk Chat E2E

**文件：**

```text
Modify: agent-service/src/test/java/com/nageoffer/shortlink/agent/business/shortlink/AgentChatE2eTest.java
Modify: agent-service/src/main/java/com/nageoffer/shortlink/agent/securityriskagent/graph/DefaultSecurityRiskGraphExecutor.java
```

- [x] 写失败 E2E 测试，覆盖 `agentType=security-risk` 路由。
- [x] 写失败 E2E 测试，覆盖 admin internal tool API 请求头。
- [x] 写失败 E2E 测试，覆盖 risk card、pendingActions、Graph Trace 和 checkpoint。
- [x] 写失败 E2E 测试，覆盖 LLM prompt / response / checkpoint 脱敏。
- [x] 运行 `mvn -pl agent-service "-Dtest=AgentChatE2eTest" test`，确认先失败。
- [x] 修复 checkpoint trace `checkpointVersion`。
- [x] 修复 checkpoint JSON 保存 `traceEvents`。
- [x] 复跑聚焦测试确认通过。

## 5. 验收标准

```text
1. AgentChatE2eTest 包含 campaign-analysis 与 security-risk 两条闭环。
2. security-risk E2E 响应包含 risk_signal 和 review_security_risk pendingAction。
3. security-risk E2E traceEvents 覆盖 intake、risk_tool_planning、risk_scoring、llm_explanation、response_compose、checkpoint_save。
4. checkpoint_save trace event 包含 checkpointVersion。
5. checkpoint 表中 graphName=security-risk-graph。
6. LLM prompt 不包含原始 IP/user。
7. response/checkpoint 不包含原始 IP/user 或前端伪造 username。
8. agent-service 全量测试通过。
9. 敏感信息扫描无真实 API key/internal token 命中。
```
