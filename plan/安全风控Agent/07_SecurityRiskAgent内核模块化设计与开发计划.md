# SecurityRisk Agent 内核模块化设计与开发计划

> **阶段定位：** 已完成第一阶段 SecurityRisk Graph 接入后，本阶段不扩大对外 API，不做自动封禁等写动作，只把风控 Agent 内核拆成可维护、可测试、可扩展的业务模块。
> **执行要求：** 每个可观察行为变更先写失败测试，再实现；完成本阶段后运行 `mvn -pl agent-service test`、敏感信息扫描、`git diff --check`，再提交并推送。

## 1. 背景与问题

当前 `securityriskagent.graph` 已经能完成工具规划、风险卡片、LLM 解释、响应组合和 checkpoint 保存，但 `DefaultSecurityRiskGraphExecutor` 与 `SecurityRiskCardFactory` 承担了过多职责：

```text
DefaultSecurityRiskGraphExecutor：
  Graph 编排
  message 参数解析
  Tool 调用
  LLM prompt 拼装
  响应组合
  checkpoint JSON 组装

SecurityRiskCardFactory：
  风险规则计算
  卡片 map 组装
  IP/user 脱敏
  工具响应递归清洗
  数值/列表/map 解析
```

这会影响后续两个方向：

```text
1. 风控规则继续扩展时，CardFactory 会变成规则大杂烩。
2. Agent Console 展示风险证据时，脱敏、安全输出、卡片结构需要稳定合同。
```

## 2. 本阶段目标

```text
1. 将通用安全清洗能力抽到 safety 层，成为 Graph、Prompt、Checkpoint、Response 共用出口。
2. 将确定性风险规则抽到 rule 层，新增规则时不需要改大工厂。
3. 将风险过程对象抽到 model 层，减少 Map 在内部流动。
4. 将 LLM prompt 组装抽到 prompt 层，固定不泄露原始 IP/user 的 prompt 合同。
5. 将 Graph 节点逻辑逐步抽到 node 层，保留 Spring AI Alibaba Graph 的编排能力。
6. 保持 `/internal/short-link-agent/v1/chat`、`agentType=security-risk`、`AgentRunResult` 对外兼容。
```

## 3. 目标包结构

```text
agent-service/src/main/java/com/nageoffer/shortlink/agent/securityriskagent/
  graph/
    SecurityRiskGraphExecutor.java
    SecurityRiskGraphRequest.java
    DefaultSecurityRiskGraphExecutor.java

  node/
    RiskIntakeNode.java
    RiskToolPlanningNode.java
    RiskScoringNode.java
    RiskLlmExplanationNode.java
    RiskResponseComposeNode.java

  rule/
    RiskRule.java
    TopIpConcentrationRule.java
    HighRepeatVisitRule.java
    HourBurstRule.java
    SecurityRiskRuleEngine.java
    SecurityRiskCardFactory.java

  model/
    RiskAnalysisContext.java
    RiskFeatureSnapshot.java
    RiskSignal.java
    RiskEvidence.java
    RiskRecommendation.java
    RiskToolInvocation.java

  prompt/
    SecurityRiskPromptBuilder.java

  safety/
    SecurityRiskSanitizer.java
```

## 4. 分层职责

| 层 | 职责 | 不做什么 |
|---|---|---|
| `graph` | Spring AI Alibaba `StateGraph` 编排、checkpoint 生命周期、对外 `AgentRunResult` | 不承载具体规则细节 |
| `node` | 单个 Graph node 的输入输出转换 | 不直接拼复杂 prompt，不保存 checkpoint |
| `rule` | 确定性风险规则、阈值、风险信号生成 | 不调用 LLM，不调用 HTTP 工具 |
| `model` | 风险分析内部强类型对象 | 不暴露为 controller DTO |
| `prompt` | LLM system/user prompt 拼装 | 不接收未脱敏对象 |
| `safety` | 递归脱敏、文本脱敏、响应/checkpoint 安全清洗 | 不做业务风险判断 |

## 5. 风险信号合同

