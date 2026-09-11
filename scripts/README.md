# 脚本导航

这里按用途保留现有脚本位置。三个 Maven 集成入口使用 `:artifactId` 和 `-am` 从仓库根的当前 reactor 构建依赖；生产 JAR 组件、E2E 和压测启动器从 `services/<模块>/target/` 读取制品，保留原服务名与 JAR 文件名。

本目录现有 **57 个脚本文件**：根目录 2、`integration` 12、`e2e` 6、`performance` 37；本导航不计入这 57 个文件。测试、夹具和被其他脚本加载的模块也计入，不能把它们全部当作独立执行入口。

| 分类 | 内容 | 主要输出 |
| --- | --- | --- |
| [integration/](integration/) | 3 个 Java 集成入口、监控检查、生产 JAR 组件检查、适配器工具、6 个 APISIX 组件工具 | `.work/verification/`、`.work/component-results/` 或工具专属目录 |
| [e2e/](e2e/) | 真实创建 / 跳转监督进程、业务用例、网关校验、网关用例、Admin 公网入站校验、HEAD 事件核对 | `.work/e2e/<runId>/`；私有凭据另存于受限目录 |
| [performance/](performance/) | 12 个准备 / 观测 / 执行工具与辅助模块，25 个 `test_*` 文件 | `.work/performance/<runId>/`；原始私有 fixture 不作为公开报告 |
| 根目录历史脚本 | 已停用的旧启动器与策略联调脚本 | 不作为当前拓扑验收依据 |

项目部署前提见[部署说明](../deploy/README.md)，验收范围与历史结果见[项目 README](../README.md)和[压测报告目录](../doc/压测报告/README.md)。以下资源说明用于选择正确入口，不意味着运行这些脚本已获授权。

## Java 集成入口

三个 `.ps1` 入口面向 Windows PowerShell / PowerShell on Windows，要求 Java 17、Maven，以及已准备好的专用测试依赖。`-JavaHome` 可显式指定 Windows JDK，也可使用 `JAVA_HOME`；Admin / Redirect 入口会在设置环境和写日志前检查非空值及 `bin/java.exe` 存在，但不代替 Java 版本核对。

脚本依据 `$PSScriptRoot/../..` 定位仓库根，不要求调用者当前工作目录为仓库根。使用 `-pl :artifactId -am -Pintegration verify` 会构建选定模块及其依赖，并执行相应测试；它不是只读检查，也不会替你安全地准备所有数据库。

| 入口 | 前置条件与隔离 | 副作用及输出 |
| --- | --- | --- |
| [run-business-it.ps1](integration/run-business-it.ps1) | Java 17；显式 `-AllowReset`；专用 MySQL 用户 / 密码和 MinIO 凭据；MySQL 端口仅 3306 / 13306；固定 `shortlink_business_it`、`shortlink_batch_it`、`shortlink_id_test_v07` 测试库；MinIO 为本机 19000 | Command 及 reactor 依赖测试，可重置指定测试表、写入业务 / 发号 / 导入夹具及对象；`.work/verification/business-<时间>.log` |
| [run-account-agent-it.ps1](integration/run-account-agent-it.ps1) | Java 17；可用 `-Maven` 指定 Maven 命令；显式 `-AllowReset` 与测试密码；MySQL 仅 3306 / 13306；Redis 默认 16379，拒绝 6379；使用固定 account / agent / admin-sharding 测试库 | 先 Agent、后 Admin，各自携带 `-am`；测试可重置专用表和写测试状态；`.work/verification/agent-rerun.log`、`admin-rerun.log` 会覆盖同名日志 |
| [run-admin-redirect-it.ps1](integration/run-admin-redirect-it.ps1) | Java 17；显式 `-AllowReset`、`-TestUser` / `-TestPassword`；隔离 Redis、Kafka 19092 与 MySQL；Maven 在 PATH 可用。Redirect JDBC 用例需要 root 密码，可另设 `-MySqlRootPassword`，未设置时使用测试密码 | Admin / Redirect 及 reactor 依赖验证，包含账号库、Admin 分表和 Redirect 临时库；`.work/component-results/admin-redirect-it-<时间>.log`；会修改专用测试数据 |

业务入口保持 `SHORTLINK_TEST_DB_USER/PASSWORD`、`SHORTLINK_TEST_MINIO_ACCESS/SECRET` 的环境默认值；账号入口保持 `SHORTLINK_IT_PASSWORD` 默认值。不要将现有生产库或共享业务实例冒充固定测试库。

