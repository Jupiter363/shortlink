# APISIX 3.11.0 入口

正式部署按 [ETCD.md](ETCD.md) 使用 traditional + etcd 保存配置，通过管理网 HTTPS Admin API 鉴权导入。仓库 `config.yaml` 的 standalone 模式仅用于组件验收：挂载 config.yaml→/usr/local/apisix/conf/config.yaml、apisix.yaml→/usr/local/apisix/conf/apisix.yaml、plugins→/opt/shortlink。使用 Apache APISIX 3.11.0 官方镜像。APISIX 是唯一网关；Admin、Redirect、Command、Redis、Kafka 只在服务网络内可达。

Standalone 容器或正式 bootstrap 环境必须设置 KAFKA_HOST、APISIX_INSTANCE_ID（部署标签）、MANAGEMENT_HOST、SHORTLINK_HOST、ADMIN_UPSTREAM_HOST、REDIRECT_UPSTREAM_HOST。每个 APISIX 节点需固定唯一 hostname，事件身份再追加节点和 boot UUID，避免共享 etcd 配置混淆节点。自定义域名需同步加入对应 route.hosts 和服务 allowed-hosts（管理域名为 ADMIN_ALLOWED_HOSTS，短链域名为 Redirect 的 allowed-hosts），禁止 wildcard Host fallback。生产 TLS 使用 [TLS.md](TLS.md) 的秘密文件输入，按 ETCD.md 导入同一 TLS 清单；此 HTTP 文件用于隔离开发环境，Java 的 secure cookie 随 APISIX 实际 scheme 决定。

公开短链只接受单段 Base62 GET/HEAD，直接进入 Redirect:8003；管理请求直接进入 Admin:8002。外部路由仍仅开放现有管理 API 前缀与单段短码，内部 Agent tools、恢复接口、Actuator 不属于公网路由。入口保留原始 Host，按实际客户端和接入协议重写 XFF/proto/request-id，清理全部 x-shortlink-*、x-agent-* 和旧身份伪造头；用户名/token 仅在管理路由保留。Admin 根据真实 socket 对端核验 APISIX_CIDRS 和 ADMIN_ALLOWED_HOSTS，随后校验 Redis 会话及数据库中的当前账号权限；不再要求客户端请求携带内部令牌，也不通过额外的 forward-auth 请求鉴权。禁止把任意客户端的转发头当作可信来源。

入口流量整形只在 APISIX 的 limit-req/limit-conn 执行，现有限值保持不变；Admin 的并发槽和请求体字节预算是服务自身的资源保护。全局请求体上限仍为 8 MiB；普通请求 256 KiB、批量请求 8 MiB、压缩体拒绝与读取时限由 Admin 统一处理。管理路由通过官方 `proxy-control.request_buffering: false` 流式转发，请求体不会先在 APISIX 完整暂存再由 Admin 重新聚合；Admin 接纳后以 5s 绝对时限读取，避免持续滴流仅靠空闲超时无限续期。短链路由不启用这个插件。管理上游连接/发送/读取空闲超时为 1s/3s/10s，读取预算为 Admin 下游 5s 调用留出处理余量；这不是整条请求的总截止时间，长任务仍由各自业务时限管理。客户端 body 空闲超时仍为 10s。禁用 upstream retries，不配置 302 缓存。limit-req/limit-conn 是可调开发样例，最终预算来自 M6。

仓库共享源配置默认固定为 **2 worker、每 worker 1 个 sender、linger 5ms**。`config.yaml` 显式声明 `nginx_config.worker_processes: 2`，正式 etcd 和 TLS 生成器继承该源配置。每 worker 1000 条/8 MiB，对应节点总计 2000 条/16 MiB、2 个发送槽；插件 schema 的 sender 缺省 2 保持不变。部署时需核对 `APISIX_WORKER_PROCESSES` 环境覆盖、最终生成的 Nginx 配置和实际普通 worker 数，不能仅凭 YAML 推算节点预算。下面保留的旧实验按各自清单解释，不代表新默认已完成容量验收。同一 etcd 全局配置下，各节点采用相同 worker profile。上述额度是单活动世代的 key+JSON 保留预算，不是进程 RSS 上限；reload 的旧世代 pending 单独统计。

logger 在普通 worker 的 `init_worker` 阶段调用 `shortlink-fd-preallocate.lua`，在受支持的 Linux x64 环境把初始 FD 表准备到至少 1024。它临时复制并关闭自己创建的描述符，不创建业务连接，不修改 `nofile` 或 `worker_connections`；更高连接数仍可能触发后续扩容。模块加载、初始化或关闭失败均保留原统计注册，并输出一次有界警告；不支持的平台继续启动，不能把启动成功当作 FD 准备成功。该准备仅针对实测冷接入扩表停顿，部署预算和持续容量仍需单独核验。实现、对照及验收口径见[接入调度与发送组织报告](../../doc/压测报告/过程报告/24-接入调度与发送组织优化执行报告.md)。