内部规则先输出 `RiskSignal`，再由 response 组合层转成现有 card map，保证外部响应不破坏。

```text
RiskSignal
  type: risk_signal
  title: Security risk signal
  severity: warning
  riskScore: int
  riskLevel: high | medium | low
  category: traffic | time | profile | records
  reasonCode: top_ip_concentration | high_repeat_visits | hour_burst | ...
  signal: ip_concentration | repeat_visit | peak_hour | ...
  sourceTool: get_group_stats | get_short_link_stats | ...
  arguments: sanitized map
  metrics: sanitized map
  thresholds: sanitized map
  evidence: sanitized map
  recommendedActions: list
```

外部 cards 仍保持第一阶段结构：

```json
{
  "type": "risk_signal",
  "title": "Security risk signal",
  "severity": "warning",
  "riskScore": 78,
  "riskLevel": "high",
  "summary": {
    "category": "traffic",
    "reasonCode": "top_ip_concentration",
    "signal": "ip_concentration"
  },
  "metrics": {},
  "thresholds": {},
  "evidence": {},
  "recommendedActions": []
}
```

## 6. 安全清洗合同

`SecurityRiskSanitizer` 是风控 Agent 内唯一的敏感信息出口：

```text
sanitizeText(text)：
  IPv4：192.168.1.10 -> 192.168.*.*
  user/username/uid/visitor/account：user=abc -> user=***
  中文用户标识：用户:张三 -> 用户:***

sanitizeObject(value)：
  Map key=ip：保留 key，value 脱敏
  Map key=user：删除该键
  动态 Map key：key 本身如果包含 IP、user、token、password、JDBC URL，也必须脱敏
  List：递归清洗
  String：执行 sanitizeText
  Number/Boolean/null：原样保留
```

要求：

```text
1. LLM prompt 只能接收 sanitizeObject 后的工具上下文和卡片上下文。
2. AgentRunResult 的 toolCalls、cards、dataSources 必须是 sanitizeObject 后结果。
3. checkpointJson 必须是 sanitizeObject 后结果。
4. Tool 异常 message 必须先 sanitizeText 再进入响应、prompt、checkpoint。
```

## 7. 规则引擎第一批规则

| 类 | reasonCode | 输入 | 触发阈值 | 风险等级 |
|---|---|---|---|---|
| `TopIpConcentrationRule` | `top_ip_concentration` | pv、topIpStats | pv >= 50 且 topIpShare >= 0.3 | high |
| `HighRepeatVisitRule` | `high_repeat_visits` | pv、uv | pv >= 50 且 pv/uv >= 5 | medium |
| `HourBurstRule` | `hour_burst` | hourStats | totalPv >= 50 且 peakHourShare >= 0.6 | medium |

规则执行约束：

```text
1. 只处理成功的 stats 类工具结果：get_group_stats、get_short_link_stats。
2. 规则返回 0..N 个 RiskSignal。
3. 单个规则异常不能导致整个 Graph 失败，异常转 warning 并脱敏。
4. 规则输出证据只能包含脱敏后的信息。
```

## 8. Graph 节点拆分计划

第一轮拆分保持行为不变，节点名仍沿用：

```text
intake
risk_tool_planning
risk_scoring
llm_explanation
response_compose
checkpoint_save
```

对应类：

```text
RiskIntakeNode：
  初始化 graphName、graphVersion、visitedNodes。

RiskToolPlanningNode：
  提取 gid/fullShortUrl/startDate/endDate/current/size。
  调用 AgentToolRegistry。
  输出 toolExecutions/toolWarnings。

RiskScoringNode：
  调用 SecurityRiskRuleEngine。
  输出 riskCards。

RiskLlmExplanationNode：
  调用 SecurityRiskPromptBuilder。
  调用 LlmChatClient。
  输出 answer/llmDataSource/warnings。

RiskResponseComposeNode：
  生成 cards、pendingActions、toolCalls、dataSources。
```

## 9. TDD 任务清单

### Task 1：抽取安全清洗层

**文件：**

