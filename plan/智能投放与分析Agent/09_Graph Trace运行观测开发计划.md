# Graph Trace 运行观测开发计划

> **For agentic workers:** 本计划承接 `08_Agent Console洞察卡片UI开发计划.md`。本阶段复用 Spring AI Alibaba Graph 的节点编排边界，为 `agent-service` 增加节点级运行观测，不改变短链接业务 Tool Facade、不引入 MCP、不重构原项目。

## Goal

为智能投放与分析 Agent 增加可验收的 Graph Trace 闭环：

```text
Graph 节点执行 -> traceEvents 结构化记录 -> AgentRunResult 返回 -> Agent Console timeline 展示 -> MySQL checkpoint 结果可观测
```

第一版目标是联调、演示和排障可用，不做完整 APM，不做实时 streaming UI。

## Framework Decision

本阶段继续复用 Spring AI Alibaba Graph：

```text
StateGraph / CompiledGraph 负责节点编排；
OverAllState 负责节点间状态传递；
RunnableConfig.threadId 继续绑定 session；
traceEvents 作为 Graph state 的普通结构化字段返回。
```

不依赖框架内部日志或私有 APM 接口。原因：

```text
节点 wrapper 可稳定绑定业务节点名；
框架升级时不受内部日志格式影响；
后续需要实时展示时，可再基于 CompiledGraph.stream / GraphResponse 演进；
当前 API 联调阶段只需要请求完成后的 traceEvents。
```

## Scope

### In Scope

```text
AgentRunResult 增加 traceEvents；
Graph 节点边界记录 intake/tool_planning/llm_analysis/response_compose；
checkpoint 保存边界记录 checkpoint_save；
checkpoint 成功事件返回 checkpointVersion；
checkpoint 失败事件返回 failed status，并保留 warning；
Agent Console 增加 Graph Trace timeline；
最终版开发文档和验收清单同步 traceEvents 契约。
```

### Out of Scope

```text
不做 streaming trace；
不接入外部 APM；
不展示未脱敏工具 rawData；
不新增远程 MCP 工具协议；
不改变原短链接业务接口。
```

## Trace Event Contract

```json
{
  "traceId": "trace_xxx",
  "nodeName": "llm_analysis",
  "status": "success",
  "timing": {
    "startEpochMs": 1783420000000,
    "endEpochMs": 1783420000120,
    "durationMs": 120
  },
  "summary": {
    "warningCount": 0
  }
}
```

checkpoint 成功：

```json
{
  "traceId": "trace_xxx",
  "nodeName": "checkpoint_save",
  "status": "success",
  "timing": {
    "durationMs": 8
  },
  "checkpointVersion": 1783420000200
}
```

checkpoint 失败：

```json
{
  "traceId": "trace_xxx",
  "nodeName": "checkpoint_save",
  "status": "failed",
  "timing": {
    "durationMs": 8
  },
  "error": "Graph checkpoint save failed"
}
```

## Implementation Tasks

### Task 1: Backend Contract Tests

文件：

```text
agent-service/src/test/java/com/nageoffer/shortlink/agent/agent/graph/DefaultCampaignAnalysisGraphExecutorTest.java
agent-service/src/test/java/com/nageoffer/shortlink/agent/harness/api/AgentChatControllerTest.java
```

验收：

```text
executeCallsLlmAndReturnsTraceableResult 断言 traceEvents 顺序包含 intake/tool_planning/llm_analysis/response_compose/checkpoint_save；
每个事件包含 traceId/status/timing.durationMs；
checkpoint_save 成功包含 checkpointVersion；
checkpoint 保存失败时最后一个事件为 checkpoint_save failed；
Controller JSON 响应包含 data.traceEvents。
```

### Task 2: Graph Trace Recorder

文件：

```text
agent-service/src/main/java/com/nageoffer/shortlink/agent/agent/graph/DefaultCampaignAnalysisGraphExecutor.java
agent-service/src/main/java/com/nageoffer/shortlink/agent/harness/runtime/AgentRunResult.java
```

实现：

```text
在 addNode 时包 tracedNode(nodeName, state, delegate)；
delegate 返回业务 output 后追加 traceEvents；
traceEvents 写入 OverAllState 普通字段；
toRunResult 将 traceEvents 放入 AgentRunResult；
saveCheckpointOrWarn 追加 checkpoint_save 事件；
fallbackResult 返回 graph_execution failed 事件。
```

### Task 3: Agent Console Timeline

文件：

```text
agent-service/src/main/resources/static/agent-console/index.html
agent-service/src/test/java/com/nageoffer/shortlink/agent/harness/api/AgentConsoleStaticResourceTest.java
```

实现：

```text
新增 graphTracePanel / graphTraceBody；
新增 renderTraceEvents(traceEvents)；
showSummary 消费 payload.traceEvents；
showLoading 和错误 fallback 清空 timeline；
timeline 展示 nodeName/status/durationMs/checkpointVersion/summary/error；
不从 rawData.records 回退渲染访问明细。
```

## Privacy Rules

```text
traceEvents 只放运行观测摘要；
summary 不放完整工具响应；
error 只放安全错误摘要，例如 Graph checkpoint save failed / Graph node execution failed / Graph execution failed；
Graph Trace timeline 不展示完整 ip/user；
完整响应仍只进入 Sanitized data 折叠调试区。
```

## Verification

阶段完成前必须执行：

```powershell
mvn -pl agent-service -Dtest=DefaultCampaignAnalysisGraphExecutorTest test
mvn -pl agent-service -Dtest=AgentChatControllerTest test
mvn -pl agent-service -Dtest=AgentConsoleStaticResourceTest test
mvn -pl agent-service test
mvn -pl agent-service -DskipTests package
node -e "const fs=require('fs'); const vm=require('vm'); const html=fs.readFileSync('agent-service/src/main/resources/static/agent-console/index.html','utf8'); const scripts=[...html.matchAll(/<script>([\\s\\S]*?)<\\/script>/g)].map(m=>m[1]); for (const script of scripts) new vm.Script(script); console.log('scripts parsed:', scripts.length);"
git diff --check
```

安全检查：

```powershell
git ls-files | rg "(^|/)(application|bootstrap).*\\.ya?ml$|shardingsphere-config.*\\.ya?ml$|(^|/)target/|nginx-nageoffer|__MACOSX|(^|/)\\.idea/|(^|/)\\.codebuddy/"
rg -n "sk-[A-Za-z0-9]{16,}|DEEPSEEK_API_KEY\\s*[:=]\\s*sk-" agent-service plan .gitignore pom.xml
```

## Future Evolution

```text
第二版可基于 Spring AI Alibaba Graph stream 输出实时节点事件；
可将 traceEvents 拆为专门的 TraceRecorder 组件；
可增加 trace 查询 API，用于按 sessionId/traceId 回看历史运行；
接入 admin 后，生产入口仍由 admin/Gateway 权限边界控制。
```
