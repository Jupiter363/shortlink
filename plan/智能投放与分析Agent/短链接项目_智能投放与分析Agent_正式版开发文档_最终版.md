# 短链接项目：智能投放与分析 Agent 正式版开发文档（最终版）

> 文档性质：第一阶段开发执行契约、模块拆分、接口与测试基线  
> 适用对象：后续 Codex 开发、接口联调、测试、验收和阶段复盘  
> 当前任务边界：本文档定义开发计划，不代表当前代码已经实现  
> 已确认技术路线：Java / Spring Boot / Spring AI Alibaba Agent Framework + Graph  
> 实施原则：Agent 先独立构建，再通过 `admin` 扩展接入；不完全重构原短链接项目  

---

## 0. 文档定位

本文档用于指导后续分阶段实现“智能投放与分析 Agent”。

第一阶段目标不是重写短链接系统，而是在现有系统旁边构建一个可独立测试的 Java Agent 能力层：

```text
agent-service 负责 Spring AI Alibaba Graph、Agent 编排、模型调用、分析解释；
agent-service 内部 Agent Tool Facade 负责工具 schema、工具执行、安全限制、PendingAction 和审计；
business API 负责把工具意图转换为短链业务能力；
admin 复用现有用户、分组和短链后台能力，按需补充 internal API；
project 保持短链接与统计事实源职责；
gateway/nginx 只做必要路由接入；
第一阶段先完成 API 联调，随后在 agent-service 内交付独立 Agent Console。
```

---

## 1. 建设目标

### 1.1 产品目标

让后台用户可以用自然语言完成：

```text
帮我给“暑期活动”生成 5 条渠道短链；
把这个落地页做成短链，有效期到月底；
分析最近 7 天哪个分组表现最好；
这个短链为什么昨天点击突然变多；
总结一个活动复盘，说明地区、设备和时间段表现；
根据已有数据建议下一步应该优化什么。
```

### 1.2 工程目标

1. Agent 运行逻辑与原业务服务解耦。
2. Agent 工具只通过 agent-service 内部 Tool Facade 和 business API 访问短链能力。
3. 读操作可直接执行，写操作必须确认。
4. Agent 回答可追溯到工具结果和数据快照。
5. Graph 支持中断、恢复和基础持久化设计。
6. Graph checkpoint 第一版直接 MySQL 持久化。
7. agent-service 具备独立 Agent Console，便于解耦调试和后续产品化。
8. 不影响现有短链创建、跳转和统计主链路。

### 1.3 成功标准

| 场景 | 验收标准 |
|---|---|
| 查询活动表现 | Agent 能调用分组统计并返回真实 PV/UV/UIP、趋势和建议 |
| 查询单链表现 | Agent 能调用单链统计并解释访问画像 |
| 创建单条短链 | Agent 能生成创建草案，用户确认后创建 |
| 批量创建短链 | Agent 能按渠道列表生成批量创建草案，用户确认后执行 |
| 异常解释 | Agent 能基于 Top IP、UV/PV 比、设备/地区集中度给出可能原因 |
| 权限隔离 | 用户不能通过 Agent 查询不属于自己的 gid |
| 写操作安全 | 未确认的写操作不会执行 |
| Graph 运行 | 关键节点、状态、工具调用和中断点可追溯 |
| 独立 Agent Console | 可独立完成对话、指标卡、数据来源、PendingAction 预览和 Graph Trace 查看 |

---

## 2. 开发范围

### 2.1 必须实现

#### 2.1.1 Java Agent Service

技术栈：

```text
Java 17；
Spring Boot 3；
Spring AI；
Spring AI Alibaba Agent Framework；
Spring AI Alibaba Graph；
Maven；
MyBatis-Plus 或现有持久化风格；
WebFlux/SSE 可选，用于流式节点进度。
```

职责：

