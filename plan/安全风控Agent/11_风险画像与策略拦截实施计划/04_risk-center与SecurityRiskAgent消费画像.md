# risk-center 与 SecurityRisk Agent 消费画像 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 交付风险事件、快照、人工审核 API，并让 SecurityRisk Agent 消费画像、写事件快照、在满足确定性条件时自动 LIMIT_RATE。

**Architecture:** risk-center 提供普通查询和审核 API；SecurityRisk Agent 只读取 risk-profile 产出的异常候选和分组画像事实，不负责全量画像计算。

**Tech Stack:** Java 17、Spring Boot 3.0.7、JUnit 5、MySQL/H2、Redis；按本批涉及模块额外使用 Spring Cloud Gateway、Spring AI Alibaba Graph、MockMvc 或 WebTestClient。

---

## 执行范围

覆盖 Task 11 - Task 12。
本批完成后，后台页面已经可以通过 internal API 查询风险分组、短链卡片、事件历史和审核记录。

## 执行前检查

- [ ] 确认 11-03 已保存 ShortLinkRiskProfile 与 GroupRiskProfile。
- [ ] 确认 SecurityRisk Agent prompt 只允许解释给定画像事实。
- [ ] 确认 LIMIT_RATE 自动执行条件保持 HIGH + riskScore>=80 + 两个强 reasonCode。

## 任务明细

### Task 11: risk-center 事件、快照、审核 API

**Files:**

- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/model/RiskEvent.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/model/RiskSnapshot.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/model/RiskReview.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/repository/JdbcRiskEventRepository.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/repository/JdbcRiskSnapshotRepository.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/repository/JdbcRiskReviewRepository.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/service/RiskCenterService.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/api/RiskCenterInternalController.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/api/dto/RiskGroupOverviewRespDTO.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/api/dto/RiskShortLinkCardRespDTO.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/api/dto/RiskShortLinkDetailRespDTO.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/api/dto/RiskEventQueryReqDTO.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/api/dto/RiskEventRespDTO.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/api/dto/RiskReviewReqDTO.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/api/dto/RiskReviewRespDTO.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/api/dto/RiskPolicyDisableReqDTO.java`
- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskcenter/RiskCenterRepositoryTest.java`
- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskcenter/RiskCenterInternalControllerTest.java`

- [ ] **Step 1: 写 repository 失败测试**

断言：

```text
saveEvent 写历史事件。
upsertSnapshot 对同一 target 更新 latest。
listEvents(gid, targetType, page) 按 event_time desc。
saveReview 写人工审核记录。
```

- [ ] **Step 2: 写 controller 失败测试**

覆盖 API：

```text
GET /internal/short-link-agent/v1/risk/groups/{gid}/overview
GET /internal/short-link-agent/v1/risk/groups/{gid}/short-links
GET /internal/short-link-agent/v1/risk/short-links/{domain}/{shortUri}
GET /internal/short-link-agent/v1/risk/events
POST /internal/short-link-agent/v1/risk/reviews
POST /internal/short-link-agent/v1/risk/policies/{policyId}/disable
```

断言响应不含：

```text
rawIp
ipAddress
userId 明细
visitorId 明细
access_records.rawData
```

- [ ] **Step 3: 运行失败测试**

Run:

```bash
mvn -pl agent-service -Dtest=RiskCenterRepositoryTest,RiskCenterInternalControllerTest test
```

Expected: FAIL，原因是 riskcenter 不存在。

- [ ] **Step 4: 实现 RiskCenterService**

公开方法：

```java
public RiskGroupOverview getGroupOverview(String gid)
public List<RiskShortLinkCard> listGroupShortLinkCards(String gid)
public RiskShortLinkDetail getShortLinkRisk(String domain, String shortUri)
public PageResult<RiskEvent> listEvents(RiskEventQuery query)
public RiskReview submitReview(RiskReviewCommand command)
public void disablePolicy(String policyId, String reviewer, String reason)
public void recordProfileBatchEvent(ShortLinkRiskProfile profile, String traceId)
public void upsertSnapshotFromProfile(ShortLinkRiskProfile profile, String eventId, String traceId)
```

审核动作语义：

```text
CONFIRM_RISK：记录审核，后续可创建策略。
FALSE_POSITIVE：记录审核，快照风险可降级为 LOW，watchStatus 不变。
IGNORE：记录审核，不改变策略。
WATCH：快照 watchStatus=WATCHING。
UNWATCH：快照 watchStatus=NONE。
```

- [ ] **Step 5: 实现 controller**

controller 返回统一 `Result<T>`。所有 internal API 由现有 `InternalAgentApiFilter` 保护，不新增公网入口。

- [ ] **Step 6: 运行通过测试**

Run:

```bash
mvn -pl agent-service -Dtest=RiskCenterRepositoryTest,RiskCenterInternalControllerTest test
```

Expected: PASS。

- [ ] **Step 7: 提交并推送**

```bash
git add agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter agent-service/src/test/java/com/nageoffer/shortlink/agent/riskcenter
git commit -m "feat: add risk center query and review api"
git push
```

### Task 12: SecurityRisk Agent 消费画像并自动 LIMIT_RATE

**Files:**

- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/securityriskagent/graph/DefaultSecurityRiskGraphExecutor.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/securityriskagent/node/ProfileCandidateLoadNode.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/securityriskagent/node/RiskEventPersistNode.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/securityriskagent/node/RiskAutoActionNode.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/securityriskagent/model/ProfileRiskAnalysisContext.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/securityriskagent/prompt/SecurityRiskPromptBuilder.java`
- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/securityriskagent/node/ProfileCandidateLoadNodeTest.java`
- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/securityriskagent/node/RiskEventPersistNodeTest.java`
- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/securityriskagent/node/RiskAutoActionNodeTest.java`
- Modify: `agent-service/src/test/java/com/nageoffer/shortlink/agent/securityriskagent/graph/DefaultSecurityRiskGraphExecutorTest.java`

- [ ] **Step 1: 写画像加载节点失败测试**

输入：

```text
message 包含 gid=gid-001
batchResult 已包含 gid-001 的 Top 10 短链画像
```

断言：

```text
ProfileCandidateLoadNode 输出 profileRiskContext。
最多包含 10 条 shortLinkProfiles。
不包含 rawIp/user/visitor 明细。
```

- [ ] **Step 2: 写事件持久化节点失败测试**

断言：

```text
RiskEventPersistNode 为每条 HIGH/MEDIUM 异常短链写 RiskEvent。
同步 upsert RiskSnapshot。
组级 summary 写入 GroupRiskProfile.agentSummary。
```

- [ ] **Step 3: 写自动 LIMIT_RATE 失败测试**

断言：

```java
assertThat(autoActionNode.apply(highScoreWithTwoStrongReasons()).activatedPolicies()).hasSize(1);
assertThat(autoActionNode.apply(highScoreWithOneStrongReason()).activatedPolicies()).isEmpty();
assertThat(autoActionNode.apply(disableRecommendation()).activatedPolicies()).isEmpty();
```

- [ ] **Step 4: 运行失败测试**

Run:

```bash
mvn -pl agent-service -Dtest=ProfileCandidateLoadNodeTest,RiskEventPersistNodeTest,RiskAutoActionNodeTest,DefaultSecurityRiskGraphExecutorTest test
```

Expected: FAIL，原因是新节点不存在。

- [ ] **Step 5: 扩展 Graph 节点顺序**

新顺序：

```text
START
  -> intake
  -> profile_candidate_load
  -> risk_tool_planning
  -> risk_scoring
  -> llm_explanation
  -> risk_event_persist
  -> risk_auto_action
  -> response_compose
  -> END
