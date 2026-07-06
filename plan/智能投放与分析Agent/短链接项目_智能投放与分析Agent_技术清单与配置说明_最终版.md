# 短链接项目：智能投放与分析 Agent 技术清单与配置说明（最终版）

> 文档性质：第一阶段技术选型、组件边界、配置规范和启动说明  
> 适用形态：本地开发、课程演示、后续生产化演进  
> 当前任务边界：本文档只定义技术方案，不声明当前代码已经完成实现  
> 已确认技术路线：Java / Spring Boot / Spring AI Alibaba Agent Framework + Graph  
> 核心原则：Agent 能力独立，短链业务事实仍由现有 Java 服务负责  

---

## 1. 技术定位

第一阶段系统是短链接后台的智能运营助手，不是广告投放平台。

技术主线：

```text
Graph 控流程；
ReactAgent 做认知；
Tool 取事实；
Analysis 做确定性计算；
PendingAction 做人审门控；
Tool Facade 管工具安全；
Business API 管业务抽象；
admin/project 做事实源和权限复用；
Audit 做追溯。
```

---

## 2. 技术栈总览

### 2.1 应用层

| 组件 | 推荐技术 | 职责 |
|---|---|---|
| `agent-service` | Java 17、Spring Boot 3、Spring AI、Spring AI Alibaba Agent Framework、Spring AI Alibaba Graph | Agent Runtime、Graph 编排、模型调用、Agent Tool Facade、独立 Agent Console |
| `admin` | 当前 Java/Spring Boot 体系 | 用户、分组、后台管理、复用现有 API，按需补充 internal API |
| `project` | 当前 Java/Spring Boot 体系 | 短链创建、跳转、统计事实源 |
| `gateway` | Spring Cloud Gateway | Token 校验、统一入口 |
| `nginx` | 当前 Nginx | 前端静态资源与 `/api` 代理 |

### 2.2 模型与工具

| 组件 | 技术 | 用途 |
|---|---|---|
| Agent Framework | Spring AI Alibaba Agent Framework | ReactAgent、工具调用、结构化输出 |
| Workflow | Spring AI Alibaba Graph | 节点、边、条件路由、状态、中断、恢复 |
| Tool Calling | Spring AI Tool / ToolCallback | 模型请求工具，应用侧执行工具 |
| LLM Provider | DeepSeek V4 Flash / OpenAI-compatible | 第一阶段默认 DeepSeek V4 Flash，保留可配置切换 |
| Studio/Admin | Spring AI Alibaba Studio/Admin | 后续调试、观测、评估、Prompt 管理 |

### 2.3 数据与基础设施

| 组件 | 用途 | 禁止用途 |
|---|---|---|
| MySQL | Agent 会话、消息、工具轨迹、PendingAction、Graph checkpoint | 不让模型直接查询 |
| Redis | 限流、短期状态、分布式锁 | 不作长期 Agent 事实源 |
| Nacos | 现有服务配置/发现 | 不存密钥明文 |
| 应用日志 | 本地调试、错误追踪 | 不记录 token、完整 IP、完整 UV |

---

## 3. Maven 模块规划

建议新增顶级模块：

```text
shortlink/
  agent-service/
    pom.xml
    src/main/java/com/nageoffer/shortlink/agent/
    src/main/resources/
  admin/
  project/
  gateway/
  aggregation/
```

父级 `pom.xml` 增加：

```xml
<module>agent-service</module>
```

### 3.1 agent-service 建议依赖

版本以实际可用 Spring Boot / Spring AI Alibaba 兼容矩阵为准，开发前需做最小 POC 验证。

```xml
<dependencies>
    <dependency>
        <groupId>com.alibaba.cloud.ai</groupId>
        <artifactId>spring-ai-alibaba-agent-framework</artifactId>
    </dependency>
    <dependency>
        <groupId>com.alibaba.cloud.ai</groupId>
        <artifactId>spring-ai-alibaba-graph-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
</dependencies>
```