```text
提供 chat API；
维护会话；
构建 Graph；
执行意图识别；
选择工具节点；
调用 agent-service 进程内 Agent Tool Facade；
执行确定性数据分析；
调用模型生成解释和建议；
生成结构化响应；
记录 Graph run / tool trace / pending action data source。
```

建议目录：

```text
agent-service/
  pom.xml
  src/main/java/com/nageoffer/shortlink/agent/
    ShortLinkAgentApplication.java
    agent/
      graph/
      state/
      node/
      prompt/
      policy/
    harness/
      api/
      runtime/
      session/
      checkpoint/
      trace/
    tool/
      core/
      registry/
      springai/
      group/
      link/
      stats/
      action/
    business/
      api/
      service/
      model/
    infrastructure/
      client/
      mapper/
      persistence/
      config/
      security/
    common/
  src/main/resources/
    application.yaml
    mapper/
    prompts/
      campaign_analyst_v1.md
      tool_use_policy_v1.md
      safety_policy_v1.md
    sql/
      agent_service_schema.sql
    static/
      agent-console/
  src/test/java/...
```

#### 2.1.2 Tool Facade、Business API 与 Admin API 接入

职责：

```text
tool 层封装 Agent 可调用的语义化工具；
business 层提供短链业务能力抽象；
infrastructure client 调用 admin 现有 API 或必要 internal API；
admin 侧复用 UserContext，检查用户拥有的 gid；
写操作只创建 PendingAction，确认后再执行；
访问明细必须脱敏、限页和审计；
不引入 MCP Server，不建设独立远程工具协议。
```

agent-service 建议包结构：

```text
agent-service/src/main/java/com/nageoffer/shortlink/agent/
  tool/
    core/
    registry/
    springai/
    group/
    link/
    stats/
    action/
  business/
    api/
    service/
    model/
  infrastructure/
    client/
```

admin 侧只补充必要 internal API：

```text
admin/src/main/java/com/nageoffer/shortlink/admin/agent/
  controller/
    AgentInternalApiController.java
    AgentPendingActionController.java
  service/
    AgentInternalApiService.java
    PendingActionService.java
  dto/
  action/
  audit/
```

#### 2.1.3 Graph 工作流

必须实现的第一阶段 Graph：

```text
campaignAnalysisGraph
```

核心节点：

```text
IntakeNode；
IntentRouterNode；
ScopeResolveNode；
PermissionGuardNode；
ReadToolNode；
AnalysisNode；
ReasoningNode；
ProposalNode；
PendingActionNode；
ResponseComposeNode；
ErrorFallbackNode。
```

#### 2.1.4 Agent Console

`agent-service` 必须提供独立 Agent Console，用于第一阶段 API 联调后的独立体验验证。

职责：

```text
提供对话输入和结果展示；
展示 answer/cards/pendingActions/dataSources/warnings；
展示 Graph 节点流转和工具调用轨迹；
支持 PendingAction 预览；
支持本地开发环境测试用户配置；
生产环境不绕过 admin/Gateway 鉴权。
```

建议技术形态：

```text
优先使用 agent-service 内置静态资源或轻量前端目录；
第一版以可联调、可观测、可演示为目标；
后续再评估是否抽成独立前端工程或嵌入 admin 前端。
```

#### 2.1.5 Agent 会话和动作确认

必须支持：

```text
session；
message；
graph run；
tool call；
pending action；
action confirm；
action cancel；
action expire。
```

#### 2.1.6 只读分析工具

必须封装：

```text
list_groups；
page_short_links；
get_short_link_stats；
get_group_stats；
get_access_records；
get_url_title。
```

#### 2.1.7 写操作工具

第一阶段只开放：

```text
create_group；
create_short_link；
batch_create_short_links。
```

全部必须走 PendingAction。

### 2.2 第一阶段不实现