```text
Create: securityriskagent/safety/SecurityRiskSanitizer.java
Create: securityriskagent/safety/SecurityRiskSanitizerTest.java
Modify: securityriskagent/rule/SecurityRiskCardFactory.java
Modify: securityriskagent/graph/DefaultSecurityRiskGraphExecutor.java
```

- [x] 写失败测试覆盖递归 Map/List 脱敏、inline user/ip 脱敏、中文用户标识脱敏。
- [x] 实现 `SecurityRiskSanitizer`。
- [x] 将 Graph/CardFactory 内脱敏调用切换到 sanitizer。
- [x] 跑 sanitizer、CardFactory、Graph focused tests。

### Task 2：抽取规则模型与规则引擎

**文件：**

```text
Create: securityriskagent/model/RiskSignal.java
Create: securityriskagent/model/RiskEvidence.java
Create: securityriskagent/model/RiskRecommendation.java
Create: securityriskagent/rule/RiskRule.java
Create: securityriskagent/rule/TopIpConcentrationRule.java
Create: securityriskagent/rule/HighRepeatVisitRule.java
Create: securityriskagent/rule/HourBurstRule.java
Create: securityriskagent/rule/SecurityRiskRuleEngine.java
Create: securityriskagent/rule/SecurityRiskRuleEngineTest.java
Create: securityriskagent/rule/SecurityRiskCardFactory.java
```

- [x] 写失败测试覆盖三条规则生成 `RiskSignal`。
- [x] 实现规则类和规则引擎。
- [x] CardFactory 只负责 `RiskSignal -> card map` 适配。
- [x] 保持现有 cards 外部结构不变。

### Task 3：抽取 Prompt Builder

**文件：**

```text
Create: securityriskagent/prompt/SecurityRiskPromptBuilder.java
Create: securityriskagent/prompt/SecurityRiskPromptBuilderTest.java
Modify: securityriskagent/graph/DefaultSecurityRiskGraphExecutor.java
```

- [x] 写失败测试证明 prompt 不包含原始 IP/user。
- [x] 实现 system prompt 与 user prompt 统一构建。
- [x] Graph 中移除散落的 prompt 拼装。

### Task 4：抽取 Graph Node 类

**文件：**

```text
Create: securityriskagent/node/RiskIntakeNode.java
Create: securityriskagent/node/RiskToolPlanningNode.java
Create: securityriskagent/node/RiskScoringNode.java
Create: securityriskagent/node/RiskLlmExplanationNode.java
Create: securityriskagent/node/RiskResponseComposeNode.java
Modify: securityriskagent/graph/DefaultSecurityRiskGraphExecutor.java
```

- [x] 先写 Graph focused regression test，锁住 visitedNodes、toolCalls、cards、pendingActions、dataSources。
- [x] 逐个节点迁移，迁移后跑 focused tests。
- [x] GraphExecutor 只保留编排、trace、checkpoint、fallback。

### Task 5：全量验证与文档回填

**文件：**

```text
Modify: plan/安全风控Agent/05_验收清单.md
Modify: plan/安全风控Agent/00_计划文档索引.md
```

- [x] 运行 `mvn -pl agent-service test`。
- [x] 运行敏感信息扫描。
- [x] 运行 `git diff --check`。
- [x] 更新验收清单。
- [x] commit + push。

## 10. 非目标

```text
不新增 MCP。
不新增自动封禁/删除/冻结。
不改变 admin/project internal tool API。
不改变 Agent Console 尚未落地的前端合同。
不要求本阶段启动完整 docker 编排。
```

## 11. 验收标准

```text
1. securityriskagent 包下具备 graph/node/rule/model/prompt/safety 分层。
2. 现有 security-risk 请求响应结构不变。
3. 现有风控测试全部通过，并新增 sanitizer、rule、prompt focused tests。
4. LLM prompt、AgentRunResult、checkpoint 均不含原始 IP/user。
5. `mvn -pl agent-service test` 通过。
6. 敏感信息扫描无真实 API key/internal token 命中。
```