如使用 DeepSeek V4 Flash：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-deepseek</artifactId>
</dependency>
```

DeepSeek 在 Spring AI 中可走专用 starter；如果后续切换到其他 OpenAI-compatible 模型，再按 Spring AI 当前版本选择 `spring-ai-starter-model-openai` 或自定义 `ChatModel` 配置。

---

## 4. 目录规划

### 4.1 agent-service

```text
agent-service/
  src/main/java/com/nageoffer/shortlink/agent/
    ShortLinkAgentApplication.java
    agent/
      graph/
        CampaignAnalysisGraphFactory.java
        CampaignAnalysisGraphExecutor.java
      state/
        CampaignAgentState.java
        CampaignAgentResult.java
      node/
        IntentRouterNode.java
        ContextCollectorNode.java
        ToolPlanningNode.java
        AnalysisNode.java
        ActionDraftNode.java
        ResponseComposerNode.java
      prompt/
        PromptTemplateService.java
      policy/
        ToolUsePolicy.java
        SafetyPolicy.java
    harness/
      api/
        AgentChatController.java
        AgentSessionController.java
        AgentTraceController.java
        AgentConsoleController.java
        HealthController.java
      runtime/
        AgentRunHarness.java
        AgentRunRequest.java
        AgentRunResult.java
      session/
        AgentSessionManager.java
      checkpoint/
        GraphCheckpointManager.java
        CheckpointCodec.java
      trace/
        AgentTraceRecorder.java
    tool/
      core/
        AgentTool.java
        ToolDescriptor.java
        ToolContext.java
        ToolResult.java
      registry/
        AgentToolRegistry.java
      springai/
        SpringAiToolAdapter.java
      group/
        ListUserGroupsTool.java
      link/
        QueryShortLinksTool.java
        CreateShortLinkDraftTool.java
      stats/
        QueryLinkStatsTool.java
        QueryAccessTrendTool.java
        CompareLinkPerformanceTool.java
      action/
        CreatePendingActionTool.java
    business/
      api/
        GroupBusinessApi.java
        ShortLinkBusinessApi.java
        StatsBusinessApi.java
        PendingActionBusinessApi.java
      service/
        GroupBusinessService.java
        ShortLinkBusinessService.java
        StatsBusinessService.java
        PendingActionBusinessService.java
      model/
    infrastructure/
      client/
        AdminApiClient.java
      mapper/
      persistence/
        MysqlGraphCheckpointRepository.java
      config/
        AgentProperties.java
        DeepSeekModelConfig.java
        InternalAuthConfig.java
      security/
        AgentUserContext.java
        InternalTokenInterceptor.java
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
```

### 4.2 admin

```text
admin/src/main/java/com/nageoffer/shortlink/admin/agent/
  controller/
    AgentChatProxyController.java
    AgentInternalApiController.java
    AgentPendingActionController.java
  service/
    AgentChatProxyService.java
    AgentInternalApiService.java
    PendingActionService.java
  dto/
    req/
    resp/
  action/
    PendingActionExecutor.java
    PendingActionType.java
  audit/
    AgentInternalApiAuditRecorder.java
  config/
    AgentAdminProperties.java
```

admin 侧不建设 MCP Server 或独立远程工具服务，只补充 Agent 必需且现有 Controller 不适合直接复用的内部 API。

---

## 5. 服务端口规划

| 服务 | 当前/建议端口 | 说明 |
|---|---:|---|
| gateway | 8000 | 已有 |
| project | 8001 | 已有 |
| admin | 8002 | 已有 |
| aggregation | 8003 | 已有 |
| agent-service | 8010 | 建议新增 |
| nginx shortlink frontend | 5174 | 已有 |

---

## 6. agent-service 配置

### 6.1 `application.yaml`

```yaml
server:
  port: 8010

