# shortlink

一个基于 Java 17、Spring Boot 与 Spring Cloud 的智能短链接平台。项目覆盖短链接创建、跳转、分组管理、访问统计、回收站与网关治理，并在独立 Agent 服务中扩展了投放分析、安全风控、风险画像和策略拦截能力。

## 核心能力

- 短链接创建、批量创建、更新、分页查询与跳转。
- 用户、分组、回收站和短链接后台管理。
- PV、UV、UIP、地域、设备、网络和访问记录等统计分析。
- Redis、Redisson 与 ShardingSphere 支撑缓存、并发控制和数据分片。
- Spring Cloud Gateway、Nacos 与 Sentinel 支撑服务发现、路由和流量治理。
- 基于 Spring AI Alibaba Graph 的智能投放与分析 Agent。
- 安全风控 Agent、风险画像批处理、审核中心与策略热路径拦截。
- Graph Checkpoint、工具调用、内部鉴权和可观测 Trace 等 Agent Harness 能力。

## 模块

| 模块 | Artifact | 职责 |
|---|---|---|
| `admin` | `shortlink-admin` | 用户、分组、短链管理、统计查询，以及 Agent/风险中心的管理端入口 |
| `project` | `shortlink-project` | 短链接创建与跳转、缓存、数据分片、访问记录和统计计算 |
| `gateway` | `shortlink-gateway` | 统一网关、短链路由、Redis 辅助解析和风险策略热路径拦截 |
| `agent-service` | `shortlink-agent-service` | 投放分析 Agent、安全风控 Agent、风险画像、策略发布与审核闭环 |
| `aggregation` | `shortlink-aggregation` | 聚合 `admin` 与 `project` 的单体部署入口 |

## 运行架构

```text
Client
  |
  v
Gateway :8000
  |--------------------------|
  v                          v
Project :8001             Admin :8002
  |                          |
  |                          v
  |                    Agent Service :8010
  |                          |
  |                    LLM / Graph / Tools
  |
MySQL + Redis + Nacos
```

本地 Agent E2E 脚本会启动 `project`、`admin` 和 `agent-service`。完整微服务运行还需要根据本地环境为 Gateway、Nacos、MySQL、Redis 和各模块提供配置。

## 技术栈

- Java 17
- Spring Boot 3.0.7
- Spring Cloud 2022.0.3
- Spring Cloud Alibaba 2022.0.0.0
- Spring AI Alibaba 1.1.2.3
- MyBatis-Plus 3.5.3.1
- ShardingSphere 5.3.2
- Redis / Redisson
- MySQL
- Nacos / Sentinel / Spring Cloud Gateway / OpenFeign

## 环境要求

- JDK 17+
- Maven 3.8+
- MySQL 8+
- Redis 7+
- Nacos 2.x（运行微服务模式时）
- PowerShell 7+（运行仓库 E2E 脚本时）
- Docker（可选，用于脚本临时启动 Redis）

## 构建

在仓库根目录执行：

```bash
mvn clean package -DskipTests
```

运行全部测试：

```bash
mvn test
```

按模块构建：

```bash
mvn -pl project -DskipTests package
mvn -pl admin -DskipTests package
mvn -pl gateway -DskipTests package
mvn -pl agent-service -DskipTests package
```

## 本地 Agent E2E

仓库提供 `scripts/local-agent-e2e.ps1`，用于构建并启动 `project`、`admin` 与 `agent-service`，随后可执行一次真实 Agent Smoke Test。

必需环境变量：

```powershell
$env:DEEPSEEK_API_KEY = "..."
$env:AGENT_INTERNAL_TOKEN = "..."
$env:AGENT_DATASOURCE_URL = "jdbc:mysql://127.0.0.1:3306/short_link"
$env:AGENT_DATASOURCE_USERNAME = "..."
$env:AGENT_DATASOURCE_PASSWORD = "..."
```

执行启动与 Smoke Test：

```powershell
.\scripts\local-agent-e2e.ps1 -StartRedis -RunSmoke -KeepRunning `
  -Username "demo" `
  -UserId "1" `
  -RealName "Demo User" `
  -Gid "default"
```

脚本默认使用以下端口：

| 服务 | 端口 |
|---|---:|
| Gateway | 8000 |
| Project | 8001 |
| Admin | 8002 |
| Agent Service | 8010 |
| Redis | 6379 |

风险画像与策略拦截链路可通过以下脚本验证：

```powershell
.\scripts\risk-profile-policy-e2e.ps1
```

## 配置与安全

运行时配置文件、数据库凭据、LLM API Key 和内部调用 Token 不应提交到仓库。项目 `.gitignore` 已排除 `application*.yml`、`bootstrap*.yml`、ShardingSphere 本地配置和 `.env*` 文件。

生产或共享环境中应通过配置中心、环境变量或密钥管理系统注入：

- MySQL / Redis / Nacos 连接信息
- `DEEPSEEK_API_KEY`
- `AGENT_INTERNAL_TOKEN`
- Agent 数据源与模型配置
- Gateway 风险策略配置

## 进一步阅读

- [智能投放与分析 Agent 文档索引](plan/智能投放与分析Agent/00_计划文档索引.md)
- [安全风控 Agent 文档索引](plan/安全风控Agent/00_计划文档索引.md)
- [Agent 平台增强文档索引](plan/Agent平台增强/00_计划文档索引.md)
- [本地 Agent E2E 脚本](scripts/local-agent-e2e.ps1)
- [风险画像与策略拦截 E2E 脚本](scripts/risk-profile-policy-e2e.ps1)

## License

当前仓库未声明开源许可证。除非仓库后续补充 LICENSE，否则代码使用、分发和衍生应以仓库所有者授权为准。
