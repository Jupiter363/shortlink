# Admin 入口 HTTP/Feign 联调与真实闭环验证实施计划

> **For agentic workers:** 本计划承接 `12_E2E联调闭环与工具结果治理计划.md`。本阶段补齐 admin 正式入口的 HTTP 入站与 Feign 出站测试，并尝试本机真实 DeepSeek + MySQL + admin/project 服务闭环验证。真实密钥、token、datasource、本地 YAML 仍然不得提交。

## Goal

补齐正式入口的自动化联调证据：

```text
HTTP /api/short-link/admin/v1/agent/chat
  -> UserTransmitFilter
  -> AgentController
  -> AgentRemoteService

AgentRemoteService Feign
  -> /internal/short-link-agent/v1/chat
  -> X-Agent-* trusted headers
  -> body only contains sessionId/message
```

同时尝试真实本机闭环：

```text
DeepSeek V4 Flash
  + agent-service
  + MySQL checkpoint
  + admin/project internal tools
```

## Scope

### In Scope

```text
新增 admin HTTP 入站 MockMvc 测试；
新增 AgentRemoteService Feign 出站测试；
验证 admin 不信任 body.username；
验证 Feign path/header/body 契约；
记录本机真实 DeepSeek + MySQL + admin/project 闭环验证结果；
提交前运行 admin/agent-service 测试、打包和密钥扫描。
```

### Out of Scope

```text
不提交 application/bootstrap/shardingsphere 本地 YAML；
不提交 DeepSeek API key、AGENT_INTERNAL_TOKEN、数据库密码；
不在测试里连接真实 Redis/Nacos/MySQL/DeepSeek；
不修改 gateway 路由；
不新增写操作 Tool。
```

## Automated Test Design

### 1. Admin HTTP Inbound

新增：

```text
admin/src/test/java/com/nageoffer/shortlink/admin/controller/AgentControllerMvcTest.java
```

覆盖：

```text
POST /api/short-link/admin/v1/agent/chat 经过 UserTransmitFilter；
header username/userId/realName 写入 UserContext；
body 中夹带 username=spoofed-user 时不进入 AgentRemoteService；
AgentRemoteService.chat 收到 X-Agent-Username 对应的 trusted-user；
请求完成后 UserContext 被清理；
缺少 gateway 注入 username 时返回 A000001。
```

### 2. AgentRemoteService Feign Outbound

新增：

```text
admin/src/test/java/com/nageoffer/shortlink/admin/remote/AgentRemoteServiceFeignTest.java
```

测试使用 JDK `HttpServer` 作为本地 stub，不引入额外依赖。Spring Boot 测试上下文只启用 Feign 相关能力，并排除 Redis/Redisson/DataSource 自动配置，避免出站协议测试依赖本机中间件状态。

覆盖：

```text
chat -> POST /internal/short-link-agent/v1/chat；
health -> GET /internal/short-link-agent/v1/health；
请求头携带 X-Agent-Internal-Token、X-Agent-Username、X-Agent-UserId、X-Agent-RealName；
chat body 只包含 sessionId/message，不包含 username；
Feign 能正确解码 Result<Object>。
```

## Manual Real Integration

### Environment Probe Result

2026-07-08 首轮本机检查结果：

```text
DEEPSEEK_API_KEY=UNSET
AGENT_INTERNAL_TOKEN=UNSET
SHORT_LINK_BUSINESS_BASE_URL=UNSET
AGENT_DATASOURCE_URL=UNSET
AGENT_DATASOURCE_USERNAME=UNSET
AGENT_DATASOURCE_PASSWORD=UNSET

admin/src/main/resources/application.yaml=EXISTS
agent-service/src/main/resources/application.yaml=EXISTS
project/src/main/resources/application.yaml=EXISTS
gateway/src/main/resources/application.yaml=EXISTS
aggregation/src/main/resources/application.yaml=EXISTS

127.0.0.1:8000=OPEN
127.0.0.1:8002=CLOSED
127.0.0.1:8010=CLOSED before manual start
127.0.0.1:3306=OPEN
127.0.0.1:6379=CLOSED
127.0.0.1:8848=CLOSED
```

