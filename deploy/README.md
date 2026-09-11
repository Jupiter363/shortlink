# 运行配置与首次部署

所有业务进程是 Java JAR，Flink 是独立作业。可以直接使用系统服务管理进程，也可以自行构建容器镜像。`test/compose.*.yaml` 只用于隔离组件验收：单节点、测试口令及较小容量不得照搬到生产。

## 固定制品

Java 17；Kafka 3.9.0；Flink 1.20.3 / Kafka connector 3.4.0-1.20；官方 ClickHouse Kafka Connect 1.4.0；APISIX 3.11.0。Maven 使用各模块 POM 冻结依赖。Leaf 固定提交和许可证见[来源说明](../libraries/id-generator/UPSTREAM.md)。

```sh
mvn clean package -DskipTests
java -jar services/shortlink-command/target/shortlink-command-1.0-SNAPSHOT.jar \
  --spring.profiles.active=production \
  --spring.config.location=classpath:application-production.properties
```

上述命令从仓库根目录执行。所有 Spring 进程均使用上述两个配置参数，避免误合并本机被忽略的旧 YAML。Admin 构建产物为 `services/admin/target/shortlink-admin.jar`，其余制品名从 `services/` 下各模块 `target/` 读取；服务器交付目录可以另行选择，JAR 文件名不随仓库归类改变。禁止把 `test/` 口令或本地 root 账号作为生产凭据。

本版固定使用配置中的内网服务地址（由运维提供稳定 DNS/LB 名称），不同时启用第二套服务目录。旧模块保留的 Nacos 依赖默认关闭；Nacos 不是新服务启动前提。这是 M0-04 对已有发现模式的收敛决策，不代表已经验收 Nacos 集群。APISIX 正式配置存储模式见其 etcd 部署说明；文件 standalone 模式用于组件测试。

| 进程 | 业务端口 | 管理端口 | 依赖 |
| --- | ---: | ---: | --- |
| Command | 8001 | 8101 | 业务 MySQL、Kafka、导入对象存储 |
| Admin | 8002 | 8102 | 业务 MySQL、Redis、Command、Analytics、Agent |
| Redirect | 8003 | 8103 | 业务库受限账号（路由读取、缓存世代更新）、Redis、Kafka、Command 策略权威 |
| Analytics API | 8004 | 8104 | 统计控制库、Command、Worker、合格 CH 副本 |
| Agent | 8010 | 8110 | 独立 Agent 库、Admin、LLM |
| Analytics Worker | 8012 | 8112 | 统计控制库、Kafka、CH、对象存储、Command |

管理端口只绑定 `127.0.0.1`，公开 health/info/prometheus；用本机采集器或受控隧道读取。只有 APISIX 面向公网；服务端口和数据库端口使用网络策略限制。APISIX TLS 和 SNI 的文件配置见 [apisix/TLS.md](apisix/TLS.md)。内部令牌仍须配合私网/TLS，不替代网络隔离。

APISIX 是唯一网关：管理流量直接转发给 Admin:8002，短链流量直接转发给 Redirect:8003。Admin 的 `shortlink.admin.ingress.*` 配置核验 APISIX socket 对端、管理 Host、Redis 会话及当前账号权限，并提供有界并发和请求体预算。公开管理入口不接受伪造内部身份；Agent 内部工具继续使用专用内部鉴权链路。APISIX upstream 环境变量使用 `ADMIN_UPSTREAM_HOST`，旧 `GATEWAY_UPSTREAM_HOST`、8000/8100 监听和 Java Gateway 制品均已撤除。

Admin 管理入口的默认资源预算如下，完整配置以 `services/admin/src/main/resources/application-production.properties` 为准。并发与字节预算贯穿异步请求体读取和业务处理，耗尽返回 429；这些上限不代表进程 RSS 或已验证 QPS。

| `shortlink.admin.ingress.*` 配置 | 默认值 |
| --- | --- |
| `max-in-flight` | 64 个在途请求 |
| `ordinary-body-bytes` / `batch-body-bytes` | 256 KiB / 8 MiB |
| `total-body-bytes` | 64 MiB 请求体预留预算 |
| `body-read-timeout` | 5s 请求体绝对读取时限 |
| `session-timeout` / `redis-connect-timeout` | 150ms / 1s |