本轮持续组件验收采用4 worker、节点1000条/8MiB、HTTP固定302桩和真实Kafka：8,000/s重复三轮60秒，全部HTTP与统计交付正确，双P99通过。它不包含生产TLS、限流器、真实Redirect或消费者，也不是上述2 worker部署默认的容量验收。20,000/s存在事件拒绝；仅扩CPU可用范围、保持4 worker的对照没有改善，当前不据此给出硬件最大QPS或broker扩容结论。

后续真实跳转对照见[按序突破执行记录](../../doc/压测报告/过程报告/27-真实跳转按序突破执行记录.md)：经过启用限流器的APISIX、实际Java路由/策略缓存及权威刷新，校验302与Location，并核对click、BUSINESS、EDGE和真实Kafka offsets。它使用独立的4/8 worker派生配置；10条热链、HTTP、单broker环境的结果不代表2 worker默认配置、冷链、TLS或Analytics消费吞吐。

shortlink-request-logger 为每个请求产生独立 APISIX/EDGE GatewayRequestEventV1。`send_concurrency` 为每个 worker 的独立发送槽数，范围 1–8、插件缺省 2，本仓库部署配置为 1。`send_batch_size` 范围 1–128、缺省 1，部署配置为 32；`send_batch_bytes` 范围 16 KiB–1 MiB、缺省及部署值为 64 KiB，按 key 与 JSON 的总字节计。`send_linger_ms` 可选 0–5 或 10，插件 schema 缺省仍为 0，仓库部署显式为 5；10ms 是独立对照选项，尚无端到端容量收益证明。timer 合并同一配置的队首事件，每个事件仍是独立 Kafka record，按分区打包到 Produce 请求。

正数 linger 以最早事件的创建时间加配置毫秒数为固定期限，新增事件不延长期限；满批、字节或配置边界可立即发送，0 和逐条兼容模式不主动等待。主动等待的 timer 目标不超过配置值；为避免零延迟定时器空转，实际 timer 向上对齐整数毫秒，且事件循环繁忙时可能晚于期限执行，所以不承诺硬性 5ms 延迟上界。等待期间不占 sender 槽，事件仍占用原 count/bytes 预算；ACK、重试、退出残留和统计质量合同均不变。

性能脚本 `supervisor.py --edge-send-linger-millis` 接受 0–5 或 10，显式覆盖部署字段，默认 5 与部署一致；旧程序调用若没有该参数，也采用 5。显式 0 会覆盖部署中的 5，适用于零等待对照；旧清单缺少字段且显式选 0 时保持缺省零等待，不插入字段。脚本只修改唯一全局 logger 的该字段，保留注释及换行，拒绝重复、别名、错误类型和块外字段。

已完成的隔离诊断 [baseline](../../.work/gateway-deep-investigation-20260909/experiments/baseline/experiment.json) 与 [linger1](../../.work/gateway-deep-investigation-20260909/experiments/linger1/experiment.json) 均为 15,000 条事件：1ms 攒批使 Produce 请求数从 9,781 降至 3,746。两组响应时间仍未达到原验收门槛，这证明该条件下减少了发送请求，不能写成容量验收通过或生产吞吐承诺。没有正式启用 `multi_accept`。

每槽独占自己的同步发送状态，允许多个 ACK 等待重叠。全部槽共用每 worker 的 1000 条、8 MiB 预算，单事件最大 4096 字节，在途项与重试项始终占用预算。`inflight_count` 是事件条数，`active_senders` 是发送槽数；默认每 worker 一槽最多同时持有 32 条批内事件，仍受共享队列上限约束。增加槽或批上限不扩大队列预算，但会增加在途编码及连接成本，需要按实际机器复测。

Kafka send 和 ACK 仅在 background timer 中执行，确认要求保持 `acks=-1`。批量路径采用项目私有 `shortlink-kafka-batch.lua`，复用固定 lua-resty-kafka 0.20 的编码器，最多三次外层调用、无内部重试；每次可能包含一次 metadata 和按 broker 分组的多个 Produce 请求，因此不是“三次网络请求”。默认逐条兼容路径仍使用原同步 producer，保留原客户端内部重试。两条路径重试均保留事件 ID、发生时间和 payload；批量路径额外固定首次分区，已 ACK 项不再重发，不可重试错误直接终态。