### First Smoke Attempt

临时启动：

```powershell
java -jar agent-service/target/shortlink-agent-service.jar
```

启动结果：

```text
agent-service started on 127.0.0.1:8010
```

请求：

```http
POST http://127.0.0.1:8010/internal/short-link-agent/v1/chat
Content-Type: application/json

{
  "sessionId": "manual-real-deepseek-1",
  "username": "local-user",
  "message": "请用一句中文回复：agent-service real llm checkpoint smoke test"
}
```

响应摘要：

```text
success=true
traceNodes=intake,tool_planning,llm_analysis,response_compose,checkpoint_save
checkpointStatus=failed
warnings=DeepSeek API key not configured|Graph checkpoint save failed
```

服务日志定位：

```text
checkpoint 失败原因：MySQL JDBC 报 Public Key Retrieval is not allowed。
DeepSeek 未真实调用原因：运行时 DEEPSEEK_API_KEY 未设置，agent-service deepseek.api-key 使用环境变量占位。
```

### Environment Fixes Applied

```text
Redis：临时 Docker 容器映射到 127.0.0.1:6379，验证结束后停止。
project：通过 Java argfile 启动到 127.0.0.1:8001，关闭 Nacos discovery，使用本地 Redis。
admin：启动到 127.0.0.1:8002，aggregation.remote-url 指向 http://127.0.0.1:8001。
agent-service：启动到 127.0.0.1:8010，SHORT_LINK_BUSINESS_BASE_URL 指向 http://127.0.0.1:8002。
DeepSeek：API key 仅注入临时进程环境，不写入仓库文件。
MySQL checkpoint：AGENT_DATASOURCE_URL 追加 allowPublicKeyRetrieval=true，并使用本地忽略配置中的 MySQL 账号。
Internal token：admin 与 agent-service 临时使用同一个本地 token，不写入仓库文件。
```

### Admin/Project Tool Probe

```text
GET /internal/short-link-admin/v1/agent-tools/groups
  user=Jupiter1
  result=success
  groupCount=6

GET /internal/short-link-admin/v1/agent-tools/short-links/page
  gid=Q70DpK
  result=success
  recordCount=21

GET /internal/short-link-admin/v1/agent-tools/group/stats
  gid=Q70DpK
  startDate=2024-01-01
  endDate=2026-12-31
  result=success
  pv=35
  uv=13
  uip=1

GET /internal/short-link-admin/v1/agent-tools/group/access-records
  gid=Q70DpK
  startDate=2024-01-01
  endDate=2026-12-31
  result=success
  recordCount=35
```

> Access record rows/rawData 未写入计划文档，避免把本地访问明细扩散到仓库历史。

### Final Admin Entry E2E

正式入口：

```http
POST http://127.0.0.1:8002/api/short-link/admin/v1/agent/chat
username: Jupiter1
userId: 10001
realName: Jupiter
Content-Type: application/json

{
  "sessionId": "manual-admin-e2e-q70-final",
  "message": "show groups and page short links and stats and access records for gid=Q70DpK startDate=2024-01-01 endDate=2026-12-31 current=1 size=3 Please analyze campaign performance and explain anomalies in Chinese."
}
```

响应摘要：

```text
warnings=
traceEvents=success>success>success>success>success
toolCalls=list_groups:true|page_short_links:true|get_group_stats:true|get_group_access_records:true
cards=group_summary|short_link_page|stats_summary|access_records|traffic_anomaly|performance_insight|performance_insight|performance_insight|performance_insight
graphDataSource=campaign-analysis-graph
llmDataSource.model=deepseek-v4-flash
llmDataSource.finishReason=stop
toolDataSource.executionCount=4
```

MySQL checkpoint 验证：

```text
database=shortlink_agent
table=t_agent_graph_checkpoint
thread_id=manual-admin-e2e-q70-final
graph_name=campaign-analysis-graph
graph_version=v1
status=FINISHED
```

## Implementation Tasks