```text
不接广告平台 API；
不做智能跳转；
不做转化事件；
不做自动投放优化；
不做自动回收/删除；
不接真实 URL 安全扫描服务；
不引入大型数据仓库；
不重写前端构建产物；
不把 Graph checkpoint 当业务事实源。
```

---

## 3. 模块设计

### 3.1 Agent API 模块

#### 职责

接收 admin 代理过来的用户对话请求，返回 Agent 回复。

#### API

```text
GET  /internal/short-link-agent/v1/health
POST /internal/short-link-agent/v1/chat
```

#### 输入

```json
{
  "sessionId": "optional",
  "username": "from-admin-not-from-browser",
  "message": "帮我分析最近 7 天暑期活动表现",
  "context": {
    "source": "admin",
    "locale": "zh-CN"
  }
}
```

#### 输出

```json
{
  "sessionId": "agt_xxx",
  "traceId": "trace_xxx",
  "answer": "最近 7 天...",
  "cards": [],
  "pendingActions": [],
  "dataSources": [],
  "warnings": []
}
```

#### 禁止事项

```text
不得信任浏览器 username；
不得直接访问 MySQL / Redis 业务数据；
不得生成未绑定工具结果的数字指标；
不得自动执行写工具。
```

### 3.2 Intent Router

#### 意图枚举

| 意图 | 说明 |
|---|---|
| `CREATE_LINK` | 创建单条短链 |
| `BATCH_CREATE_LINKS` | 批量创建短链 |
| `ANALYZE_GROUP` | 分析分组/活动表现 |
| `ANALYZE_LINK` | 分析单条短链 |
| `EXPLAIN_ANOMALY` | 异常解释 |
| `GENERATE_REPORT` | 生成复盘报告 |
| `CLARIFY` | 参数缺失追问 |
| `GENERAL_HELP` | 使用帮助 |

#### 关键要求

1. 缺少 `gid` 时优先调用 `list_groups` 或追问。
2. 缺少时间范围时默认最近 7 天，并在回答中说明。
3. 涉及创建时必须生成确认草案。
4. 涉及删除/回收/禁用时第一阶段拒绝并说明原因。

### 3.3 Graph Orchestrator

#### 职责

创建、编译和执行 Spring AI Alibaba Graph。

#### 要求

```text
Graph 名称和版本必须显式记录；
节点输入输出结构化；
节点失败进入 ErrorFallbackNode；
写操作节点只能生成 PendingAction；
支持 threadId/sessionId 关联；
支持 checkpoint 扩展；
支持流式输出扩展。
```

#### 建议 threadId

```text
threadId = sessionId + ":" + traceId
```

如果未来引入正式 Campaign 对象，可调整为：

```text
threadId = campaignId + ":" + taskId
```

### 3.4 Business API 与 Admin API Client

Agent Service 中的工具层不直接调用原始 Controller，也不暴露 MCP。推荐调用链：

```text
Graph Node
  -> AgentTool
  -> BusinessApi
  -> AdminApiClient
  -> admin existing/internal API
```

`BusinessApi` 负责参数收敛、权限上下文传递和业务语义；`AdminApiClient` 只负责 HTTP 调用、超时、错误映射和内部 token。

#### 重试策略

| 错误 | 策略 |
|---|---|
| 网络超时 | 最多重试 1 次 |
| 401/403 | 不重试，返回权限错误 |
| 参数错误 | 不重试，触发追问 |
| 统计无数据 | 不报错，返回空状态 |
| 写操作失败 | 返回失败原因，不自动重放 |

### 3.5 Analysis Engine

所有数值分析必须由确定性代码完成：

```text
排序；
聚合；
环比；
占比；
Top N；
异常初筛；
UV/PV 比；
UIP/PV 比；
设备/地区集中度；
时间段峰值；
新老访客结构。
```

LLM 只负责：

```text
把确定性分析结果解释成人话；
生成建议；
组织报告结构；
追问缺失参数。
```

### 3.6 Response Composer

输出必须包含：

