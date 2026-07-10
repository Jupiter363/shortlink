# Agent 能力增强 Implementation Plan

## Task 1: SecurityRisk 结构化解释

**Files:**

- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/securityriskagent/model/StructuredRiskExplanation.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/securityriskagent/llm/RiskExplanationParser.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/securityriskagent/prompt/SecurityRiskPromptBuilder.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/securityriskagent/node/RiskLlmExplanationNode.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/securityriskagent/node/RiskEventPersistNode.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskprofile/repository/JdbcGroupRiskProfileRepository.java`
- Test: `agent-service/src/test/java/com/nageoffer/shortlink/agent/securityriskagent/llm/RiskExplanationParserTest.java`
- Modify node and Graph tests.

- [ ] 测试 JSON 结构化输出映射到 group 和 targetKey。
- [ ] 测试未知 targetKey 被忽略。
- [ ] 测试解析失败回退到普通 answer。
- [ ] 测试每条 RiskEvent 保存对应 explanation。

## Task 2: Campaign Planner 和缺参追问

**Files:**

- Create package: `agent-service/src/main/java/com/nageoffer/shortlink/agent/campaignanalysisagent/planner`
- Create: `CampaignIntent.java`
- Create: `CampaignPlan.java`
- Create: `CampaignSlotPolicy.java`
- Create: `DeterministicCampaignPlanner.java`
- Create: `LlmCampaignPlanner.java`
- Create: `CampaignPlanValidator.java`
- Test package: `agent-service/src/test/java/com/nageoffer/shortlink/agent/campaignanalysisagent/planner`

- [ ] 测试中文自然语言和显式 key-value。
- [ ] 测试缺 gid/日期返回 clarification slots。
- [ ] 测试 Tool allow-list 和参数 schema。
- [ ] 测试非法分页、日期范围和未知 Tool 被拒绝。
- [ ] 测试确定性规划成功时不调用 LLM Planner。

## Task 3: Campaign 创建动作

**Files:**

- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/campaignanalysisagent/action/CampaignShortLinkActionFactory.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/campaignanalysisagent/action/CampaignShortLinkActionExecutor.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/business/shortlink/ShortLinkBusinessGateway.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/business/shortlink/ShortLinkBusinessHttpGateway.java`
- Modify: `admin/src/main/java/com/nageoffer/shortlink/admin/controller/AgentToolInternalController.java`
- Modify: `admin/src/main/java/com/nageoffer/shortlink/admin/service/AgentToolInternalService.java`
- Test action factory/executor and admin internal controller.

- [ ] 测试完整创建参数生成 pending action。
- [ ] 测试缺参只返回 clarification。
- [ ] 测试确认前不调用 POST。
- [ ] 测试确认后调用单条或批量创建 internal API。
- [ ] 测试相同 idempotencyKey 不重复创建。

## Task 4: Campaign Graph 小步拆分

**Files:**

- Create package: `agent-service/src/main/java/com/nageoffer/shortlink/agent/campaignanalysisagent/node`
- Create: `CampaignIntakeNode.java`
- Create: `CampaignPlanningNode.java`
- Create: `CampaignToolExecutionNode.java`
- Create: `CampaignInsightNode.java`
- Create: `CampaignLlmAnalysisNode.java`
- Create: `CampaignResponseComposeNode.java`
- Modify: `DefaultCampaignAnalysisGraphExecutor.java`
- Modify existing Campaign Graph tests.

- [ ] 每提取一个节点先锁定现有行为测试。
- [ ] 节点提取不改变现有响应字段。
- [ ] Planner 和 pending action 接入后再删除旧关键词规划方法。
- [ ] 运行 agent-service 和 admin 测试。
- [ ] 提交并推送本阶段。

