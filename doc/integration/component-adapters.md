# 隔离组件验收记录

环境：2026-09-06，新建 WSL `shortlink-refactor-it`，Ubuntu 24.04.4、原生 Docker Engine 29.1.3。
使用 `deploy/test/compose.integration.yaml`、`compose.adapters.yaml`、`compose.replicas.yaml`。
本节适配器测试仅访问隔离容器；没有使用旧 Docker Desktop 数据，也没有完整业务 E2E 或压测。
后续 production jar 启动复验使用下文明确列出的授权隔离测试库。

固定组件：APISIX 3.11.0-debian、nginx 1.27.4-alpine 测试上游、Kafka 3.9.0、ClickHouse
25.3.6.56、官方 `com.clickhouse.kafka.connect.ClickHouseSinkConnector`（运行时版本 `v1.4.0`）、
Redis 7.2.5、MySQL 8.0.36、Java 17。Kafka 测试配置为 2 分区/RF1/minISR1，不能视为生产容量配置。

## 重跑

先启动上述隔离 Compose。`component_adapters.py` 仅使用 Python 3 标准库与 WSL Docker CLI，
HTTP 客户端直接连接本地端口，不使用外部代理、不自动跟随 302。Windows 示例：

```powershell
python scripts/integration/component_adapters.py init
python scripts/integration/component_adapters.py topics
python scripts/integration/component_adapters.py apisix
python scripts/integration/component_adapters.py connect
python scripts/integration/apisix_tls_component.py
./scripts/integration/run-gateway-redirect-it.ps1 -JavaHome D:/develop/javaJDK/17
```

`init` 仅以 `IF NOT EXISTS` 应用最新 `001-analytics.sql`、`002-connect-landing.sql`，将库名
替换为 `shortlink_analytics_it`；不清表。`topics` 使用生产 topic 初始化脚本，但显式选择上述隔离参数。
测试结果写入 `.work/component-results/`。失败会返回非零退出码，不将缺失依赖记成跳过或通过。

## 已执行结果

| Case | 触发与结果 | 实际证据 |
|---|---|---|
| AP01–AP02 | 已注册 Host 的 GET 302 指向测试上游；HEAD 302 无正文 | `apisix-20260906T122348Z.json` |
| AP03–AP06 | POST 405、未注册 Host 404、两个入口的内部路由均 404 | 同上 |
| AP07–AP10 | 编码斜线/重复斜线 400，超长短码 404，大小写及默认端口 Host 302 | 同上 |
| AP11 | x-shortlink/x-agent/legacy 用户身份及内部 token 被剥离；管理 username 凭证保留；XFF/proto/request-id 重建 | 同上，记录实际 nginx 回显 |
| TLS01–TLS03 | 信任临时测试证书并验证 SNI 主机名；TLS1.3 GET302、HEAD无正文；管理上游收到可信 https scheme | `tls-20260906T123008Z.json` |
| CHC01 | 官方 Connect 将一条 derived record 实际写入与正式 landing 表同结构的测试表；大于 2^53 的 long、来源 offset、refererDomain、hashVersion 完整保留；样例使用时间绑定 ID 和 broker-time-v2 | `connect-20260906T130445Z.json` |
| CHC02 | 非法 UInt32 数据使 task FAILED，目标表行数为 0；未当成成功跳过 | 同上 |
| GatewaySessionRedisIT | 真实 Redis Session 读取、过期和可信身份边界，1 项通过 | `.work/verification/gateway-redirect/verify.log` 与 `gateway/failsafe-reports` |
| Redirect IT | RedisRouteCacheIT 3、JdbcGenerationIT 2、KafkaPublisherIT 1、ChangeFanoutIT 1，共 7 项通过，含新事件时间绑定 | 同上与 `shortlink-redirect/failsafe-reports` |

