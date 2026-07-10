# Agent 生产就绪增强设计

> **阶段 02 说明：** 本文第 7 节“通用待确认动作”和第 8 节“策略一致性”是总体方案。2026-07-10 已确认更细化的状态机、幂等槽位、BLOCK_IP 范围、Outbox、权限和迁移设计，实施时以 [03_待确认动作与策略一致性详细设计.md](./03_待确认动作与策略一致性详细设计.md) 为准。

## 1. 设计目标

本阶段补齐以下能力：

```text
风险画像批处理可以自动驱动 SecurityRisk Agent。
同一风险批次可以安全重跑，不重复生成画像、事件和自动策略。
单条短链失败不会中断整个批次。
人工确认可以真实执行 DISABLE_SHORT_LINK、BLOCK_IP、LIMIT_TIME_WINDOW。
同一 policyKey 同一时刻只有一个有效策略版本。
MySQL 与 Redis 同步失败可以补偿，不依赖人工清库。
Graph 使用租户隔离的线程标识，并具备原生 checkpoint 和受控会话记忆。
两个 Agent 共用统一脱敏、输入校验、HTTP 韧性和运行观测能力。
SecurityRisk Agent 输出组级和逐短链解释。
Campaign Agent 支持结构化工具规划、缺参追问和待确认创建动作。
```

## 2. 非目标

```text
不引入 Kafka、RabbitMQ 或新的工作流平台。
不让 Agent 全量扫描所有短链接。
不让 LLM 直接调用写接口。
不把风险查询封装成 Agent Tool。
不修改短链接回收站 enableStatus 语义。
不在本阶段重写 admin、project 或 gateway 的主体架构。
不以 E2E 作为本阶段每个后端任务的前置条件。
```

## 3. 关键决策

| 主题 | 决策 |
|---|---|
| Agent 框架 | 继续使用 Spring AI Alibaba Graph 1.1.2.x |
| 风险调度 | 数据库批次和任务状态机，不新增 MQ |
| 风险 Agent 粒度 | 每个 batchId + gid 一次 Graph，最多携带 Top 10 |
| Graph 输入 | 自动任务使用结构化 `RiskAnalysisInput`；交互入口保留 message |
| 批次幂等 | 固定两小时窗口生成确定性 batchId |
| 画像幂等 | 短链画像唯一键为 batchId + gid + domain + shortUri |
| 分组画像幂等 | 唯一键为 batchId + gid |
| 分析任务幂等 | 唯一键为 batchId + gid + graphName + graphVersion |
| 自动策略幂等 | 使用 batchId + target + action 生成 idempotencyKey |
| 人工动作 | 使用通用 `AgentPendingAction`，执行器归属具体业务 |
| Redis 一致性 | 有效策略槽位 + Outbox + 版本校验撤销 |
| Checkpoint | 接入 Spring AI Alibaba Graph 原生 Saver，同时保留脱敏运行快照 |
| 会话隔离 | threadKey = agentType + usernameHash + sessionId |
| 会话记忆 | 只注入脱敏、限长的最近摘要，不回放原始 Tool 明细 |
| Tool 规划 | 确定性规划优先；无法判定时才调用受约束 LLM Planner |
| 写操作 | 只生成 pending action；确认后由确定性 executor 执行 |
| 可观测性 | Micrometer Observation + 脱敏运行日志 + token/latency 指标 |

## 4. 总体架构

```text
                         +---------------------------+
                         |        agent-service      |
                         +---------------------------+

访问统计 ---> risk-profile batch coordinator
                 |
                 +--> ShortLinkRiskProfile
                 +--> GroupRiskProfile
                 +--> RiskAnalysisJob(batchId, gid)
                                      |
                                      v
                          RiskAnalysisJobWorker
                                      |
                                      v
                         SecurityRisk Graph
                      group + exact batch Top 10
                         |       |        |
                         |       |        +--> AgentPendingAction
                         |       +-----------> RiskEvent/Snapshot
                         +-------------------> auto LIMIT_RATE

人工确认 ---> AgentPendingActionService ---> AgentActionExecutorRegistry
                                               |
                          +--------------------+--------------------+
                          |                                         |
                  RiskPolicyActionExecutor              CampaignShortLinkActionExecutor
                          |                                         |
                          v                                         v
                  policy/effective/outbox                admin internal create API
                          |
                          v
                  Redis policy publisher
                          |
                          v
                  gateway hot path
```

## 5. 风险批次与任务设计

### 5.1 两小时窗口

调度时间必须对齐到 Asia/Shanghai 的偶数小时边界：

```text
10:00:00 处理 [08:00:00, 10:00:00)
12:00:00 处理 [10:00:00, 12:00:00)
```

`fixedDelay` 改为 cron 或等价的窗口协调器。即使服务在 10:17 启动，首次任务仍识别 10:00 窗口，不使用 10:17 作为画像边界。

确定性批次标识：

```text
risk-profile:{windowEndEpochSecond}
```

### 5.2 批次表

新增 `t_agent_risk_profile_batch`：

