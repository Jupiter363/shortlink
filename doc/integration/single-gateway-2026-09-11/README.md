# 2026-09-11 APISIX 单网关迁移验收

本次移除独立 Java Gateway，管理流量改为 `APISIX → Admin:8002`，公开短链继续使用 `APISIX → Redirect:8003`。Admin 承接管理会话校验、可信入口与本节点资源保护；Command 仍负责写入业务。设计与配置见[单网关开发说明](../../development/single-gateway.md)。

**本次约定范围的单网关迁移验收已通过，真实三 JAR E2E 与整轮资源清理均已完成。** 验收基于 `8ff6a1e84b0695888fc48f4cfd400ae86f4c034d` 之上的本轮工作树，具体源码及制品身份由收据 SHA256 标识，最终 Git 提交由对应 PR 记录关联。以下结果不构成本次 QPS 或吞吐提升的证据。

## 验收矩阵

| 层次 | 本轮结果 | 实际覆盖与限制 | 收据 |
| --- | --- | --- | --- |
| APISIX 真实 HTTP / TLS 组件 | 21/21 PASS | 官方 APISIX 3.11.0、Nginx 1.27.4 管理/跳转桩、配置渲染和 promtool；管理桩监听 8002。没有真实 Java 会话或 Kafka 交付验收 | [apisix-components.json](apisix-components.json) |
| EDGE logger / execution Lua | 99/99 PASS | 在独立、无网络的官方镜像 LuaJIT 中执行实际 Lua 源码与内存依赖替身；验证计数、排队、攒批、重试和诊断契约，不连接 Kafka | [edge-logger-regression.json](edge-logger-regression.json) |
| Admin 默认单元测试 | 153/153 PASS | 入口、会话授权、资源保护、控制器和内部 Agent Tools 等单元契约；Admin clean 后再次全部通过，重复运行不增加用例数；不能替代 Agent 服务 / LLM E2E | [java-acceptance.json](java-acceptance.json)、[clean-unit-rebuild.json](clean-unit-rebuild.json) |
| risk-core 单元测试 | 101/101 PASS | IP、可信代理、请求上下文、风险评估与哈希等公共库契约 | 同上 |
| Servlet 资源保护集成 | 6/6 PASS | 真实嵌入式 Tomcat 与受控身份夹具；不含 APISIX，生产身份与 Agent 异步恢复另由单元测试覆盖；后续 clean package 未重跑本套件 | [java-acceptance.json](java-acceptance.json) |
| Redis 会话集成 | 5/5 PASS | 真实隔离 Redis、生产会话存储和入站过滤器；MySQL 用户权威查询使用替身。断连测试只控制夹具 TCP 中继，不暂停 Redis 服务；后续 clean package 未重跑本套件 | 同上 |
| Python 离线脚本回归 | 最终去重 172 项 PASS | 初轮 167 项加 5 个新隔离用例；后续 36 次执行只有 5 项为新覆盖。启动器、网络、进程、SQL 等外部动作使用替身 | [scripts-review.json](scripts-review.json)、[script-isolation-review.json](script-isolation-review.json) |
| 语法与脚本导航 | 初轮 49 Python / 5 PowerShell / 41 本地链接 PASS；补充 PowerShell 语法 PASS | 原 AST、Parser、链接和差异检查记录保留，后续隔离修补有独立收据；没有据此启动所有脚本 | 同上 |
| 三 JAR 构建与内容 | PASS | Admin 输出经 Maven clean 清理，再携带默认单元测试 package；Command / Redirect 使用此前已完成的包。三 JAR 仅含生产配置，无旧 flow 类 / Lua 或 Gateway 残留 | [build-artifacts.json](build-artifacts.json) |
| 架构图同步 | PASS | 5600×6120；仅管理区像素变化，现行图为 APISIX → Admin，其他区域与前版一致；只证明图文修改范围 | [architecture-verification.json](architecture-verification.json) |
| 真实创建与跳转 E2E | 16/16 PASS，42 次 HTTP 请求 | APISIX → Admin → Command 真实创建，以及 APISIX → Redirect 真实 302；包含幂等、越权拒绝、版本冲突、缓存失效、回收恢复、到期与 HEAD，不跟随外部 Location | [business.json](business.json) |
| Admin 公网入站 E2E | 9/9 PASS | 同会话参数、跨参数注销拒绝与正确注销；持续慢滴 5.01 秒 408、首字节 413 / 415 及恢复 | [admin-ingress.json](admin-ingress.json) |
| 网关与 Admin 容量边界 | 14/14 PASS | 真实边界、直连拒绝、临时低阈值限流、Admin 64 在途拒绝与恢复；工作配置最终还原 | [gateway-summary.json](gateway-summary.json) |
| HEAD 原始事件核对 | PASS | 捕获位点内匹配独立 HEAD 短链：0 个 click、1 个 REDIRECT HEAD 302；不提交消费位点，不证明统计聚合 | [head-events.json](head-events.json) |
| 真实执行与运行身份 | 4 个验收命令退出码 0，监督器退出码 0 | 实际三 JAR SHA 与构建收据一致；Java / APISIX / Kafka 版本与隔离依赖记录完整，最终运行状态 STOPPED | [finite-execution.json](finite-execution.json)、[runtime.json](runtime.json) |
| 整轮资源清理 | PASS | 本轮 Java、APISIX、4 个依赖容器和网络已回收；11 个端口关闭，dockerd / 专用 WSL 已停止，历史容器未启动或删除 | [resource-cleanup.json](resource-cleanup.json) |