上表非 JUnit 适配器用例为 HTTP 11 + TLS 3 + Connect 2，共 16 项；Java IT 另为 1 + 7 项。
下文正式 etcd 模式新增 7 项，非 JUnit 适配器总数为 **23 项**。Java IT 不重复累加到这个数字；
最终 Java 回归于 21:09:23 通过，使用 `-Pintegration verify`，36 项单元、8 项集成均零失败/错误/跳过；完整日志和 JUnit XML 已归档至 `.work/verification/gateway-redirect/`。数据库/topic 初始化、制品启动和 promtool
检查分别记录，不混入这 23 项功能用例。
Connect 使用独立 `shortlink.it.adapter.<run>.*` topic、`adapter_<run>_*` 表和唯一 eventId。
表通过 `CREATE TABLE ... AS ...derived_events` 获取实际列类型；测试数值是序列化边界样例，
不代表短码发号器会产生超范围业务 ID。记录落表属于单适配器验收，不包含 Flink 或业务跳转全链路。
重放去重/快照一致性由 analytics lane 的独立 IT 验证，本脚本没有将单次投递当作 exactly-once 证明。

脚本在完成后删除自己创建的 Connect connector，保留唯一测试 topic/表及结果供核对。
TLS 脚本只启动唯一名称的临时 APISIX 容器，完成后停止并自动删除；证书是 1 天测试证书，
生成文件在被忽略的 `.work/` 中，禁止作为生产凭证。正式 TLS 步骤见
[`deploy/apisix/TLS.md`](../../deploy/apisix/TLS.md)。

## 实测推动的修订

APISIX 3.11 对 upstream.nodes 映射键里的环境变量会先按字面量尝试 DNS，实测首次请求出现
约 24 秒等待。已改为数组节点，把 host 环境变量放在值中，并固定 Gateway/Redirect 端口 8000/8003；
重建后上述 HTTP 用例均通过。两个入口都具有粗粒度请求/连接上限，数值是保护配置，不能当作 QPS 验收结论。
Connect 插件 API 返回版本带 `v` 前缀，且注册后 status topic 异步建立；验收脚本准确识别版本并有限等待状态。

## 正式 etcd 模式组件验证

正式部署步骤见 [`deploy/apisix/ETCD.md`](../../deploy/apisix/ETCD.md)。Standalone 仅用于上文
独立组件验证；新增 traditional + etcd 模板复用同一插件、路由、body/连接预算及 TLS 清单，
通过管理网 HTTPS Admin API 导入。使用受控静态内网 DNS 服务名，当前未接线 Nacos 双目录。

```powershell
python -m pip install -r deploy/apisix/requirements.txt
python scripts/integration/apisix_etcd_component.py
```

etcd 测试版本 `quay.io/coreos/etcd:v3.5.17`，镜像摘要
`sha256:a055da833a7c013b836ed0822e8ec1f99b059658be255ad8d0fcd31b635ae3d6`。
脚本创建唯一名称的 etcd 和两个 APISIX，etcd 开启 HTTPS 与限定前缀账户；Admin/公网 TLS
使用临时证书，客户端校验 CA 与主机名。只将测试端口发布到 localhost，无已有容器/数据修改。

| Case | 触发与结果 | 最终证据 |
|---|---|---|
| ET01 | HTTPS+API key 两次导入相同的 1 global_rule、1 ssl、2 routes；每项读回核对，摘要相同 | `etcd-20260906T130637Z.json` |
| ET02 | Admin API 未传/错误 key 均为 401，HTTPS 验证启用 | 同上 |
| ET03 | 已导入 TLS 路由 GET302/HEAD302，HEAD 无正文且 Location 正确 | 同上 |
| ET04 | POST405、未知 Host404、两个入口内部路径404、编码斜线400 | 同上 |
| ET05 | 管理请求剥离伪造 tenant/internal token；传给上游的 scheme 为 https | 同上 |
| ET06 | 重启 APISIX，仍从 etcd 加载两条路由并恢复 HEAD302 | 同上 |
| ET07 | 同一 etcd 配置的两个节点与节点重启后共 3 条真实 Kafka 记录，producer 身份各异；decisionId 精确绑定 occurredAt 与上游回显 request-id，客户端伪造 ID 被替换，suffix 为 1–256 个可打印 ASCII 字符 | 同上，包含三个实际 producerInstanceId 与 suffixLengths |