```text
一句话结论；
关键指标；
证据或数据来源；
可能原因；
建议动作；
风险提示；
后续可执行操作。
```

示例：

```text
结论：最近 7 天“暑期活动”主要流量来自移动端，UV 集中在周三和周五晚间。
依据：get_group_stats 返回 2026-07-01 至 2026-07-07 的 daily/hour/device 数据。
建议：下次投放优先安排在 19:00-22:00，并单独生成移动端渠道短链。
```

---

## 4. Admin 接入接口设计

### 4.1 Chat Proxy

前端先调 admin，再由 admin 调 agent-service。

```text
POST /api/short-link/admin/v1/agent/chat
Auth: gateway token
```

职责：

```text
读取 UserContext；
把 username/userId 安全传给 agent-service；
不把浏览器 token 传给模型；
返回 Agent 结构化响应。
```

### 4.2 Internal API

内部 API 示例：

```text
POST /internal/short-link-admin/v1/agent/list-groups
POST /internal/short-link-admin/v1/agent/page-short-links
POST /internal/short-link-admin/v1/agent/get-group-stats
POST /internal/short-link-admin/v1/agent/get-short-link-stats
POST /internal/short-link-admin/v1/agent/get-access-records
POST /internal/short-link-admin/v1/agent/create-pending-action
POST /internal/short-link-admin/v1/agent/execute-pending-action
```

这些接口只服务 `agent-service` 内部调用，不作为 MCP Server 或对外工具协议。

所有工具 API 必须：

```text
校验 internal auth；
接收 username；
校验 gid；
脱敏输出；
记录 audit；
限制分页和批量数量。
```

### 4.3 PendingAction API

```text
POST /api/short-link/admin/v1/agent/actions/{actionId}/confirm
POST /api/short-link/admin/v1/agent/actions/{actionId}/cancel
```

执行规则：

```text
action 必须属于当前 username；
action 必须是 pending 状态；
action 未过期；
payload hash 未变化；
确认后只能执行一次；
执行结果必须写审计。
```

---

## 5. 数据库设计

### 5.1 第一阶段表清单

建议在 `admin` 数据源所在库新增：

```text
t_agent_session
t_agent_message
t_agent_graph_run
t_agent_tool_call
t_agent_pending_action
t_agent_graph_checkpoint
```

第一阶段不分表。后续如果会话量变大，再评估按 `username` 或 `session_id` 分表。

### 5.2 字段规范

所有 Agent 表必须包含：

```text
id；
create_time；
update_time；
del_flag。
```

涉及用户的数据必须包含：

```text
username；
trace_id；
session_id。
```

### 5.3 数据保留

| 数据 | 默认保留 |
|---|---|
| session/message | 90 天 |
| graph_run | 90 天 |
| tool_call | 30 天 |
| pending_action | 180 天 |
| graph_checkpoint | 30 天或随 session 清理 |
| 错误日志 | 30 天 |

课程演示阶段可先不做自动清理，但表结构必须预留。

---

## 6. 配置设计

### 6.1 agent-service 配置

```yaml
server:
  port: 8010

short-link:
  agent:
    graph:
      name: campaign-analysis-graph
      version: v1
      checkpoint-enabled: true
    admin:
      base-url: http://127.0.0.1:8002
      internal-token: ${SHORTLINK_AGENT_INTERNAL_TOKEN:change-me}
      tool-timeout-ms: 5000
    limit:
      max-batch-create-size: 20
      access-record-max-page-size: 100
    trace:
      enabled: true

spring:
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY:${LLM_API_KEY:}}
      base-url: ${LLM_BASE_URL:https://api.deepseek.com}
      chat:
        options:
          model: ${LLM_MODEL:deepseek-v4-flash}
```

### 6.2 admin 配置

