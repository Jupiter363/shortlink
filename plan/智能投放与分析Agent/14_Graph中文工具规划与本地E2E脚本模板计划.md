# Graph 中文工具规划与本地 E2E 脚本模板实施计划

> **For agentic workers:** 本计划承接 `13_admin入口HTTP-Feign联调与真实闭环验证计划.md`。上一阶段真实闭环已经打通，本阶段把闭环中暴露出的两个工程化问题固化：Graph 对中文业务表达的工具规划能力不足，以及本地 E2E 启动流程缺少可复用脚本模板。

## Goal

补齐第一阶段 Agent 的可用性与可运维性：

```text
中文自然表达
  -> Graph tool_planning
  -> list_groups/page_short_links/get_group_stats/get_group_access_records
  -> 不需要用户记英文触发词

本地 E2E
  -> scripts/local-agent-e2e.ps1
  -> project/admin/agent-service/Redis 可按顺序拉起
  -> smoke 请求走正式 admin Agent 入口
  -> 不写入真实 key/token/password
```

## Scope

### In Scope

```text
增强 Graph 规则规划器的中文触发词；
修复 key=value 参数值尾部句号导致 size/current 解析失败的问题；
新增 TDD 回归测试覆盖中文组合工具规划和 size=3. 容错；
新增本地 E2E PowerShell 脚本模板；
更新计划索引；
提交前运行 agent-service/admin 测试、打包、脚本 help、配置与密钥扫描。
```

### Out of Scope

```text
不引入 LLM 动态 tool planner；
不改写 Graph 架构；
不提交 .env.local、application.yaml、shardingsphere-config.yaml 或任何真实 secret；
不自动创建或修改本机 MySQL 数据；
不把本地访问记录 rows/rawData 写入文档。
```

## Design

### 1. Graph 中文工具规划

当前 Graph 第一阶段仍采用轻量规则规划器。为了保持可控和低风险，本阶段继续沿用规则方式，仅补齐高频中文触发词：

```text
list_groups:
  列出分组 / 查看分组 / 查询分组 / 分组列表 / 我的分组

page_short_links:
  分页查看短链 / 分页查看短链接 / 查看短链 / 查看短链接 / 查询短链 / 查询短链接

get_group_stats:
  继续复用已有 统计 / 分析 / 表现 / 数据

get_group_access_records:
  继续复用已有 访问 / 记录 / 明细
```

实现位置：

```text
agent-service/src/main/java/com/nageoffer/shortlink/agent/agent/graph/DefaultCampaignAnalysisGraphExecutor.java
```

验收测试：

```text
executePlansComposableToolsForChineseCampaignAnalysisRequest
```

### 2. 参数尾部标点容错

真实联调时出现：

```text
size=3.
  -> KEY_VALUE_PATTERN 捕获为 "3."
  -> Tool 参数校验失败
```

本阶段在 `putArgument` 入参阶段统一清洗尾部句末标点：

```text
. 。 ! ！ ? ？
```

该处理在进入 `current/size` 数字解析前执行，也适用于 `gid/fullShortUrl/startDate/endDate` 的句末自然语言标点。

验收测试：

```text
executeTrimsTrailingSentencePunctuationFromNumericArguments
```

### 3. 本地 E2E 脚本模板

新增：

```text
scripts/local-agent-e2e.ps1
```

脚本能力：

```text
-Help          查看使用说明，不要求 secret；
-StartRedis    启动临时 redis:7.2-alpine 容器并映射到 6379；
-SkipBuild     跳过 Maven 打包；
-RunSmoke      通过 admin 正式入口发起 Agent Chat；
-KeepRunning   保持服务运行，便于人工继续调试。
```

敏感配置全部从环境变量读取：

```text
DEEPSEEK_API_KEY
AGENT_INTERNAL_TOKEN
AGENT_DATASOURCE_URL
AGENT_DATASOURCE_USERNAME
AGENT_DATASOURCE_PASSWORD
AGENT_E2E_USERNAME
AGENT_E2E_USER_ID
AGENT_E2E_REAL_NAME
AGENT_E2E_GID
```

脚本内置两个上一阶段验证得到的本机兼容点：

```text
project 用 Java argfile 启动，避免 Windows classpath 过长；
project argfile 中 target/classes 使用相对路径，避免中文 workspace 路径编码导致 ClassNotFound。
```

## Acceptance Criteria

- [x] 中文“列出分组、分页查看短链接、统计数据、访问记录”能规划出四个读工具。
- [x] `size=3.` 能解析为 `3L`，不会导致 page/access 工具参数失败。
- [x] 脚本 `-Help` 模式可运行，不触发 secret 校验和服务启动。
- [x] 脚本不包含真实 DeepSeek key、internal token、数据库账号密码。
- [x] `mvn -pl agent-service test` 通过。
- [x] `mvn -pl admin test` 通过。
- [x] `mvn -pl agent-service -DskipTests package` 通过。
- [x] `mvn -pl admin -DskipTests package` 通过。
- [x] 配置与密钥扫描无命中。
- [x] 阶段完成后 commit 并 push。

## Verification Commands

```powershell
mvn -pl agent-service "-Dtest=DefaultCampaignAnalysisGraphExecutorTest" test
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/local-agent-e2e.ps1 -Help
mvn -pl agent-service test
mvn -pl admin test
mvn -pl agent-service -DskipTests package
mvn -pl admin -DskipTests package
git diff --check
git ls-files | rg "(^|/)(application|bootstrap).*\\.ya?ml$|shardingsphere-config.*\\.ya?ml$|(^|/)target/|nginx-nageoffer|__MACOSX|(^|/)\\.idea/|(^|/)\\.codebuddy/"
rg -n "sk-[A-Za-z0-9]{16,}|DEEPSEEK_API_KEY\\s*[:=]\\s*sk-|AGENT_INTERNAL_TOKEN\\s*[:=]\\s*[A-Za-z0-9_-]{16,}|X-Agent-Internal-Token\\s*[:=]\\s*[A-Za-z0-9_-]{16,}" admin agent-service scripts plan .gitignore pom.xml
```

## Discussion Notes

本阶段仍是第一阶段的稳态增强，不改变“规则规划器 + Java Tool Facade + admin internal API”的总体路线。后续如果中文表达覆盖继续变宽，可以再评估把 planner 抽成独立组件，或者让 Spring AI Alibaba Graph 中新增一个轻量 LLM planning node，但那会带来可解释性、测试稳定性和成本问题，暂不进入本阶段。