这些是不同层次的测试及检查项，不合并为一个“全系统测试总数”。历史 Java Gateway 测试、既有压测报告和本轮重复运行也不加入新增覆盖数量。

## 入口与传输验证

APISIX 组件最终运行 ID 为 `ffe799822c`。21 项中包含 HTTP/TLS 的 Host、SNI、scheme、request-id、身份清洗、单一真实客户端 XFF，外部 actuator / Agent 内部工具 / 恢复接口拒绝，未知域名和原始路径异常拒绝，以及跳转桩 GET 302、Location、HEAD 与 POST 405。这里的 302 由跳转桩返回，不是真实短链数据解析的 E2E 结果。

管理路由启用 `proxy-control.request_buffering=false` 后，声明 1024 字节、仅发送首字节的请求在约 16 ms 收到 **8002 管理桩**响应，证明 APISIX 没有等完整正文才转发。声明 8 MiB + 1 字节、仅发首字节则立即 413，证明外层体积上限仍生效。这两项只证明 APISIX 层行为；真实 Admin 的慢体超时与 256 KiB 拒绝由后文公网 E2E 单独验证。

TLS / etcd 渲染复用公共 route、plugin 与 worker 预算；etcd bootstrap 正确解析 `ADMIN_UPSTREAM_HOST`。Promtool 3.2.1 检查配置及 4 条告警规则通过，已包含在 21 项中，不另加 4 项。该组件运行创建的 2 个容器和 1 个网络均已删除；真实 E2E 的整轮清理另有独立收据。

Lua 99 项由现行[execution diagnostics 测试入口](../../../scripts/performance/test_edge_execution_diagnostics.py)的 `logger` 套件执行，已包含其继承的 logger、并发、batch、linger 用例，不再把这些子套件重复相加。此次没有改变 Redirect 路由的 Kafka 发送组织或扩大消息预算。

## Java 验收与初次失败处理

Java 收据运行环境为 Linux amd64、Ubuntu OpenJDK **17.0.20**。收据分别冻结每个测试类的数量、报告路径 / SHA256，以及相关源码 SHA256；`target` 下原始报告路径用于本地核对，不承诺这些构建输出在 Git 中存在。归档只保留结构化结果，没有复制包含完整环境的 Maven 原始日志。