例如，已准备并授权专用依赖后，可以从仓库根调用：

```powershell
./scripts/integration/run-business-it.ps1 -JavaHome $env:JAVA_HOME -AllowReset
./scripts/integration/run-account-agent-it.ps1 -JavaHome $env:JAVA_HOME -AllowReset
./scripts/integration/run-admin-redirect-it.ps1 -JavaHome $env:JAVA_HOME -AllowReset
```

这些是独立入口，按需要选择；不要因为示例相邻而连续运行全部测试。脚本可能改变当前 PowerShell 会话的环境变量 / 工作目录，需要保留原会话时使用独立 PowerShell 进程。

`run-admin-redirect-it.ps1` 支持 `-MySqlPort`、`-RedisPort` 和 `-KafkaBootstrap` 指定隔离端口，仍须显式提供测试凭据与 `-AllowReset`；结束时恢复本脚本修改的环境变量及工作目录。

## 组件与部署验证

| 入口 | 环境、前提和资源影响 |
| --- | --- |
| [component_adapters.py](integration/component_adapters.py) | Windows Python，通过已有 WSL 测试环境调用 Docker；`init/topics/apisix/connect` 各有不同副作用，包括建表、建主题、发事件、配置适配器及 HTTP 请求；结果在 `.work/component-results/` |
| [production_jar_components.py](integration/production_jar_components.py) | Windows Python、已构建的真实 Admin / Redirect JAR、显式 `SHORTLINK_IT_BUSINESS_DB_URL`、`SHORTLINK_IT_DB_USERNAME`、`SHORTLINK_IT_DB_PASSWORD` 及隔离依赖；JDK 优先 `SHORTLINK_IT_JAVA_HOME`，未设置或为空时回退 `JAVA_HOME`，提前拒绝非 Windows、缺少 JDK 配置或 `bin/java.exe`。需 Java 17，路径检查不执行版本探测；会启动 Java、轮询健康 / 指标并停止自己启动的进程；Admin 使用 18002/18102，Redirect 使用 18003/18103。未指定 `SHORTLINK_IT_COMMAND_URL` 时启动本机临时 Command readiness 适配器，只校验内部 token 和就绪请求，拒绝业务路径；Agent / Analytics 不提供业务实现。此入口不能替代业务 E2E；输出 `.work/component-results/` |
| [validate-monitoring.ps1](integration/validate-monitoring.ps1) | Windows PowerShell + 既有 `shortlink-refactor-it` WSL / Docker；启动一次 promtool 容器读取 `deploy/monitoring`；镜像须按环境约束预先准备；输出 `.work/component-results/promtool-<时间>.log` |
| [apisix_tls_component.py](integration/apisix_tls_component.py)、[apisix_etcd_component.py](integration/apisix_etcd_component.py) | Windows Python / WSL 组件路径，依赖专用拓扑及 TLS / etcd 测试前提；会生成私有配置、启动或调整组件并发请求；输出 `.work/apisix-tls-*`、`.work/apisix-etcd-*` 及组件结果 |
| [apisix_limiter_component.py](integration/apisix_limiter_component.py) | `--static-only` 为配置校验；`--run-component` 需要可用 Linux Docker 及已缓存镜像，会创建专属网络 / 容器并发限流请求；输出 `.work/apisix-limiter-*` |
| [apisix_sender_component.py](integration/apisix_sender_component.py)、[apisix_sender_four_component.py](integration/apisix_sender_four_component.py)、[apisix_sender_batch_component.py](integration/apisix_sender_batch_component.py) | Linux Docker 组件检查；明确区分 `--static-only` 与 `--run-component`。真实模式会创建专属 Kafka / APISIX 资源，发送有限事件，暂停 / 恢复或停止 broker，并清理自建资源；输出 `.work/gateway-send-v1/component/` |

APISIX 组件的隔离、镜像和有限请求要求以各工具为准。不要对既有业务容器执行其故障场景，也不要通过批量 import 所有 Python 文件来检查语法：部分组件脚本的顶层代码会解析参数、读配置或启动进程。

## 创建与跳转 E2E