```text
batch_id
window_start
window_end
status              RUNNING / SUCCEEDED / PARTIAL_SUCCESS / FAILED
owner_token
lease_until
scanned_count
generated_count
failed_count
analysis_job_count
error_summary
start_time
finish_time
```

`batch_id` 唯一。执行器通过插入或租约抢占获得运行权，避免多实例重复执行。

### 5.3 失败隔离

每条短链画像独立捕获异常：

```text
成功：保存或更新当前 batchId 画像。
失败：记录 ShortLinkRiskProfileFailure，不阻塞其他候选。
分组画像：只聚合本批成功画像。
批次状态：存在失败但仍有成功时为 PARTIAL_SUCCESS。
```

批次结果必须返回失败目标和错误分类，但错误信息进入持久化前必须脱敏、限长。

### 5.4 分析任务

新增 `t_agent_risk_analysis_job`：

```text
job_id
batch_id
gid
graph_name
graph_version
status              PENDING / RUNNING / SUCCEEDED / RETRY_WAIT / FAILED
attempt_count
next_retry_time
owner_token
lease_until
session_id
trace_id
error_summary
create_time
update_time
```

唯一键：

```text
batch_id + gid + graph_name + graph_version
```

画像批次只负责创建任务，不同步等待 LLM。Worker 使用有限并发领取任务，失败采用指数退避，超过最大次数进入 FAILED。

## 6. SecurityRisk Graph 结构化输入

自动任务请求：

```java
public record RiskAnalysisInput(
        String batchId,
        String gid,
        LocalDateTime profileWindowEnd,
        List<RiskProfileTargetRef> candidates
) {
}
```

`SecurityRiskGraphRequest` 增加可选 `analysisInput`。自动任务必须提供结构化输入，交互式 chat 可以不提供。

`ProfileCandidateLoadNode` 行为：

```text
analysisInput 存在：
  按 batchId + gid 精确加载 group profile 和 candidate refs。
  不从 message 提取 gid。

analysisInput 不存在：
  兼容交互模式，从显式 gid 参数加载最新画像。
```

自动任务的 sessionId 使用：

```text
risk-batch:{batchId}:{gid}
```

这样重试同一任务复用同一线程，不会产生新的逻辑会话。

## 7. 通用待确认动作

### 7.1 数据模型

新增 `t_agent_pending_action`：

```text
action_id
agent_type
action_type
owner_username
gid
target_type
target_key
payload_json
evidence_json
status              PENDING / CONFIRMED / EXECUTING / EXECUTED / REJECTED / FAILED / EXPIRED
idempotency_key
expire_time
confirmed_by
confirmed_time
execution_result_json
trace_id
create_time
update_time
```

`idempotency_key` 唯一，防止 Graph 重试重复生成动作。

### 7.2 执行模型

```java
public interface AgentActionExecutor {
    AgentActionType actionType();
    AgentActionExecutionResult execute(AgentPendingAction action, AgentActionExecutionContext context);
}
```

`AgentPendingActionService.confirm()` 必须：

```text
校验 owner_username 或 gid 所有权。
使用 compare-and-set 将 PENDING 改为 EXECUTING。
调用对应 executor。
成功改为 EXECUTED，失败改为 FAILED。
重复确认 EXECUTED 动作直接返回原结果。
```

### 7.3 风控动作

支持：

```text
DISABLE_SHORT_LINK
BLOCK_IP
LIMIT_TIME_WINDOW
```

确认动作同时写 `RiskReview(CONFIRM_RISK)` 和动作审计，再调用 `RiskPolicyService`。

### 7.4 Campaign 动作

支持：

```text
CREATE_SHORT_LINK
BATCH_CREATE_SHORT_LINK
```

Graph 只保存请求参数和摘要。确认后通过 admin internal API 调用已有 project 创建接口。

## 8. 策略一致性

### 8.1 有效策略槽位

保留 `t_agent_risk_policy` 作为历史策略记录，新增 `t_agent_risk_policy_effective`：

```text
policy_key           UNIQUE
policy_id
policy_version
gid
action
payload_json
effective_time
expire_time
sync_status          PENDING / SYNCED / FAILED
update_time
```

同一 `policy_key` 只有一个有效版本。激活新策略时以数据库版本号覆盖槽位，旧历史记录保留。

### 8.2 Outbox

新增 `t_agent_policy_sync_outbox`：

```text
outbox_id
policy_key
policy_id
policy_version
operation            UPSERT / DELETE
payload_json
status               PENDING / SENT / RETRY_WAIT / FAILED
attempt_count
next_retry_time
error_summary
create_time
update_time
```

策略历史、有效槽位和 Outbox 在同一数据库事务中提交。提交后立即尝试发送，失败由补偿调度器重试。

### 8.3 Redis 版本保护

Redis payload 必须包含：

```json
{
  "policyId": "policy-001",
  "policyVersion": 3,
  "action": "LIMIT_RATE"
}
```

撤销使用 Lua compare-and-delete：

```text
仅当 Redis 中 policyId 和 policyVersion 与待撤销版本一致时删除。
旧策略撤销不得删除新策略。
```

## 9. Checkpoint 与会话

### 9.1 双层职责