归档复制前已核对 Java 收据关联的 57 个源码 / 报告文件，SHA256 全部一致。随后主线在正式打包前发现增量 `target` 残留已删除的类与 Lua，停止接受该增量 Admin 制品，并用 Maven `clean` 清理 Admin 输出，再以 `-pl :shortlink-admin -am package` 执行默认单元测试及打包。此次 **Admin 153 + risk-core 101 = 254 次重复验证全部通过**，退出码为 0，见[clean 重建收据](clean-unit-rebuild.json)；没有新增 254 个测试，也没有重跑前述 Tomcat / Redis IT。冻结的原 Java 收据不改写。

[制品收据](build-artifacts.json)记录 Command、Admin、Redirect 三 JAR 的完整 SHA256、大小、生产配置检查，以及旧 flow 类 / Lua / Gateway 残留为零。仅 Admin 输出经过本轮 Maven clean；Command / Redirect 的 package 已在停止旧增量 Admin 构建前完成，不能表述为“三模块全 clean”。`initialIncrementalAdminArtifactAccepted=false` 明确拒绝旧增量 Admin 包；制品静态检查通过不等于真实运行 E2E 已通过。

首次完整入口返回非零：Admin 153、risk-core 101、Tomcat 6 已通过，Redis 测试夹具尝试 spy final 类导致初始化失败。夹具改为委托真实会话存储的 mock 后，Redis 首次重跑为 4/5：第一条冷 Lettuce 连接在写入测试会话前出现 `ClosedChannelException`，后续连接及实际断连恢复用例通过。

最终只修复 Redis **测试夹具准备**：种子数据写入前增加最多 15 秒的 PING 就绪轮询，每次仍使用产品配置的 **150 ms 命令等待、1 秒连接超时**。没有扩大产品超时，没有删除断连 / 503 / 恢复断言。最终 Redis 5/5 通过；这 5 项不与失败轮次相加。更早的 Windows 离线仓库来源不匹配、初版单元夹具错误也保留在收据中，不改写为通过。

生产会话 JSON 已拒绝合法对象后拼接 `null`、第二个对象和垃圾文本；真实 Redis 用例包含这些损坏值。这说明解析失败时拒绝授权，不表示外部用户能直接写入 Redis。

可从仓库根在 Java 17 与已准备的专用 Redis 上复现同范围。重新复现应先清理构建输出，避免已删除的类或资源残留。以下使用稳定 artifactId，与收据中的 `-pl services/admin` 等价；`-o` 和本地 Maven 仓库路径按已缓存依赖的环境选择：

```bash
export SHORTLINK_REDIS_TEST_PORT=26379
mvn -pl :shortlink-admin -am \
  -Dsurefire.reportNameSuffix=single-gateway-final \
  -Dfailsafe.reportNameSuffix=single-gateway-final \
  -Dit.test=AdminSessionRedisIT,AdminServletBudgetIntegrationTest \
  -Dfailsafe.failIfNoSpecifiedTests=false \
  clean test org.apache.maven.plugins:maven-failsafe-plugin:3.2.5:integration-test \
  org.apache.maven.plugins:maven-failsafe-plugin:3.2.5:verify
```

当时最终仅重跑 Redis 的入口为 `test-compile` 加相同 Failsafe goals、`-Dit.test=AdminSessionRedisIT`。三次相关 shell 的退出码为 **1 → 1 → 0**，完整命令来源与 SHA 在 Java 收据 `commands` 中。上述范围没有运行旧 Account / MySQL 集成套件，也不是根 reactor 的全模块测试。需要更广的当前 Admin / Redirect 集成入口时，见[脚本导航](../../../scripts/README.md)，该入口另需显式 `-AllowReset` 及独立 MySQL / Redis / Kafka 条件，不能与本表的 11 项集成检查混为一谈。

## 脚本去重与复现

初轮 167 项来自 13 个离线测试文件，各文件数量见 `scripts-review.json:testCountsByFile`。冻结的初轮收据保留以下四次执行，不以运行次数累加覆盖；后续补充后最终并集为 172 项：