```

`risk_tool_planning` 保留原只读工具能力，但优先使用 `profileRiskContext` 中的画像事实。

- [ ] **Step 6: 修改 PromptBuilder**

prompt 必须明确：

```text
1. 只解释已给定画像和统计事实。
2. 不编造 7 天趋势。
3. 不输出原始 IP/user/visitor。
4. 对 DISABLE_SHORT_LINK、BLOCK_IP、LIMIT_TIME_WINDOW 只能生成 pendingAction。
5. LIMIT_RATE 只有满足确定性规则时才允许 autoAction。
```

- [ ] **Step 7: 运行通过测试**

Run:

```bash
mvn -pl agent-service -Dtest=ProfileCandidateLoadNodeTest,RiskEventPersistNodeTest,RiskAutoActionNodeTest,DefaultSecurityRiskGraphExecutorTest test
```

Expected: PASS。

- [ ] **Step 8: 敏感信息扫描**

Run:

```bash
rg -n "rawIp|ipAddress|visitorId|access_records\\.rows|access_records\\.rawData|sk-[A-Za-z0-9]{16,}" agent-service/src/main/java/com/nageoffer/shortlink/agent/securityriskagent agent-service/src/test/java/com/nageoffer/shortlink/agent/securityriskagent
```

Expected: no output。

- [ ] **Step 9: 提交并推送**

```bash
git add agent-service/src/main/java/com/nageoffer/shortlink/agent/securityriskagent agent-service/src/test/java/com/nageoffer/shortlink/agent/securityriskagent
git commit -m "feat: let security risk agent consume risk profiles"
git push
```


## 批次完成验收

Run:

```bash
mvn -pl agent-service "-Dtest=RiskCenterRepositoryTest,RiskCenterInternalControllerTest,ProfileCandidateLoadNodeTest,RiskEventPersistNodeTest,RiskAutoActionNodeTest,DefaultSecurityRiskGraphExecutorTest" test
rg -n "rawIp|ipAddress|visitorId|access_records\\.rows|access_records\\.rawData|sk-[A-Za-z0-9]{16,}" agent-service/src/main/java/com/nageoffer/shortlink/agent/securityriskagent agent-service/src/test/java/com/nageoffer/shortlink/agent/securityriskagent
git diff --check
```

Expected:

```text
risk-center API 不走 LLM。
SecurityRisk Agent 只处理 Top 10 异常候选。
RiskEvent/RiskSnapshot 写入成功。
LIMIT_RATE 只在确定性条件满足时自动发布策略。
敏感信息扫描无真实明细泄漏。
```