```text
Spring AI Alibaba Graph MysqlSaver：
  保存节点级状态，支持 state history、恢复和中断续跑。

现有 GraphCheckpointStore：
  作为脱敏运行快照和审计摘要，不再冒充 Graph 原生 checkpoint。
```

### 9.2 线程隔离

所有 Graph 调用统一通过 `AgentThreadKeyFactory`：

```text
threadKey = agentType + ":" + sha256(username) + ":" + normalizedSessionId
```

sessionId、username、agentType 必须非空并限制长度。正式模式必须使用可信 `X-Agent-Username`，只有 console dev mode 可以回退 body username。

### 9.3 会话记忆

只恢复：

```text
最近一次脱敏 answer 摘要
最近一次卡片摘要
最近一次缺参槽位
```

不恢复：

```text
原始访问明细
原始 Tool rows
IP、visitor、user 明细
密钥、连接串、异常堆栈
```

记忆注入最大长度默认 2000 字符。

## 10. Harness 公共能力

### 10.1 统一脱敏

新增 `AgentSensitiveDataSanitizer`，覆盖：

```text
prompt
Tool arguments/result/error
LLM response
cards/dataSources/pendingActions
checkpoint/run log
HTTP exception message
```

需要识别字段名和文本模式：

```text
ip/ipAddress/rawIp
user/username/userId/uid/account
visitor/visitorId
password/token/apiKey/secret
Bearer token
sk-* 密钥
jdbc/mysql/redis URL 中的凭据
```

SecurityRiskSanitizer 迁移为该公共组件的风险策略配置，不再维护两套不同实现。

### 10.2 HTTP 韧性

业务 HTTP 配置：

```text
connectTimeoutMs = 2000
readTimeoutMs = 5000
maxGetAttempts = 2
circuitOpenSeconds = 30
maxPageSize = 50
maxStatsRangeDays = 31
```

只对幂等 GET 重试。创建短链等 POST 通过 pending action idempotencyKey 保证幂等，但 HTTP 层不自动重试。

### 10.3 可观测性

每次运行记录：

```text
agentType
threadKeyHash
traceId
status
durationMs
toolCallCount
promptTokens
completionTokens
totalTokens
warningCount
createTime
```

Micrometer 指标：

```text
agent.run.duration
agent.run.total
agent.node.duration
agent.tool.duration
agent.llm.tokens
risk.profile.batch.total
risk.analysis.job.total
agent.pending.action.total
risk.policy.sync.total
```

## 11. Agent 能力增强

### 11.1 SecurityRisk Agent

LLM 输出解析为：

```json
{
  "groupSummary": "...",
  "groupRecommendations": ["..."],
  "shortLinks": [
    {
      "targetKey": "nurl.ink/abc123",
      "explanation": "...",
      "evidenceReferences": ["TRAFFIC_SPIKE"],
      "recommendedAction": "LIMIT_RATE"
    }
  ]
}
```

规则分数、reasonCode 和自动执行判断仍以确定性节点为准。LLM 输出无法解析时保留原 answer，但不得阻塞事件持久化。

### 11.2 Campaign Planner

规划顺序：

```text
1. 确定性 intent/slot 提取。
2. 校验 ToolDescriptor 和参数策略。
3. 缺少 gid、日期或创建参数时返回 clarification card。
4. 只有确定性规划无法判断时调用 LLM Planner。
5. LLM Planner 输出必须通过 JSON schema 和 allow-list。
6. 写意图只生成 pending action。
```

### 11.3 Campaign Graph 拆分

将当前大 Graph 执行器逐步拆为：

```text
CampaignIntakeNode
CampaignPlanningNode
CampaignToolExecutionNode
CampaignInsightNode
CampaignLlmAnalysisNode
CampaignResponseComposeNode
CampaignCheckpointNode
```

拆分必须保持现有 API 响应兼容，并由现有测试保护。

## 12. 验收标准

```text
1. 同一两小时窗口多次触发只产生一个逻辑批次。
2. 单条短链统计失败不阻塞其他画像。
3. 每个 batchId + gid 只存在一个风险分析任务。
4. 风控 Graph 自动任务只消费指定 batchId 的 Top 10。
5. Graph 重试不重复写风险事件和自动 LIMIT_RATE。
6. 人工确认可以真实执行三种人工风险策略。
7. 同一 policyKey 不存在多个有效版本。
8. 撤销旧策略不会删除 Redis 新版本。
9. Redis 暂时失败后 Outbox 可以恢复同步。
10. 正式模式缺少可信 username 或非法 sessionId 时拒绝请求。
11. 同 session 不同用户无法读取彼此 checkpoint 或记忆。
12. Campaign prompt、响应和 checkpoint 使用统一脱敏。
13. Tool GET 具备超时、有限重试、熔断和分页上限。
14. 响应和运行日志记录 LLM token 与关键耗时。
15. SecurityRisk 事件包含逐短链解释。
16. Campaign 缺参时返回 clarification，不静默空跑。
17. Campaign 创建动作确认前不调用写 API。
18. 所有配置和测试不包含真实密钥。
```