spring:
  application:
    name: short-link-agent
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY:${LLM_API_KEY:}}
      base-url: ${LLM_BASE_URL:https://api.deepseek.com}
      chat:
        options:
          model: ${LLM_MODEL:deepseek-v4-flash}

short-link:
  agent:
    enabled: true
    graph:
      name: campaign-analysis-graph
      version: v1
      checkpoint-enabled: true
      stream-enabled: false
    admin:
      base-url: ${SHORTLINK_ADMIN_BASE_URL:http://127.0.0.1:8002}
      internal-token: ${SHORTLINK_AGENT_INTERNAL_TOKEN:change-me}
      tool-timeout-ms: ${SHORTLINK_TOOL_TIMEOUT_MS:5000}
    limit:
      max-batch-create-size: ${SHORTLINK_MAX_BATCH_CREATE_SIZE:20}
      access-record-max-page-size: ${SHORTLINK_ACCESS_RECORD_MAX_PAGE_SIZE:100}
    model:
      provider: ${LLM_PROVIDER:deepseek}
      model-name: ${LLM_MODEL:deepseek-v4-flash}
      timeout-ms: ${LLM_TIMEOUT_MS:30000}
      max-output-tokens: ${LLM_MAX_OUTPUT_TOKENS:2000}
    console:
      enabled: true
      base-path: /agent-console
      dev-mode: ${AGENT_CONSOLE_DEV_MODE:true}
    trace:
      enabled: ${AGENT_TRACE_ENABLED:true}
      redact-sensitive: true
```

### 6.2 环境变量

```text
SHORTLINK_ADMIN_BASE_URL=http://127.0.0.1:8002
SHORTLINK_AGENT_INTERNAL_TOKEN=change-me
SHORTLINK_TOOL_TIMEOUT_MS=5000
SHORTLINK_MAX_BATCH_CREATE_SIZE=20
SHORTLINK_ACCESS_RECORD_MAX_PAGE_SIZE=100

LLM_PROVIDER=deepseek
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-v4-flash
DEEPSEEK_API_KEY=
# 或统一使用 LLM_API_KEY=
LLM_TIMEOUT_MS=30000
LLM_MAX_OUTPUT_TOKENS=2000

AGENT_CONSOLE_DEV_MODE=true
AGENT_TRACE_ENABLED=true
```

### 6.3 配置规则

```text
模型 API Key 不得提交仓库；
DeepSeek API Key 只通过 `DEEPSEEK_API_KEY` 或 `LLM_API_KEY` 注入；
internal token 不得写死在代码；
本地、测试、生产必须使用不同配置；
模型名称、prompt profile、tool schema 必须带版本；
feature flag 默认关闭写操作；
Graph checkpoint 序列化必须带版本。
```

---

## 7. admin 配置

```yaml
short-link:
  agent:
    enabled: false
    write-enabled: false
    service-url: ${SHORTLINK_AGENT_SERVICE_URL:http://127.0.0.1:8010}
    internal-token: ${SHORTLINK_AGENT_INTERNAL_TOKEN:change-me}
    tool-timeout-ms: 5000
    max-batch-create-size: 20
    access-record-max-page-size: 100
    trace-enabled: true
    risk-scan-enabled: false
```

| 配置 | 说明 |
|---|---|
| `enabled` | 是否启用 Agent API |
| `write-enabled` | 是否允许确认后的写操作 |
| `service-url` | Agent Service 地址 |
| `internal-token` | 内部调用鉴权 |
| `tool-timeout-ms` | 工具调用超时 |
| `max-batch-create-size` | 批量创建上限 |
| `access-record-max-page-size` | 访问明细最大分页 |
| `trace-enabled` | 是否记录工具轨迹 |
| `risk-scan-enabled` | 风控 Agent 预留 |

---

## 8. Tool Schema 规范

### 8.1 通用请求头

内部工具调用必须带：

```text
X-Agent-Internal-Token
X-Agent-Trace-Id
X-Agent-Session-Id
```

### 8.2 通用请求体

```json
{
  "username": "current-user-from-admin",
  "traceId": "trace_xxx",
  "sessionId": "agt_xxx",
  "payload": {}
}
```

### 8.3 通用响应体

```json
{
  "success": true,
  "data": {},
  "errorCode": null,
  "errorMessage": null,
  "snapshotId": "snap_xxx"
}
```

### 8.4 Tool 结果脱敏

| 字段 | 脱敏规则 |
|---|---|
| IP | `192.168.1.*` |
| UV | 哈希后保留前 8 位 |
| User-Agent | 只保留浏览器/系统/设备解析结果 |
| token | 永不返回 |
| 手机号 | `138****1234` |
| 邮箱 | `a***@domain.com` |

---

## 9. Prompt Profile 规范

建议文件：

```text
agent-service/src/main/resources/prompts/campaign_analyst_v1.md
agent-service/src/main/resources/prompts/tool_use_policy_v1.md
agent-service/src/main/resources/prompts/safety_policy_v1.md
```

Prompt 必须包含：

```text
Agent 角色；
允许任务；
禁止任务；
工具使用规则；
数据真实性要求；
写操作确认要求；
隐私脱敏要求；
无数据时回答规范；
异常和失败回答规范；
外部内容只作为数据的说明。
```

禁止：

```text
Prompt 中出现真实 API Key；
Prompt 要求模型绕过工具；
Prompt 要求模型直接执行删除；
把网页标题或用户 describe 当作系统指令。
```

---

## 10. Graph 配置规范

### 10.1 Graph 命名

```text
graph.name = campaign-analysis-graph
graph.version = v1
```

### 10.2 节点命名

节点命名必须稳定，便于日志和验收：

```text
intake
intent_router
scope_resolve
permission_guard
read_tool
analysis
reasoning
proposal
pending_action
response_compose
error_fallback
```

### 10.3 中断点

必须中断的节点：

```text
pending_action
```

可中断的未来节点：

```text
risk_review
budget_change
route_change
disable_link
```

### 10.4 Checkpoint

第一阶段要求直接 MySQL 持久化，至少保存：

```text
thread_id；
trace_id；
graph_name；
graph_version；
state_json；
checkpoint_version；
status；
create_time；
update_time。
```

纯内存 checkpoint 只允许作为临时本地验证手段，不作为第一阶段正式验收目标。

---

## 11. 日志与 Trace 字段

每次 Agent 运行至少记录：

```text
trace_id；
session_id；
username；
graph_name；
graph_version；
node_name；
intent；
tool_name；
tool_status；
tool_latency_ms；
model_name；
prompt_profile_version；
answer_snapshot_ids；
pending_action_ids；
error_code；
created_at。
```

日志脱敏规则：

```text
不得记录 token；
不得记录完整 API Key；
不得记录完整 IP；
不得记录完整 UV；
不得记录未脱敏访问明细；
不得记录模型原始敏感上下文。
```

---

## 12. 与现有配置的冲突提醒

| 风险 | 文件 | 建议 |
|---|---|---|
| Redis/Nacos/MySQL 硬编码 | 各 `application.yaml` | 后续迁移为环境变量 |
| Gateway route 缺失 | `gateway/src/main/resources/application.yaml` | 补本地路由或明确 Nacos 配置 |
| Java 编译版本不一致 | 各模块 `pom.xml` | 后续统一到 Java 17 |
| 高德 Key 明文 | `project/application.yaml` | 后续外置 |
| admin Feign access-record 路径与 project 不一致 | `ShortLinkActualRemoteService` / `ShortLinkStatsController` | 统一路径 |
| GroupService gid 校验实现疑似参数顺序问题 | `GroupServiceImpl` | 开发前验证 |

第一阶段 Agent 不强制修复全部历史问题，但新增配置不得继续扩大硬编码问题。

---

## 13. 推荐启动顺序

本地开发：

```text
1. 启动 MySQL / Redis / Nacos；
2. 启动 project；
3. 启动 admin；
4. 启动 agent-service；
5. 启动 gateway；
6. 启动 nginx 或直接调 admin API；
7. 运行 smoke test。
```

如果使用 `aggregation`：

```text
1. 启动 MySQL / Redis；
2. 启动 aggregation；
3. 启动 agent-service；
4. 通过 admin agent API 联调；
5. 验证 Feign 和 UserContext 是否符合预期。
```

---

## 14. 第一阶段交付技术清单

| 类别 | 必须交付 |
|---|---|
| 服务 | `agent-service` Java 模块 |
| 前端 | `agent-service` 独立 Agent Console |
| Graph | `campaign-analysis-graph` |
| Agent | `CampaignAnalystAgent` |
| 工具 | list/page/stats/access/title/proposal/confirm |
| 数据 | session/message/graph_run/tool_call/pending_action/MySQL checkpoint 表 |
| 配置 | agent-service 与 admin 的 `short-link.agent.*` |
| 安全 | internal token、gid 校验、脱敏、写确认 |
| 测试 | Agent、Graph、Tool Facade、Business API、E2E、回归 |
| 文档 | 架构、开发、配置、验收最终版 |