批量路径完整校验响应帧、correlation id、主题、分区集合和成功 offset 后，才记录该请求内的 ACK；后续 broker 失败不会撤销已确认项。编码请求及响应有独立大小上限，错误连接关闭，合法响应后才进入按 TLS/认证配置隔离的连接池。只有成功 ACK 才计 delivered，接纳后失败与队列拒绝分别计数。HTTP 302 并不证明事件已交付；ACK 不确定可能导致重复，内存队列不保证进程崩溃后的重放，Kafka 故障造成的采集损失必须进入 collectionQuality 监控。

127.0.0.1:9099/shortlink/metrics 只用于本机诊断，不得对公网开放。除 attempted/delivered/failed/rejected 外，提供 queued/inflight/active_senders、最老 pending 年龄、发送尝试/总耗时、重试及按原因的拒绝指标。发送耗时含客户端处理、metadata 和 ACK 等待，不是纯 broker ACK 延迟。完整观测必须满足 `attempted = delivered + failed + rejected + pending`、`pending = queued + inflight`；跨 worker 采样短暂不一致会标记 `observation_complete=0`，不能当作空队列。旧 worker 世代残留不会被新 worker 覆盖。

新增 `shortlink_edge_batch_*` 指标区分调用数、事件尝试数、Produce 请求/记录尝试数和 metadata 请求数；Produce 计数在 socket 发送前递增，不能直接当作 ACK 数。`send_attempts` 在批量模式统计批调用而非事件条数，不可用旧的“一调用一事件”口径比较。旧世代缺少扩展指标时 `batch_observation_complete=0`，原事件账本仍独立核对；缺失的历史批量指标不能当作零。

调度与记账回归入口为 `scripts/performance/test_edge_sender_batch.py`（包含原并发测试），协议故障入口为 `scripts/performance/test_edge_kafka_batch.py`，默认使用运行时已安装的固定 Kafka 编码器。有限真实 Kafka 故障/恢复验证入口为 `scripts/integration/apisix_sender_component.py`，显式四槽在途验证为 `scripts/integration/apisix_sender_four_component.py`，批发送及双分区精确核对为 `scripts/integration/apisix_sender_batch_component.py`。上一轮逐条并发发送的吞吐、失败和资源口径见[网关并发复测报告](../../doc/压测报告/过程报告/12-网关事件并发优化与复测.md)，其数据不代表本次批发送已经通过压测。

历史批发送与2/4/8 worker结果见[批发送复测报告](../../doc/压测报告/过程报告/13-网关有界批发送优化与复测.md)。隔离性能脚本支持 `--edge-workers 2/4/8`、`--edge-send-concurrency 1/2/4`，默认分别为2和1；所有组合均按worker数分摊节点2000条/16MiB，发送槽总数为worker数乘每worker sender数。8 worker默认使用CPU 0–7，与Java共享，每worker派生250条/2MiB队列；这些覆盖只写入独立run的派生清单。渲染器先严格核验唯一全局logger及唯一nginx_config内的已知源worker值2，再应用显式选择，拒绝重复、别名、错误层级/类型和未知源值。组件实验采用的节点1000条/8MiB是独立实验预算，不能替换本性能脚本的节点预算。历史8 worker/每worker四槽在5000/s目标的90.024秒窗口实测约4982.32正确请求/s、P99=67ms，因1228次全场景丢迭代仍未通过容量验收，未运行长确认。

依据：[Standalone 与环境变量](https://apisix.apache.org/docs/apisix/3.11/deployment-modes/)、[插件装载](https://apisix.apache.org/docs/apisix/3.11/plugin-develop/)、[官方 Kafka logger producer 参数](https://github.com/apache/apisix/blob/3.11.0/apisix/plugins/kafka-logger.lua)。本目录是实现和部署输入，测试记录不将未运行的 APISIX HTTP 端到端验收视为已完成。
# Component verification and coarse budgets

Both management and redirect routes enforce local connection and request limits. The example
limits are bounded protective defaults, not measured QPS capacity or an acceptance target.
Freeze production values from the final hardware and reliability measurements. Batch bodies
are capped at 8 MiB at the edge; Admin applies the stricter ordinary 256 KiB budget and the
bounded aggregate memory budget. Environment substitution belongs in upstream node values; node map
keys are not a supported template mechanism in the verified APISIX 3.11 runtime. Production
Admin and Redirect ports are 8002 and 8003 respectively; no Java Gateway process is deployed.

Reproducible isolated HTTP cases and actual results are documented in
[`doc/integration/component-adapters.md`](../../doc/integration/component-adapters.md).
