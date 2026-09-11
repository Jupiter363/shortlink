# 开发入口与仓库布局

当前状态：**10 个 Maven 模块已按运行角色归入 `services/`、`libraries/`、`jobs/`**。本指南记录现行开发入口；Java Gateway 已移除，APISIX 直接路由到 Admin / Redirect，职责见[单网关说明](single-gateway.md)。此前目录整理决策与验收要求见[仓库结构整理计划](../plan/仓库结构整理/01-现状分析与整理计划.md)。

## 当前模块与职责

根 `pom.xml` 是统一父 POM 和 reactor 入口，直接聚合 10 个叶子模块；三个分类目录不另设 POM。模块内部沿用 `src/main`、`src/test`、`src/main/resources` 和 `target`。

| 类型 | 当前目录 | 稳定 artifactId | 职责 |
| --- | --- | --- | --- |
| Spring 服务 | `services/admin/` | `shortlink-admin` | 会话与当前账号鉴权、请求预算、管理 API、Agent 入口与统计适配 |
| Spring 服务 | `services/agent-service/` | `shortlink-agent-service` | Harness、Graph、Tool、风险画像与审核 |
| Spring 服务 | `services/shortlink-command/` | `shortlink-command` | 短链写入、任务、策略事实与 Outbox |
| Spring 服务 | `services/shortlink-redirect/` | `shortlink-redirect` | 跳转、路由缓存、策略执行与事件生产 |
| Spring 服务 | `services/shortlink-analytics-api/` | `shortlink-analytics-api` | 统计查询、快照与持久化查询任务 |
| Spring 服务 | `services/analytics-worker/` | `analytics-worker` | 常驻归档、补算、发布与恢复协调 |
| 公共库 | `libraries/event-contract/` | `event-contract` | 共享事件与 sourceCut 契约 |
| 公共库 | `libraries/id-generator/` | `id-generator` | Command 进程内使用的 Leaf Segment 来源适配 |
| 公共库 | `libraries/risk-core/` | `risk-core` | 共享的确定性风控语义 |
| Flink 作业 | `jobs/analytics-flink/` | `analytics-flink` | Kafka / Flink / RocksDB 流式统计作业 |

这是 **6 个 Spring 常驻服务、3 个公共库、1 个 Flink 作业**。APISIX 配置与插件属于 `deploy/apisix/`，不是 Java 模块；发号库不会单独启动 Leaf Server。旧 `project`、`aggregation` 不在当前 reactor 中，本机残留目录不代表仍有对应部署入口。

## 根目录构建

使用 Java 17、Maven 和 UTF-8，在仓库根目录执行。推荐用 `:artifactId` 选择模块，避免把目录名误当作制品名：

```sh
mvn -B -ntp clean test
mvn -B -ntp package -DskipTests
mvn -B -ntp -pl :shortlink-command,:shortlink-redirect -am test
mvn -B -ntp -pl :shortlink-admin,:shortlink-agent-service -am test
```

`-am` 会同时构建所选模块的 reactor 依赖。`package` 不会将公共库安装到本地 Maven 仓库，后续选择相关服务时仍应保留 `-am`。Admin 和 Agent 的目录名、artifactId 与 JAR 命名存在区别，制品路径以实际模块 `target/` 及[部署说明](../../deploy/README.md)为准。

默认测试排除 IT / IntegrationTest、E2E 名称及 `e2e,performance` 标签。组件集成使用显式 `-Pintegration verify`，但必须先准备目标用例的隔离依赖、测试库和环境变量；该 profile 不会自动完成环境配置，也不等于业务 E2E。

## 脚本与运行入口

详细参数、平台和输出位置见[脚本导航](../../scripts/README.md)。以下入口各自有范围，按需执行：