```yaml
short-link:
  agent:
    enabled: true
    write-enabled: false
    service-url: http://127.0.0.1:8010
    internal-token: ${SHORTLINK_AGENT_INTERNAL_TOKEN:change-me}
    max-batch-create-size: 20
    tool-timeout-ms: 5000
    access-record-max-page-size: 100
    trace-enabled: true
    risk-scan-enabled: false
```

### 6.3 Feature Flag

| 配置 | 默认 | 说明 |
|---|---|---|
| `short-link.agent.enabled` | false | 是否启用 Agent API |
| `short-link.agent.write-enabled` | false | 是否允许确认后写操作 |
| `short-link.agent.trace-enabled` | true | 是否记录工具轨迹 |
| `short-link.agent.risk-scan-enabled` | false | 是否启用 URL 风险扫描预留 |
| `short-link.agent.graph.checkpoint-enabled` | true | 是否启用 Graph checkpoint |

---

## 7. 错误码规范

建议新增 Agent 错误码：

| 错误码 | 场景 |
|---|---|
| `A000001` | Agent 服务不可用 |
| `A000002` | 意图无法识别 |
| `A000003` | 缺少必要参数 |
| `A000004` | 工具调用失败 |
| `A000005` | 用户无权访问该分组 |
| `A000006` | 写操作需要确认 |
| `A000007` | PendingAction 不存在或已过期 |
| `A000008` | 模型输出结构不合法 |
| `A000009` | 统计数据为空 |
| `A000010` | 批量创建数量超限 |
| `A000011` | Graph 执行失败 |
| `A000012` | Graph checkpoint 恢复失败 |

---

## 8. 测试方案

### 8.1 Agent Service 单元测试

必须覆盖：

```text
意图识别；
参数抽取；
缺参追问；
Graph 节点流转；
Graph 错误降级；
分析计算；
工具错误处理；
无数据回答；
写操作生成 PendingAction；
禁止高风险动作。
```

### 8.2 Tool Facade 与 Admin API 集成测试

必须覆盖：

```text
用户分组权限校验；
gid 越权拒绝；
短链分页工具；
统计工具；
访问明细脱敏；
PendingAction 确认执行；
过期动作拒绝；
重复确认幂等。
```

### 8.3 端到端测试

至少覆盖：

```text
自然语言创建单条短链；
自然语言批量创建短链；
分析最近 7 天分组表现；
分析单条短链设备分布；
解释 Top IP 异常；
用户尝试访问他人 gid 被拒绝。
```

### 8.4 回归测试

必须确认：

```text
用户注册登录可用；
分组创建与列表可用；
短链创建可用；
短链跳转可用；
短链统计可用；
回收站可用；
gateway 鉴权可用。
```

---

## 9. Codex 开发阶段拆解

### Phase 1：文档确认与边界冻结

目标：

```text
确认第一阶段只做智能投放与分析 Agent；
确认技术路线为 Spring AI Alibaba Graph；
确认安全风控 Agent、智能路由和转化归因为第二阶段。
```

输出：

```text
最终版计划文档；
确认技术栈；
确认服务形态；
确认写操作策略。
```

禁止：

```text
禁止直接开始改业务代码；
禁止扩大到广告平台；
禁止改跳转主链路。
```

### Phase 2：Java Agent Service 骨架

目标：

```text
创建独立 agent-service Maven module；
引入 Spring AI Alibaba Agent Framework / Graph；
配置 DeepSeek V4 Flash 默认模型；
提供 health API；
提供 chat API mock；
跑通基础 Graph 节点流转；
跑通 mock Tool 调用；
建立 MySQL Graph checkpoint 基础表和写入链路；
提供 Agent Console 静态骨架。
```

涉及文件：

```text
agent-service/**
pom.xml
```

验收：

```text
服务可启动；
health API 返回 OK；
chat API 返回结构化响应；
Graph 执行有 traceId；
Graph checkpoint 可写入 MySQL；
Agent Console 可打开并调用 mock chat；
单元测试通过。
```

### Phase 3：Tool Facade、Business API 与只读工具