### Task 1: Admin HTTP Inbound Test

**Files:**

```text
admin/src/test/java/com/nageoffer/shortlink/admin/controller/AgentControllerMvcTest.java
```

**TDD acceptance:**

```text
先新增测试；
RED 初次暴露 Mockito captor matcher 写法问题；
修复测试后 GREEN；
不需要生产代码改动。
```

### Task 2: AgentRemoteService Feign Test

**Files:**

```text
admin/src/test/java/com/nageoffer/shortlink/admin/remote/AgentRemoteServiceFeignTest.java
```

**TDD acceptance:**

```text
先新增测试；
RED 初次暴露 Feign 测试上下文拉起 Redis/Redisson 自动配置；
排除无关自动配置后 GREEN；
不需要生产代码改动。
```

### Task 3: Manual Real Integration Attempt

**Steps:**

```text
检查环境变量和本地 YAML 是否存在；
检查 8000/8001/8002/8010/3306/6379/8848 端口；
临时启动 Redis/project/admin/agent-service；
先调用 admin internal tool API 验证 project 穿透；
再调用 admin 正式 agent chat 入口；
验证 DeepSeek 响应、工具执行和 MySQL checkpoint；
停止临时服务与 Redis 容器。
```

## Verification

阶段完成前执行：

```powershell
mvn -pl admin "-Dtest=AgentControllerMvcTest,AgentRemoteServiceFeignTest" test
mvn -pl admin test
mvn -pl agent-service test
mvn -pl admin -DskipTests package
mvn -pl agent-service -DskipTests package
mvn -pl project -DskipTests package
git diff --check
git ls-files | rg "(^|/)(application|bootstrap).*\\.ya?ml$|shardingsphere-config.*\\.ya?ml$|(^|/)target/|nginx-nageoffer|__MACOSX|(^|/)\\.idea/|(^|/)\\.codebuddy/"
rg -n "sk-[A-Za-z0-9]{16,}|DEEPSEEK_API_KEY\\s*[:=]\\s*sk-|AGENT_INTERNAL_TOKEN\\s*[:=]\\s*[A-Za-z0-9_-]{16,}|X-Agent-Internal-Token\\s*[:=]\\s*[A-Za-z0-9_-]{16,}" admin agent-service plan .gitignore pom.xml
```

## Acceptance Criteria

- [x] admin HTTP 入站测试覆盖正式 `/api/short-link/admin/v1/agent/chat`。
- [x] admin HTTP 入站测试验证 body.username 不被信任。
- [x] admin HTTP 入站测试验证缺少可信 username 时失败。
- [x] AgentRemoteService Feign 测试验证 chat path/header/body。
- [x] AgentRemoteService Feign 测试验证 health path/header。
- [x] 真实本机 agent-service 已尝试启动并调用 chat。
- [x] 真实联调初始阻塞项已记录并完成本机绕过：DeepSeek key 注入、MySQL datasource 修正、Redis/project/admin 启动。
- [x] 完整真实 DeepSeek + MySQL checkpoint + admin/project 工具闭环通过。
- [x] 阶段完成后 commit 并 push。

## Discussion Notes

本阶段自动化测试补齐了正式入口协议证据，真实本机闭环也已打通。关键经验是：业务代码链路本身可以成立，但本地 E2E 对运行时环境非常敏感，必须显式控制 Redis、Nacos 绕过、MySQL 账号、agent datasource URL、DeepSeek key 和 internal token。

```text
admin /api/short-link/admin/v1/agent/chat
  -> AgentRemoteService Feign
  -> agent-service Graph
  -> admin internal tool API
  -> project stats/access records
  -> DeepSeek V4 Flash
  -> MySQL checkpoint FINISHED
```

后续如要把该闭环常态化，建议补一个不提交密钥的本地运行脚本模板：

```text
target/e2e-logs/              # 临时日志，已被 target/ 忽略
.env.local                    # 本地密钥和数据库账号，已被 .gitignore 忽略
scripts/local-agent-e2e.ps1   # 仅放占位变量和启动顺序，不写真实 secret
```