| 用途 | 入口 | 前提与范围 |
| --- | --- | --- |
| Command / 发号 / 批量集成 | [run-business-it.ps1](../../scripts/integration/run-business-it.ps1) | Java 17、隔离 MySQL / MinIO、明确测试凭据；重置限定测试表须显式 `-AllowReset` |
| Admin / Agent 集成 | [run-account-agent-it.ps1](../../scripts/integration/run-account-agent-it.ps1) | 专用测试库、隔离 Redis、Java 17 与显式重置授权 |
| Admin 入口 / Redirect 集成 | [run-admin-redirect-it.ps1](../../scripts/integration/run-admin-redirect-it.ps1) | 匹配脚本约定的隔离 Redis / MySQL / Kafka；Java 路径来自参数或 `JAVA_HOME` |
| 生产 JAR 与组件适配 | [组件指南](../integration/component-adapters.md) | 按具体组件准备环境；部分脚本会启动真实进程或容器 |
| 创建、跳转与网关 E2E | [run_create_redirect_e2e.py](../../scripts/e2e/run_create_redirect_e2e.py) | 专用 Linux、已构建 JAR 和固定隔离容器；会启动三个 Java 服务与 APISIX，不运行 Agent / Analytics |
| 压测准备与监督 | [supervisor.py](../../scripts/performance/supervisor.py) | 独立 Linux 测试环境和资源预算；会准备或启动运行资源，不能当作只读查询命令 |

E2E 的 READY 后步骤、结果核对和 STOP 协议见[创建与跳转验收记录](../plan/生产级重构增强/05-创建跳转E2E验收.md#复现入口)。压测先阅读[计划和归档索引](../压测报告/README.md)，每次运行使用新批次与独立候选，不能覆盖旧 `.work` 证据。

两个根部旧 Agent 脚本保持原路径，但状态不同：

- `scripts/local-agent-e2e.ps1` 已通过启动时直接报错停用。
- `scripts/risk-profile-policy-e2e.ps1` 仍能发出请求，但使用旧身份与网关假设，不是当前重构拓扑的验收入口；保留文件不代表已适配或已停用。

## 文档、配置与运行证据

`doc/` 是文档总层级，入口为[文档总目录](../README.md)。当前指南与历史材料按用途阅读：

| 位置 | 用途与维护方式 |
| --- | --- |
| 根 README、`doc/development/` | 当前项目介绍与开发约定；布局迁移时同步现行路径 |
| `deploy/`、`scripts/` 的使用说明 | 与部署输入、运行工具相邻的当前操作说明，统一从文档导航可达 |
| `doc/analytics/` | 当前统计运行、恢复与查询协议 |
| `doc/plan/` | 方案、分阶段任务与决策上下文；以各文档状态判断是否已实施 |
| `doc/压测报告/`、`doc/integration/` 中的验收批次 | 对应当次源码、制品、配置与结果；保留原始 FAIL、JSON、SHA 和绝对路径，不全局替换历史正文 |
| `doc/images/` | 当前介绍和历史说明使用的图片资源；目录整理不替换选定架构图 |
| `.work/`、各模块 `target/` | 本地候选、原始运行证据和构建产物；不作为公共源码入口或自动纳入 Git |

配置以各模块受版本管理的 `application-production.properties` 和部署模板为准。私有 YAML、`.env`、凭据及本机配置不提交，不为方便搬目录放宽资源排除规则。被忽略的旧配置可能仍有用途，不能因模块退出构建就递归删除本地目录。

历史验收只证明对应版本与拓扑，目录整理的构建、路径和文档检查应生成独立记录；不能把原有 7k 压测或 main 合并测试直接改写成本次布局已通过。

## 现行层级与迁移约定

```text
shortlink/
├── services/       # 上表 6 个 Spring 服务，叶子目录名保持
├── libraries/      # event-contract、id-generator、risk-core
├── jobs/           # analytics-flink
├── deploy/         # 保持现有组件分区
├── scripts/        # 保持 integration / e2e / performance 层级
├── doc/            # 文档总层级
├── pom.xml         # 仍为统一父 POM，直接聚合 10 个叶子模块
└── README.md
```

当前模块位置与根 / 子 POM、JAR 启动器、文件定位和使用文档配套维护。模块坐标、依赖图、Java 包、JAR 文件名、服务参数和历史资料字节保持其既有约定；完整映射与门禁见[整理计划](../plan/仓库结构整理/01-现状分析与整理计划.md#4-推荐目标结构)。历史报告中的旧根模块路径只对应当时版本，不提供旧目录软链接或空壳模块来兼容新构建。

[返回文档总目录](../README.md) · [项目 README](../../README.md) · [首次部署](../../deploy/README.md)