目标：

```text
新增 agent-service Tool Facade 和 business API；
封装分组、短链列表、统计查询；
通过 AdminApiClient 调用 admin 现有接口和必要 internal API；
实现 gid 权限校验和访问明细脱敏。
```

涉及文件：

```text
agent-service/src/main/java/com/nageoffer/shortlink/agent/tool/**
agent-service/src/main/java/com/nageoffer/shortlink/agent/business/**
agent-service/src/main/java/com/nageoffer/shortlink/agent/infrastructure/client/**
admin/src/main/java/com/nageoffer/shortlink/admin/agent/**
admin/src/main/resources/application.yaml
```

验收：

```text
工具 API 能校验当前用户；
gid 越权被拒绝；
统计结果可脱敏返回；
已有后台接口不受影响。
```

### Phase 4：Graph 分析闭环

目标：

```text
Graph 能调用真实只读工具；
完成分组/单链分析；
返回 cards/dataSources/warnings。
```

验收：

```text
回答包含真实数据来源；
空数据场景不报错；
工具失败有降级回答；
生成结构化 cards。
```

### Phase 5：PendingAction 与写操作确认

目标：

```text
支持创建分组、创建短链、批量创建短链的确认执行。
```

验收：

```text
未确认不执行；
确认后只执行一次；
过期动作不可执行；
批量数量受限制；
执行结果可追溯。
```

### Phase 6：网关与前端接入

目标：

```text
通过现有后台入口接入 Agent；
将 agent-service 独立 Agent Console 的能力按需接入 admin/gateway。
```

验收：

```text
Gateway/Nginx 路由可达；
登录用户可使用 Agent；
未登录用户不可访问；
agent-service 独立 Console 可展示回答、指标卡、PendingAction 和 Graph Trace；
admin 前端可按需跳转或嵌入 Agent Console。
```

### Phase 7：回归、验收与第二阶段评审

目标：

```text
完成回归测试和验收；
评审安全风控 Agent 的实现计划。
```

验收：

```text
现有短链创建/跳转/统计不回归；
Agent 场景测试通过；
验收清单有证据；
第二阶段边界明确。
```

---

## 10. 开发红线

1. 不允许 Agent 直接访问业务数据库。
2. 不允许 Agent 绕过 admin 权限。
3. 不允许 Agent 自动执行删除、回收、更新跳转目标。
4. 不允许 LLM 输出未经工具证实的指标。
5. 不允许把 token、手机号、邮箱、完整 IP 注入模型。
6. 不允许在跳转热路径调用大模型。
7. 不允许为了 Agent 第一阶段重构所有统计链路。
8. 不允许把安全风控、智能路由、转化归因混入第一阶段验收。
9. 不允许把 Graph checkpoint 当成业务最终事实。

---

## 11. 已确认事项与后续建议

### 11.1 已确认事项

```text
第一阶段采用 Spring AI Alibaba Agent Framework + Graph；
默认模型使用 DeepSeek V4 Flash；
第一阶段优先做智能投放与分析 Agent；
第一阶段先做 API 联调，随后交付 agent-service 独立 Agent Console；
安全风控 Agent 作为第二阶段；
不完全重构原项目；
agent-service 作为顶级 Maven module；
先构建 Agent，再通过 admin 扩展接入；
写操作必须 PendingAction 确认；
Graph checkpoint 第一版直接 MySQL 持久化。
```

### 11.2 密钥与后续建议

1. DeepSeek API Key 只通过 `DEEPSEEK_API_KEY` 或 `LLM_API_KEY` 注入。
2. 不把 API Key 写入文档、代码、Git、Prompt、测试样例或日志。
3. agent-service 独立 Console 可以先做轻量页面，优先服务 API 联调、Graph Trace 和 PendingAction 预览。
4. 后续接入 admin 前端时，保持 admin/Gateway 鉴权为生产用户边界。