| 文件 | 用途与边界 |
| --- | --- |
| [run_create_redirect_e2e.py](e2e/run_create_redirect_e2e.py) | Linux 专用测试环境的监督入口；需要已构建 Command / Admin / Redirect JAR、APISIX 与隔离依赖；显式 `--allow-test-database` 后创建独特测试 schema / 用户、选择空 Redis DB、启动应用及 APISIX，生成 state 与私有配置 |
| [shortlink_create_redirect_cases.py](e2e/shortlink_create_redirect_cases.py) | 使用真实 API 注册、创建、更新和访问测试短链；生成业务夹具及请求事件，不访问外部目标网站来冒充跳转吞吐 |
| [verify_gateway_e2e.py](e2e/verify_gateway_e2e.py) | 基于已有 state 校验入口，包含对本轮生成的 APISIX 配置调整 / reload 及请求；不是只读指标查询 |
| [gateway_security_cases.py](e2e/gateway_security_cases.py) | APISIX 可信头 / 限流及 Admin 64 在途接入用例，被验证入口调用；需与对应测试 state / 运行配置一致 |
| [admin_ingress_cases.py](e2e/admin_ingress_cases.py) | 读取 READY state，经 APISIX 注册 / 登录两个独特账号，验证会话参数一致性、跨参数注销拒绝和注销生效；单连接慢滴、超大 Content-Length、gzip 请求均保持请求体未发送完成，检查 408/413/415 及后续公开查询恢复。每项有总时限，不输出凭据；结果为 `admin-ingress.json`，测试账号留在本轮隔离 schema |
| [verify_head_events.py](e2e/verify_head_events.py) | 已有专用 WSL 环境中的只读 HEAD 事件验收：查询 Redirect 质量接口与 Kafka offsets / records，不发业务请求、不提交消费位点；不证明 Flink / ClickHouse 已完成聚合 |

E2E 证据按 runId 保存于 `.work/e2e/`。当前入口不包含完整 Agent / LLM E2E；管理、鉴权和事件验收不能互相替代。

现行入口为 APISIX → Admin:8002 和 APISIX → Redirect:8003，Command 在 8001；三个管理端口分别为 8101/8102/8103。`verify_gateway_e2e.py` 需要 PyYAML，只修改工作副本里指定 route ID 的限流参数，并保留其他插件与上游，结束时恢复原始配置文本。可信来源拒绝和 64 在途请求探针针对 Admin 业务端口，健康端口不经过外部入站边界。

监督入口支持已启动的独立资源：`--mysql-container`、`--redis-container`、`--kafka-container`、`--network`、`--mysql-port`、`--redis-port`、`--kafka-bootstrap`、`--kafka-host`、`--object-endpoint`。MySQL root 密码由 `SHORTLINK_E2E_MYSQL_ROOT_PASSWORD` 提供；MinIO 凭据由 `SHORTLINK_E2E_IMPORT_ACCESS_KEY` / `SHORTLINK_E2E_IMPORT_SECRET_KEY` 提供。默认值仅适用已有专用测试清单。每轮创建新 schema，只选择空 Redis DB，不清空既有库；调用者负责准备 Kafka 主题和导入 bucket。

实际依赖身份写入 state.json；HEAD 事件核对默认使用 state 的 Kafka 容器和容器内 bootstrap，旧 state 缺字段时需显式指定。监督入口停止本轮 Java / APISIX，保留既有依赖容器。

创建数据前，监督入口检查 MySQL / Redis 容器正在运行，且其发布到 `127.0.0.1` 的端口与 Java 使用的端口一致；实例不一致立即拒绝。清理后需确认自建 APISIX 容器已不存在，无法确认时记录 `CLEANUP_FAILED`，不会报告成功停止。

真实监督进程 READY 后，可运行 `python3 -B scripts/e2e/admin_ingress_cases.py .work/e2e/<runId>/state.json`。慢滴用例每 0.4 秒发送一个字节，以持续活跃的未完成上传检查 Admin 约 5 秒读体期限；请求经过 APISIX 管理路由的 `proxy-control.request_buffering=false`。它是有限功能检查，不测并发吞吐。

## 压测工具与纯回归

`performance` 的 12 个工具与辅助模块保留同目录：

