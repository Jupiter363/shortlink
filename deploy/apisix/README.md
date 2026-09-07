# APISIX 3.11.0 入口

正式部署按 [ETCD.md](ETCD.md) 使用 traditional + etcd 保存配置，通过管理网 HTTPS Admin API 鉴权导入。仓库 `config.yaml` 的 standalone 模式仅用于组件验收：挂载 config.yaml→/usr/local/apisix/conf/config.yaml、apisix.yaml→/usr/local/apisix/conf/apisix.yaml、plugins→/opt/shortlink。使用 Apache APISIX 3.11.0 官方镜像。公网只发布 APISIX，Java Gateway/Redirect、Command、Redis、Kafka 只在服务网络内可达。

Standalone 容器或正式 bootstrap 环境必须设置 KAFKA_HOST、APISIX_INSTANCE_ID（部署标签）、MANAGEMENT_HOST、SHORTLINK_HOST、GATEWAY_UPSTREAM_HOST、REDIRECT_UPSTREAM_HOST。每个 APISIX 节点需固定唯一 hostname，事件身份再追加节点和 boot UUID，避免共享 etcd 配置混淆节点。自定义域名需同步加入对应 route.hosts 和 Redirect allowed-hosts，禁止 wildcard Host fallback。生产 TLS 使用 [TLS.md](TLS.md) 的秘密文件输入，按 ETCD.md 导入同一 TLS 清单；此 HTTP 文件用于隔离开发环境，Java 的 secure cookie 随 APISIX 实际 scheme 决定。

公开短链只接受单段 Base62 GET/HEAD，管理请求只进 Java Gateway。入口重写 XFF/proto/request-id，清理身份伪造头；用户名/token 仅管理会话边界保留。禁用 upstream retries，不配置 302 缓存。limit-req/limit-conn 是可调开发样例，最终预算来自 M6。

shortlink-request-logger 为每个请求产生独立 APISIX/EDGE GatewayRequestEventV1。`send_concurrency` 为每个 worker 的独立发送槽数，范围 1–8、插件缺省 2，本仓库部署配置为 4。`send_batch_size` 范围 1–128、缺省 1，部署配置为 32；`send_batch_bytes` 范围 16 KiB–1 MiB、缺省及部署值为 64 KiB，按 key 与 JSON 的总字节计。timer 只合并当前已有、属于同一配置的队首事件，不等待凑满。每个事件仍是独立 Kafka record，按分区打包到 Produce 请求；没有把多条事件包成一个统计事件。

每槽独占自己的同步发送状态，允许多个 ACK 等待重叠。全部槽共用每 worker 的 1000 条、8 MiB 预算，单事件最大 4096 字节，在途项与重试项始终占用预算。`inflight_count` 是事件条数，`active_senders` 是发送槽数；部署四槽最多同时持有 128 条批内事件，仍受共享队列上限约束。增加槽或批上限不扩大队列预算，但会增加在途编码及连接成本，需要按实际机器复测。

Kafka send 和 ACK 仅在 background timer 中执行，确认要求保持 `acks=-1`。批量路径采用项目私有 `shortlink-kafka-batch.lua`，复用固定 lua-resty-kafka 0.20 的编码器，最多三次外层调用、无内部重试；每次可能包含一次 metadata 和按 broker 分组的多个 Produce 请求，因此不是“三次网络请求”。默认逐条兼容路径仍使用原同步 producer，保留原客户端内部重试。两条路径重试均保留事件 ID、发生时间和 payload；批量路径额外固定首次分区，已 ACK 项不再重发，不可重试错误直接终态。

批量路径完整校验响应帧、correlation id、主题、分区集合和成功 offset 后，才记录该请求内的 ACK；后续 broker 失败不会撤销已确认项。编码请求及响应有独立大小上限，错误连接关闭，合法响应后才进入按 TLS/认证配置隔离的连接池。只有成功 ACK 才计 delivered，接纳后失败与队列拒绝分别计数。HTTP 302 并不证明事件已交付；ACK 不确定可能导致重复，内存队列不保证进程崩溃后的重放，Kafka 故障造成的采集损失必须进入 collectionQuality 监控。