APISIX 管理路由使用 `proxy-control.request_buffering: false`，直接将请求体交给 Admin 的有界读取器，避免边缘与业务服务各自完整暂存一遍。APISIX 的全局 8 MiB 体积限制仍然有效；管理上游读取空闲超时为 10s，为 Admin 的有界处理和下游 5s 调用留出余量，它不等于请求总截止时间。边界只接受 `Content-Encoding: identity` 或未压缩请求，请求大小应在上述预算内。

## 必填环境

完整键名以各模块 `src/main/resources/application-production.properties` 为准，没有生产密码默认值。

| 环境组 | 变量 |
| --- | --- |
| 业务数据 | `BUSINESS_DB_URL`、`BUSINESS_DB_USERNAME`、`BUSINESS_DB_PASSWORD`、`BUSINESS_DB_CATALOG` |
| 服务身份 | `INTERNAL_TOKEN`（至少 32 字符）、`AGENT_INTERNAL_TOKEN`、`AGENT_SYSTEM_USERNAME` |
| 创建身份 | `SHORTLINK_DEFAULT_DOMAIN`、`SHORTLINK_ALLOWED_DOMAINS`、`SHORTCODE_FIXED_KEY_HEX`（固定 32 字节十六进制密钥） |
| 账号加密 | `ACCOUNT_PII_KEY`（固定 AES 密钥，至少 32 字符） |
| Redis | `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD` |
| 管理入口 | `ADMIN_ALLOWED_HOSTS`、`APISIX_CIDRS`；APISIX 的 `MANAGEMENT_HOST`、`ADMIN_UPSTREAM_HOST` |
| Redirect | `REDIRECT_DB_USERNAME`、`REDIRECT_DB_PASSWORD`、`REDIRECT_INSTANCE_ID`、`APISIX_CIDRS` |
| Kafka | `KAFKA_BOOTSTRAP_SERVERS`、`KAFKA_SECURITY_PROPERTIES` |
| 统计身份 | `ANALYTICS_HASH_KEY`（Redirect/Flink/Worker 一致且固定）、`REDIRECT_QUALITY_URLS`（完整实例清单） |
| 统计数据库 | `ANALYTICS_CONTROL_DB_URL`、`ANALYTICS_CONTROL_DB_USERNAME`、`ANALYTICS_CONTROL_DB_PASSWORD`、CH 环境配置 |
| 对象存储 | `OBJECT_ENDPOINT`、各模块 `IMPORT_*` / `ARCHIVE_*` 独立账号和 bucket |
| Agent | `AGENT_DB_*`、LLM 模型/地址/API 密钥，按 Agent 配置文件完整提供 |

Kafka 的 `KAFKA_SECURITY_PROPERTIES` 指向权限受控、UTF-8 且最多 64 KiB 的本机文件。共享代码只接受 TLS/SASL 连接相关白名单键，并保留应用固定的 ACK、幂等、队列及超时约束。文件存在时必须采用 SSL 或 SASL_SSL，不允许关闭 TLS 主机名校验。正式启动时必须设置该文件，测试环境才使用无文件的 PLAINTEXT。Connect 和 APISIX 采集插件的 Kafka 安全配置按各组件文档单独提供。

## 新环境初始化顺序

1. 为业务、统计控制、Agent 创建独立空 schema。业务采用一个物理库；分表不是跨库分布式事务。
2. 在明确指定的业务库执行 `mysql/001-business-schema.sql`。账号服务和 Command 共用此物理库；Redirect 使用独立账号，对 `t_link_route`、`t_cache_generation` 授予 `SELECT`，仅对 `t_cache_generation` 额外授予表级 `UPDATE`。Redis 世代标记缺失（例如新 Redis 数据库）时，`GenerationCoordinator` 需要条件更新 `cache_name='redirect-route'` 的世代并回读；其他业务表保持只读，`UPDATE` 权限不扩大到整个业务库。ID 号段账号/连接池可以限制到分配表；当前默认与 Command 同账号、独立连接池和事务。
3. 在统计控制库执行 `mysql/002-analytics-control-schema.sql`。在新的 Agent 空库执行 `mysql/003-agent-analytics-adaptation.sql`；它包含本版完整建表定义，不属于业务库。不能将 `CREATE TABLE IF NOT EXISTS` 当作修改旧表的迁移器；旧开发表应继续保留，使用新库验收和首次部署。
4. 配置对象存储独立导入/归档 bucket 和版本保留。导入必须保留 versionId；归档对象、外部恢复世代标记、manifest 日志和 Flink checkpoint 不能配置为任意覆盖或随意到期。
5. 创建 Kafka Topic：`kafka/topics.yaml`、`kafka/create-topics.sh`。生产 RF=3、minISR=2；raw 两条流使用 LogAppendTime；关闭自动建 Topic。为各生产者/消费者提供最小 Topic、consumer-group 和 transactional-id 权限，避免共用超级用户。
6. 配置 CH/Keeper。单机开发用 `clickhouse/001-analytics.sql` 和 `002-connect-landing.sql`；副本环境用 `003-replicated.sql.template` 填入实际集群名/宏。连接器依照 `clickhouse/connect-config.json` 安装，不启用跳过坏记录继续成功的容错模式。
7. 启动 Command、Worker，执行恢复 begin/reconcile/activate 协议。Command 初态自动动作关闭；完成真实覆盖和世代证明后才解除恢复门禁，Agent 仍逐次核验当前授权和统计证据。`collectionQuality=UNKNOWN` 会阻止自动动作；近似 UV 只阻止依赖精确 UV 的规则，不能以其他精确指标代替该证据。Flink 从固定 checkpoint/savepoint 启动，参数见下节。开始归档、派生投递和规范窗口发布后启动 Analytics API。
8. 启动 Admin、Redirect、Agent，再配置 APISIX TLS。注册/初始化分开显示状态：默认组未就绪不能伪报 READY。Agent 必须使用当前账号 authVersion，不能把旧开发 userId 复制为可信身份。