| 文件 | 职责与副作用 |
| --- | --- |
| [install_k6.py](performance/install_k6.py) | 获取并校验固定 k6 工具，涉及下载及工具目录写入；不是测试或启动服务的前提检查替代品 |
| [supervisor.py](performance/supervisor.py) | 专用 Linux / WSL 环境的启动与清理监督；准备测试库、选择空 Redis DB、创建 APISIX 和启动 3 个 Java 服务，输出 `.work/performance/<runId>/state.json` |
| [kafka_probe_profile.py](performance/kafka_probe_profile.py) | 读取专用 Kafka 容器的实际健康探针配置，拒绝未知 / 超预算 profile；不执行 inspect 返回的任意命令，也不发业务请求 |
| [prepare_fixtures.py](performance/prepare_fixtures.py) | 在 READY state 上通过真实注册 / 创建 API 写有界夹具，私有凭据与 fixture 在 `/var/lib/shortlink-perf/<runId>/`，脱敏摘要在本轮输出目录 |
| [verify_id_boundary.py](performance/verify_id_boundary.py) | 顺序执行真实批量创建以证明 Current / Next 号段跨界，需要 `--exclusive-writer`，会写业务行；不是 QPS 测量 |
| [verify_edge_path.py](performance/verify_edge_path.py) | 使用已有运行身份从 APISIX 网络入口发有限请求，校验真实路径及返回值；不能在未知服务上套用旧 state |
| [observe.py](performance/observe.py) | 读取现有进程、数据库、网关和服务指标并生成观测；需要专用环境和受限凭据 |
| [evidence.py](performance/evidence.py) | `freeze/boundary/refresh` 生成来源、运行和边界证据；SQL 为只读，refresh 会更新本轮私有 fixture 的真实版本，freeze 会写本轮状态 / 清单 |
| [run_stage.py](performance/run_stage.py)、[run_wave.py](performance/run_wave.py)、[workload.js](performance/workload.js) | 使用既有运行状态与 k6 执行单档 / 有界波次；创建类场景会写业务数据，跳转也产生 Kafka 事件；正式窗、排空、错误和资源门禁分别验收 |
| [summarize.py](performance/summarize.py) | 后处理已有阶段文件形成汇总，不通过修改原 FAIL 生成通过结论 |

另外 25 个 `test_*` 文件包含 Python 单元测试、Node 工作负载测试，以及生成 / 执行 Lua 用例的工具。[test_repository_jar_paths.py](performance/test_repository_jar_paths.py) 在独立目录运行真实入口逻辑、捕获 JAR 命令和状态哈希，全部进程 / SQL / HTTP / Docker 适配器均替换为离线假实现。[test_single_gateway_topology.py](performance/test_single_gateway_topology.py) 校验限流工作副本、Admin 直连与接入探针、就绪适配器和历史记录兼容；[test_admin_ingress_cases.py](performance/test_admin_ingress_cases.py) 用离线网络与时钟验证公网安全脚本的期限、连接清理和证据脱敏。其他 `test_` 文件名不等于无资源副作用：先确认具体入口及参数；LuaJIT 或容器执行应有独立环境与资源授权。不要无差别执行所有 Python 文件或把冻结的历史原生发生器替换成当前 k6 工具。

脚本内部使用同目录导入、`parents[2]` 根定位和相对 source SHA 清单。此轮不再拆分 `performance` 子层级；运行时需保留整棵仓库及相应脚本，而非只复制 Java 服务目录。

## 历史脚本与证据

- [local-agent-e2e.ps1](local-agent-e2e.ps1) 已在入口无条件 `throw`，直接拒绝执行旧 Project / Redis 统计拓扑。后面的旧构建代码仅作为历史保留，不能移除拒绝语句后用于新 schema。
- [risk-profile-policy-e2e.ps1](risk-profile-policy-e2e.ps1) 已在入口无条件拒绝执行，保留旧策略链路代码作为历史参考；旧身份协议不适用于 APISIX → Admin，不能作为当前 Agent 验收入口。
- `.work` 和 `doc/压测报告/` 中的历史结果、FAIL、运行配置、source SHA、JAR SHA、fixture 和 Kafka 边界保持原样。目录迁移用新的路径映射记录解释旧位置，不批量替换原 JSON 或追改已有验收。
- 活跃模块 / JAR 路径变动时更新当前启动器及导航。本轮移除 Java Gateway 后，现行清单为 Command / Admin / Redirect；APISIX 的 gateway 场景名、主题和统计指标保持。汇总器保留历史四 JVM 记录，当前执行器拒绝使用旧四服务 state。