127.0.0.1:9099/shortlink/metrics 只用于本机诊断，不得对公网开放。除 attempted/delivered/failed/rejected 外，提供 queued/inflight/active_senders、最老 pending 年龄、发送尝试/总耗时、重试及按原因的拒绝指标。发送耗时含客户端处理、metadata 和 ACK 等待，不是纯 broker ACK 延迟。完整观测必须满足 `attempted = delivered + failed + rejected + pending`、`pending = queued + inflight`；跨 worker 采样短暂不一致会标记 `observation_complete=0`，不能当作空队列。旧 worker 世代残留不会被新 worker 覆盖。

新增 `shortlink_edge_batch_*` 指标区分调用数、事件尝试数、Produce 请求/记录尝试数和 metadata 请求数；Produce 计数在 socket 发送前递增，不能直接当作 ACK 数。`send_attempts` 在批量模式统计批调用而非事件条数，不可用旧的“一调用一事件”口径比较。旧世代缺少扩展指标时 `batch_observation_complete=0`，原事件账本仍独立核对；缺失的历史批量指标不能当作零。

调度与记账回归入口为 `scripts/performance/test_edge_sender_batch.py`（包含原并发测试），协议故障入口为 `scripts/performance/test_edge_kafka_batch.py`，默认使用运行时已安装的固定 Kafka 编码器。有限真实 Kafka 故障/恢复验证入口为 `scripts/integration/apisix_sender_component.py`，显式四槽在途验证为 `scripts/integration/apisix_sender_four_component.py`，批发送及双分区精确核对为 `scripts/integration/apisix_sender_batch_component.py`。上一轮逐条并发发送的吞吐、失败和资源口径见[网关并发复测报告](../../plan/生产级重构增强/12-网关事件并发优化与复测.md)，其数据不代表本次批发送已经通过压测。

本次批发送与2/4/8 worker结果见[批发送复测报告](../../plan/生产级重构增强/13-网关有界批发送优化与复测.md)。隔离性能脚本支持 `--edge-workers 2/4/8`，默认2；8 worker实际使用CPU 0–7，与Java共享，每worker派生250条/2MiB队列、四槽，节点总预算保持2000条/16MiB。这个覆盖只写入独立run的派生清单，仓库部署基础清单仍为每worker1000条/8MiB；正式部署需结合实际worker数确定节点总预算。8 worker在5000/s目标的90.024秒窗口实测约4982.32正确请求/s、P99=67ms，因1228次全场景丢迭代仍未通过容量验收，未运行长确认。

依据：[Standalone 与环境变量](https://apisix.apache.org/docs/apisix/3.11/deployment-modes/)、[插件装载](https://apisix.apache.org/docs/apisix/3.11/plugin-develop/)、[官方 Kafka logger producer 参数](https://github.com/apache/apisix/blob/3.11.0/apisix/plugins/kafka-logger.lua)。本目录是实现和部署输入，测试记录不将未运行的 APISIX HTTP 端到端验收视为已完成。
# Component verification and coarse budgets

Both management and redirect routes enforce local connection and request limits. The example
limits are bounded protective defaults, not measured QPS capacity or an acceptance target.
Freeze production values from the final hardware and reliability measurements. Batch bodies
are capped at 8MB at the edge; Java Gateway applies the stricter ordinary 256KB budget after
session authorization. Environment substitution belongs in upstream node values; node map
keys are not a supported template mechanism in the verified APISIX 3.11 runtime. Production
Gateway and Redirect ports are 8000 and 8003 respectively.

Reproducible isolated HTTP cases and actual results are documented in
[`docs/integration/component-adapters.md`](../../docs/integration/component-adapters.md).
