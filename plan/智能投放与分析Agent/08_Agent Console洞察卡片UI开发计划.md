# Agent Console 洞察卡片 UI Implementation Plan

> **For agentic workers:** 本计划承接 `07_响应展示视图隐私治理开发计划.md`。本阶段只升级 `agent-service` 独立 Agent Console 的展示体验，不修改 Graph 响应 DTO、不新增业务 API、不改变 Tool Facade 调用边界。

## Goal

将 `agent-service` 内置 Agent Console 从“原始 JSON 主视图”升级为可用于演示、联调和分析复盘的洞察 dashboard：

```text
answer 作为主解释区；
cards 按 type 渲染为指标卡、异常卡、洞察卡和工具告警卡；
access_records 单独渲染为访问明细表；
pendingActions 单独渲染为待确认动作预览；
toolCalls、dataSources 和完整响应退居折叠的 Sanitized data 调试区。
```

## Scope

本阶段修改范围：

```text
agent-service/src/main/resources/static/agent-console/index.html
agent-service/src/test/java/com/nageoffer/shortlink/agent/harness/api/AgentConsoleStaticResourceTest.java
plan/智能投放与分析Agent/08_Agent Console洞察卡片UI开发计划.md
正式版开发文档
正式版验收清单
```

本阶段不做：

```text
不修改 AgentRunResult 字段；
不新增前端构建工程；
不引入 admin 前端依赖；
不绕过 admin/Gateway 鉴权；
不在 Console 里直接调用 Tool Facade；
不展示未脱敏的 rawData 作为主界面。
```

## Design

Console 仍由 `agent-service` 静态资源提供，保持单文件 HTML/CSS/JS，便于本地运行和后续嵌入 admin：

```text
/agent-console/index.html
  -> POST /internal/short-link-agent/v1/chat
  -> consume AgentRunResult
  -> render answer/cards/pendingActions/access_records
  -> put sanitized toolCalls/dataSources/full response in details
```

### 主视图区

```text
Insight Dashboard
  Answer panel
  Warning notices
  Cards grid
    stats_summary
    traffic_anomaly
    performance_insight
    tool_warning
    group_summary / short_link_page / tool_result fallback
  Pending Actions panel
  Access records table
```

### 卡片渲染规则

```text
traffic_anomaly:
  默认 warning 视觉级别；
  展示 title/sourceTool/summary.reasonCode/metrics/evidence。

performance_insight:
  默认 info 视觉级别；
  展示 title/sourceTool/summary.reasonCode/metrics/evidence。

stats_summary:
  展示 PV/UV/UIP 等 metrics。

tool_warning:
  展示 warning message，不中断页面渲染。

未知 card:
  使用通用卡片 fallback，展示 title/sourceTool/summary/rows count。
```

### 访问明细规则

`access_records` 不混入洞察卡片网格，单独进入表格：

```text
列：Time、Locale、Device、Browser、OS、Network、IP、Visitor；
分页信息：Rows / Total / Page / Size；
空数据：显示 No access records；
主表只读取 display-safe 的 access_records.rows，不从 rawData.records 回退；
IP/user 的出站脱敏仍由后端 `AgentRunResult` 展示视图负责，前端不恢复任何敏感字段。
```

### 调试数据规则

```text
toolCalls；
dataSources；
full response；
```

以上内容只放在 `details#debugDataDetails` 折叠区，标题为 `Sanitized data`。这意味着调试能力仍然存在，但主界面不再默认铺开大段 JSON。

## Test Strategy

使用静态资源契约测试锁住页面骨架：

```text
AgentConsoleStaticResourceTest
  - 页面能通过 /agent-console/index.html 被服务；
  - 包含 chat endpoint；
  - 包含 Insight Dashboard；
  - 包含 cardsPanel；
  - 包含 pendingActionsPanel；
  - 包含 accessRecordsPanel；
  - 包含 debugDataDetails；
  - 包含 renderCards；
  - 包含 renderPendingActions；
  - 包含 renderAccessRecords；
  - 包含 Sanitized data；
  - 保留 Tool Calls / Data Sources 调试入口。
```

红绿过程：

```text
先让测试期待 dashboard 壳、cardsPanel、accessRecordsPanel、debugDataDetails；
确认旧页面失败；
实现卡片化 dashboard；
再让测试期待 pendingActionsPanel/renderPendingActions；
确认缺口失败；
补 PendingAction 面板；
确认静态资源测试通过。
```

## Acceptance Criteria

- [ ] Console 默认展示 `Insight Dashboard`，不再把 raw JSON 作为主结果区域。
- [ ] Console 可展示 `answer`、`traceId` 和 `warnings`。
- [ ] Console 可按 card type 渲染 `stats_summary`、`traffic_anomaly`、`performance_insight`、`tool_warning`。
- [ ] Console 对未知 card type 有 fallback，不因缺字段中断。
- [ ] Console 可展示 `pendingActions` 预览。
- [ ] Console 可将 `access_records.rows` 渲染为表格，且不从 `rawData.records` 回退。
- [ ] Console 将 `toolCalls`、`dataSources` 和完整响应放入 `Sanitized data` 折叠区。
- [ ] Console 不直接调用 Tool Facade 或 admin 内部接口。
- [ ] 不修改 `AgentRunResult` 字段。
- [ ] `mvn -pl agent-service -Dtest=AgentConsoleStaticResourceTest test` 通过。
- [ ] `mvn -pl agent-service test` 通过。
- [ ] `mvn -pl agent-service -DskipTests package` 通过。
- [ ] 提交前 secret/config/target 检查无命中。