| 收据中的执行 | unittest 计数 | 退出码 | 解释 |
| --- | --- | --- | --- |
| Windows 目标回归 | 70 | 0 | 包含 9 项 PyYAML 拓扑 / 配置夹具测试 |
| Windows 扩展回归 | 97 | 1 | 95 通过；2 项原有 POSIX 定时器用例因 Windows 没有 `signal.getitimer` 报错 |
| WSL 首次合并执行 | 159 | 1 | 158 个实际用例通过，另 1 个为缺 PyYAML 导致的模块导入失败占位；不能称为 159 项实际用例 |
| WSL 无 PyYAML 模块的有界重跑 | 158 | 0 | 包含上述 2 项 POSIX 用例；与 Windows 70 项重叠 61 项 |

因此初轮覆盖并集为 **70 + 158 − 61 = 167**。随后主线验证可复用现存 PyYAML 6.0.2，WSL 的运行前提已解决；当时只更新环境收据，没有声称重跑整套 167 项。原失败记录保留。

后续[脚本隔离收据](script-isolation-review.json)新增 5 个测试方法，拓扑套件由 9 增至 14 项，并重跑 Redis 配置 10 项、制品路径 12 项，三套共 **36 次执行全部通过**。其中 31 项属于原覆盖，只有 5 项新增，因此最终并集为 **167 + 5 = 172**，不能写成 167 + 36。

补充检查覆盖依赖容器运行身份与 loopback 端口绑定、写库前的隔离确认、state 中保留实际依赖身份，以及 APISIX 清理失败时的 `CLEANUP_FAILED` / `servicesStopped=false`。集成入口另支持隔离高端口与 loopback Kafka、保留 `AllowReset` / 凭据前提并在 finally 恢复环境；PowerShell AST 通过。此轮三套离线检查没有启动 Java / Docker、执行真实 SQL、业务 HTTP 或压测。

复现离线检查时在 `scripts/performance` 运行收据 `testRuns[].command`；Linux 需提供 PyYAML。原工作环境使用 `PYTHONPATH=<仓库绝对路径>/.work/apisix-bootstrap-deps`，这个本地依赖目录不作为仓库交付文件。执行前确认依赖已准备，不要无差别导入所有组件脚本。

后续隔离回归在相同依赖环境分别执行 `python3 -B test_single_gateway_topology.py`、`python3 -B test_explicit_redis_database.py`、`python3 -B test_repository_jar_paths.py`；具体调用形式与更新文件 SHA256 见补充收据。原 `scripts-review.json` 保持初轮源码身份，发生后续修改的文件以补充收据记录的身份为准。

Lua logger 99 项的可复现入口如下，需使用已存在的 LuaJIT / cjson 环境。`--static-only` 不能代替实际 Lua 执行：

```bash
python3 -B scripts/performance/test_edge_execution_diagnostics.py \
  --suite logger --lua-command 'luajit -'
```