ET07 首轮暴露日志阶段重新读取 nginx request_id 与边界转发值不一致，已在同一请求 ctx 固定后
复验通过。节点身份包含部署标签、固定 hostname、共享字典 boot UUID；worker reload 保留字典及
计数，全实例重启更新 boot。此前失败结果保留供追溯，上表只引用修正后的完整 7/7 通过结果。
脚本结束停止并删除自己创建的临时容器；单成员 etcd 验证不证明生产三成员的故障容量。

最终事件协议为 `v1:<occurredAt规范十进制毫秒>:<受控suffix>`。Redirect 点击与请求结果在生成时
各取一次时间并调用共享 `EventIdentity.bind`；APISIX 使用同一个毫秒变量写时间字段和 ID，
suffix 包含部署/节点/boot 身份与受控 request-id。未匹配边界的 EDGE 请求使用独立 UUID，
不复用客户端头；配置中的实例标签提前限制为可打印 ASCII。后台发送重试始终复用已序列化
body 和 key，不续期或重建 ID。此次最后联改的 Java 回归已由根节点以 `-Pintegration verify`
执行并通过，使用上表的新日志和 XML，不将前一轮结果重复累计。

## 生产配置制品启动与观测

`production_jar_components.py` 使用 `java -jar` 启动真实可执行制品，并显式加载仓库
`application-production.properties`。仅测试端口、测试秘密、无密码 Redis 和本机健康详情覆盖生产值。
采用 Gateway 18000/18100、Redirect 18003/18103；服务在脚本结束后关闭，不保留后台进程。
业务数据库为经根节点指定的本机 3306 `shortlink_batch_it`，专用测试账户；Redis/Kafka 是上述 WSL 隔离实例，
Command 是已验证的本地 28001 测试实例。没有读写其它本机开发业务库。

```powershell
$env:SHORTLINK_IT_BUSINESS_DB_URL='jdbc:mysql://127.0.0.1:3306/shortlink_batch_it?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true'
$env:SHORTLINK_IT_DB_USERNAME='shortlink_refactor_it'
$env:SHORTLINK_IT_DB_PASSWORD='shortlink-it-only'
$env:SHORTLINK_IT_COMMAND_URL='http://127.0.0.1:28001'
python scripts/integration/production_jar_components.py
```

上述密码仅为隔离验收约定，不能用于首次正式上线。执行前需与根节点协调共享测试库及 Command 生命周期。
脚本在 liveness UP 后每 5 秒查询整体 health，最多等待 30 秒；整体非 UP 返回失败。
首次 Redirect 检查发生于依赖初始化期间，整体曾为 503，未据此声称依赖可用；复验明确等到整体 UP。

| 组件检查 | 最终结果 | 证据 |
|---|---|---|
| Gateway production jar | Bean/配置绑定完成；liveness UP、整体 health UP、Prometheus 200 | `production-jars-20260906T123541Z.json` 中 Gateway 条目 |
| Redirect production jar | Bean/配置绑定完成；liveness UP、整体 health UP、Prometheus 200 | `production-jars-20260906T123840Z.json` |
| Prometheus 配置 | 官方 `prom/prometheus:v3.2.1` 的 promtool 通过 1 个采集配置与 4 条告警规则检查 | `deploy/monitoring/README.md` 的复现命令；镜像摘要 `sha256:6927e0919a144aa7616fd0137d4816816d42f6b816de3af269ab065250859a62` |

实际指标全文位于 `.work/component-results/*-prometheus-*.txt`；监控模板见
[`deploy/monitoring/README.md`](../../deploy/monitoring/README.md)。未把 liveness 或指标可采集当成整体依赖就绪。
