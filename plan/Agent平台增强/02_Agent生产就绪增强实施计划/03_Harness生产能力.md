# Harness 生产能力 Implementation Plan

## Task 1: 输入校验与身份边界

**Files:**

- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/harness/api/AgentChatController.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/harness/runtime/AgentRunRequest.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/harness/session/AgentThreadKeyFactory.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/infrastructure/config/AgentProperties.java`
- Modify: `agent-service/src/test/java/com/nageoffer/shortlink/agent/harness/api/AgentChatControllerTest.java`
- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/harness/session/AgentThreadKeyFactoryTest.java`

- [ ] 测试空 session/message/agentType 返回 400。
- [ ] 测试长度和字符集限制。
- [ ] 测试正式模式缺少可信 username 被拒绝。
- [ ] 测试同 session 不同用户生成不同 threadKey。

## Task 2: Graph 原生 Saver 与受控记忆

**Files:**

- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/infrastructure/config/GraphSaverConfiguration.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/harness/checkpoint/AgentConversationMemoryService.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/campaignanalysisagent/graph/DefaultCampaignAnalysisGraphExecutor.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/securityriskagent/graph/DefaultSecurityRiskGraphExecutor.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/infrastructure/persistence/JdbcGraphCheckpointStore.java`
- Test: `agent-service/src/test/java/com/nageoffer/shortlink/agent/harness/checkpoint/GraphNativeCheckpointTest.java`
- Test: `agent-service/src/test/java/com/nageoffer/shortlink/agent/harness/checkpoint/AgentConversationMemoryServiceTest.java`

- [ ] 测试 CompileConfig 注册 Saver。
- [ ] 测试同 threadKey 可以读取状态历史。
- [ ] 测试不同用户不能读取同名 session。
- [ ] 测试只注入脱敏、限长摘要。

## Task 3: 统一脱敏

**Files:**

- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/harness/security/AgentSensitiveDataSanitizer.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/securityriskagent/safety/SecurityRiskSanitizer.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/campaignanalysisagent/graph/CampaignInsightCardFactory.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/campaignanalysisagent/graph/DefaultCampaignAnalysisGraphExecutor.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/business/shortlink/ShortLinkBusinessHttpGateway.java`
- Test: `agent-service/src/test/java/com/nageoffer/shortlink/agent/harness/security/AgentSensitiveDataSanitizerTest.java`
- Modify Campaign/Security Graph tests.

- [ ] 测试字段名、文本密钥、Bearer、JDBC URL、IP、visitor 和 user 脱敏。
- [ ] 测试 prompt、response、warning、checkpoint 使用同一组件。

## Task 4: 业务 HTTP 韧性和预算

**Files:**

- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/infrastructure/config/AgentBusinessHttpConfiguration.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/business/http/AgentBusinessHttpExecutor.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/business/shortlink/ShortLinkBusinessHttpGateway.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskprofile/source/ShortLinkBusinessRiskStatsGateway.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/tool/shortlink/PageShortLinksTool.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/tool/shortlink/GetGroupAccessRecordsTool.java`
- Test: `agent-service/src/test/java/com/nageoffer/shortlink/agent/business/http/AgentBusinessHttpExecutorTest.java`

- [ ] 测试 connect/read timeout 配置。
- [ ] 测试 GET 最多重试两次。
- [ ] 测试熔断打开期间快速失败。
- [ ] 测试 page size 最大 50，统计范围最大 31 天。
- [ ] 测试 POST 不自动重试。

## Task 5: Token 和运行观测

**Files:**

- Modify: `agent-service/pom.xml`
- Create package: `agent-service/src/main/java/com/nageoffer/shortlink/agent/harness/observation`
- Create: `AgentRunObservationService.java`
- Create: `AgentRunLog.java`
- Create: `JdbcAgentRunLogRepository.java`
- Modify: `agent-service/src/main/resources/sql/agent_service_schema.sql`
- Modify both LLM nodes and Graph executors.
- Test: `agent-service/src/test/java/com/nageoffer/shortlink/agent/harness/observation/AgentRunObservationServiceTest.java`

- [ ] 测试 DeepSeek usage 进入 dataSources、checkpoint 和 run log。
- [ ] 测试成功/失败运行记录 duration、tool count 和 warning count。
- [ ] 测试运行日志不保存原始 prompt。
- [ ] 运行 agent-service 测试。
- [ ] 提交并推送本阶段。