APISIX 21 项使用本轮本地驱动 `.work/single-gateway/apisix/validate.py`，由 Windows Python + PyYAML 调用既有 WSL Docker；会创建独立网络 / 容器，并使用 19480 / 19443。该临时驱动未包含在本归档，原工作区可用 `python -B .work/single-gateway/apisix/validate.py` 复现；仅克隆仓库时不能把该命令视为现成入口。持久维护的组件工具见[组件与部署验证](../../../scripts/README.md#组件与部署验证)，其各自用例数量与本次 21 项收据分别计算。

## 真实三 JAR E2E 与运行配置

本轮运行 ID 为 `20260911T080908Z-63b1c29f`。业务、HEAD 事件、Admin 公网、网关四个命令均以 0 退出，执行耗时分别为 20.781、27.048、6.487、19.224 秒。它们是不同的有限验收任务，耗时不能用于计算业务 QPS。`business.json` 的 `runId` 是业务夹具自身标识；执行记录及相同的 HEAD 专用 linkId / shortUri 将其关联到本轮监督运行。

| 配置 | 本轮实际值 |
| --- | --- |
| 公网入口 | `http://127.0.0.1:19080`；管理 Host `admin.e2e.test`，短链 Host `s.e2e.test` |
| Java | 17.0.20；Command 8001/8101、Admin 8002/8102、Redirect 8003/8103；没有 Java Gateway |
| 每 JVM 参数 | `-Xms64m -Xmx384m -XX:ActiveProcessorCount=2`；后者为 JVM 并行度提示，不代表 CPU 绑核 |
| APISIX | 3.11.0，2 workers；本轮容器 `shortlink-e2e-apisix-552a5de1`，可信来源 `10.242.92.6` |
| MySQL / Redis 隔离 | 新 schema `shortlink_cr_e2e_6e7d5cf76a`、Redis DB 8；实际命名容器及 loopback 23306 / 26379 绑定已核对 |
| Kafka / MinIO | Kafka 3.9.0，2 partitions、RF=1；宿主 `localhost:29092`、容器内 `localhost:9092`；对象端点 `http://127.0.0.1:29000` |
| 依赖容器预算 | MySQL / Kafka 各 1 CPU、768 MiB；Redis 0.5 CPU、128 MiB；MinIO 0.5 CPU、256 MiB |
| 服务范围 | 仅三个真实 JAR 与 APISIX；没有启动 Agent、Analytics API、Flink 或 ClickHouse |

这些是本轮隔离功能验证配置。[运行收据](runtime.json)中的三个实际 JAR SHA256 与[构建收据](build-artifacts.json)逐一相同；Admin 来自清理旧输出后的制品，监督器最终为 `STOPPED`、`servicesStopped=true`。

### 真实业务与资源边界结果

业务 16 项通过，共 42 次真实 HTTP 请求：注册登录及默认分组、未登录拒绝、非法目标拒绝、9 字符短码创建、幂等与冲突、真实 GET / HEAD 302、跨账号修改拒绝、已预热路由的更新失效、旧版本冲突、回收恢复和到期失效。更新 / 回收 / 恢复是有界最终一致校验，分别观察到约 0.239 / 0.222 / 0.232 秒收敛，不宣称写入后的首个跳转必然立即看到新状态。所有 Location 只核对返回值，不访问外部目标网站。

Admin 公网 9 项通过：header / query 同会话查询成功，跨 username 或另一 token 的注销返回 401，且两边原会话仍有效；正确注销后旧 token 返回 401。慢上传声明 1024 字节，每 0.4 秒发送一字节，累计只发送 13 字节便在 **5.01 秒返回 408**。普通请求声明 262145 字节，仅发首字节即在约 4 ms 返回 413；gzip 同样仅发首字节，在约 4 ms 返回 415。三次拒绝后的公开用户名查询均恢复 200，证明 APISIX 流式转发与真实 Admin 保护接通。

网关 14 项通过，其中 Admin 64 在途用例从可信网络命名空间直接访问 Admin 业务端口：保持 64 个未完成正文，第 65 个请求返回 429，关闭持有连接后恢复 200；它验证 Admin 本节点容量保护。另有 APISIX 管理 / 跳转 `rate=2, burst=0` 与管理连接低阈值用例，验证放行、429 和恢复。用例只临时调整工作副本，`productionManifestRestored=true`，原始配置文本完全恢复；这不是默认生产阈值下的吞吐测试。

### HEAD 与 Kafka 证据范围

独立 HEAD 短链 `5evCtf4kr`（linkId 4）只收到 1 次 HEAD、0 次 GET。在本轮 Redirect producer 身份与捕获位点内，匹配 click 事件为 0，匹配结果为 **1 条 `source=REDIRECT / method=HEAD / status=302`**。它不是说主题里总共只有一条消息。

| 原始主题 | 捕获范围，左闭右开 |
| --- | --- |
| `shortlink.click.raw.v1` | partition 0 `[0,9)`；partition 1 `[0,3)` |
| `shortlink.gateway.request.v1` | partition 0 `[0,26)`；partition 1 `[0,35)` |

采样前后 Redirect click 的 attempted/delivered 均为 12、result 均为 19，failed/rejected/pending 为 0。读取没有提交消费位点。此结论仅覆盖[HEAD 收据](head-events.json)冻结的原始事件区间，不证明区间之外的投递、Analytics 存储、Flink / ClickHouse 聚合或完整统计消费链路。

### 复现入口与顺序

本轮资源已经删除。重放需重新准备独立 MySQL、Redis、Kafka、MinIO、APISIX 镜像、主题与导入 bucket，以及 Java 17 / Python / PyYAML；通过进程环境提供 `SHORTLINK_E2E_MYSQL_ROOT_PASSWORD`、`SHORTLINK_E2E_IMPORT_ACCESS_KEY`、`SHORTLINK_E2E_IMPORT_SECRET_KEY`。每次由监督器生成新 schema、Redis DB 选择与新 state，已归档的 STOPPED state 只供审计。

以下为本轮[监督器](../../../scripts/e2e/run_create_redirect_e2e.py)参数，在专用 Linux 环境从仓库根执行；容器必须已运行并匹配指定的 loopback 端口：

```bash
python3 -B scripts/e2e/run_create_redirect_e2e.py \
  --allow-test-database --max-runtime-seconds 1800 \
  --mysql-container shortlink-single-gateway-mysql-20260911 \
  --redis-container shortlink-single-gateway-redis-20260911 \
  --kafka-container shortlink-single-gateway-kafka-20260911 \
  --network shortlink-single-gateway-20260911_default \
  --mysql-port 23306 --redis-port 26379 \
  --kafka-bootstrap localhost:29092 --kafka-host kafka \
  --object-endpoint http://127.0.0.1:29000
```

监督器 READY 后，在另一测试 shell 设置 `RUN_DIR` 为本次新生成的 `.work/e2e/<runId>` 绝对路径，并按[执行收据](finite-execution.json)的顺序运行。先保存业务夹具结果，再读取其 HEAD 身份核对事件；随后执行 Admin 与网关边界：

```bash
python3 -B scripts/e2e/shortlink_create_redirect_cases.py \
  http://127.0.0.1:19080 admin.e2e.test s.e2e.test > "$RUN_DIR/business.json"
python3 -B scripts/e2e/verify_head_events.py "$RUN_DIR"
python3 -B scripts/e2e/admin_ingress_cases.py "$RUN_DIR/state.json"
python3 -B scripts/e2e/verify_gateway_e2e.py "$RUN_DIR/state.json"
```

每个命令必须单独核对退出码；本轮四个命令均为 0。工具职责与依赖参数见[脚本导航](../../../scripts/README.md#创建与跳转-e2e)。完成后通过监督器所属目录的 STOP 信号停止本轮应用与 APISIX，再按实际容器身份清理本轮自建依赖。

### 整轮资源清理

[清理收据](resource-cleanup.json)确认：本轮三个 Java PID 均已退出，APISIX 删除退出码 0 且再次核对不存在；4 个独立依赖容器和本轮网络已删除。8001/8002/8003、8101/8102/8103、19080、23306、26379、29092、29000 共 **11 个端口关闭**，剩余运行容器为空，历史容器没有启动或删除，未使用 `docker prune`。

资源回收后，本轮 dockerd 以 0 退出，`shortlink-refactor-it` 专用 WSL 也已停止，停止后的 WSL 运行列表为空。上述结果与 `runtime.json` 的 STOPPED 状态一致；私有原始诊断记录继续留在本地工作目录，未复制进入公开归档。

## 归档边界

最终[仓库检查收据](final-audit.json)核对 Maven 模块数量、旧网关文件移除、变更脚本语法、文档链接、验收结果一致性及已知夹具凭据未进入归档。

本目录的 Java / 脚本收据经检查仅包含统计、固定错误说明、源码 / 报告哈希与环境路径，没有注册密码、会话 token、数据库口令或原始私有 fixture。外部资源与本轮 state 的私有记录留在 `.work`，不复制到公开文档。

本次不压测，不给新 QPS 结论；历史[压测报告](../../压测报告/README.md)继续描述原日期、原配置。公开跳转原来已从 APISIX 直达 Redirect，不能把移除管理链 Java Gateway 等同于跳转吞吐提升。未覆盖完整 Agent / LLM E2E、统计全消费链路、全模块集成及上线运维容量验收。