当前是新环境首次部署方案，不包含生产流量切换，也不修改原有开发 schema。任何恢复、备份或保留期变更都按 [统计恢复协议](../doc/analytics/runtime.md) 核对外部存储覆盖。

## Flink 启动

在 Flink 1.20.3 的 Linux 集群安装对应 S3 文件系统插件，配置受限 checkpoint bucket 凭据。仓库构建产物为 `jobs/analytics-flink/target/analytics-flink-1.0-SNAPSHOT.jar`；该 shaded JAR 包含应用、Kafka connector 和事件依赖，Flink core/runtime 由集群提供。下面示例使用交付到提交目录的 JAR 文件名。

```sh
bin/flink run -d -c com.jupiter.shortlink.analytics.flink.AnalyticsFlinkJob \
  analytics-flink-1.0-SNAPSHOT.jar \
  --bootstrap "$KAFKA_BOOTSTRAP_SERVERS" \
  --build-id "$ANALYTICS_BUILD_ID" \
  --recovery-epoch "$ANALYTICS_RECOVERY_EPOCH" \
  --checkpoint-uri "$FLINK_CHECKPOINT_URI" \
  --group-id shortlink-analytics-v1 --parallelism 2
```

并行度 2 仅为启动基线，不是容量承诺。JobManager/TaskManager 均要获得固定 hash 密钥及 Kafka 安全文件。恢复同一作业保留 UID、checkpoint 和事务身份；新状态构建使用新 build ID，不允许两个运行实例共享同一 Kafka transaction prefix。Kafka 事务超时须覆盖已配置的 checkpoint 时间和故障恢复间隔。

## 边界与运行观测

- 创建同步最多 500 行；501～50000 为持久任务；更大请求使用不可变对象导入，文件最多 64 MiB、最多一百万行。取消与旧 Worker 提交在同库事务中仲裁。
- 同步统计最多 7 天、500 链接、10000 明细行；超预算使用查询 Job，持久结果有过期和当前授权复核。长任务最多 180 天，不扩大无限扫描预算。
- 列表今日指标来自 Analytics 快照，保留质量信息。排序必须先覆盖全组再分页，当前上限 500 链接；跨页携带 `statsSnapshotId` 和 `statsEnd`。累计排序只有生命周期在同步预算内且覆盖完整时支持，否则明确 `ASYNC_REQUIRED`；普通列表未证明的累计值为 null。
- JVM、HTTP、连接池及事件丢弃/失败/队列字节、ID 当前余量/预取状态/失败均从实际指标采集。Kafka 两条 raw 流分别监测 lag 和归档覆盖；不能仅凭 lag=0 判断统计完整。
- Outbox 失败保持可重试状态；监测最早未发布时间。Batch/metadata/查询任务按 state、租约和错误原因监测，避免高基数 tenant/link 标签。
- 统计控制库恢复必须先暂停自动动作，重建外部世代和覆盖；缺块时保持不可用。手工撤销仍依据当前授权和 Command 事实处理。

压力、容量、故障耐久性和完整 E2E 另行验收。本轮组件测试通过不等同于首次生产上线批准。
