# 双 Agent 真实业务链路补验

> 2026-09-14 后续补验：本文记录当时状态。地域、运营商及首次观测访客维度的补全进展、调用链修复与新证据见[统计维度补验报告](statistics-dimensions-followup.md)。

本轮继续修复前次 Flash 补验中保留的业务阻断。使用旧 NageOffer Vue 管理前端、重构后的后端，以及 DeepSeek 官方 `deepseek-flash`。未运行压测。

**结论：短链创建、真实跳转、异步统计查询、双 Agent 读取，以及风险画像和后台分析的主业务链路已打通。** 本地数据仍缺地域、网络和新老访客维度；风控动作继续受证据与人工审批约束，不以绕过这些约束作为“解除限制”。

## 修复范围

- 投放 Agent 从本轮授权分组列表解析 `default / 默认分组`，把“最近 7 天”转换为上海时区的明确日期，连续执行真实统计工具；长范围复用统计查询 Job。
- 原生投放 Graph checkpoint 加入租户、用户及权限版本，避免不同用户使用相同 session 串联状态。
- 有效统计快照的 PARTIAL 观测指标可以展示，同时披露临时结果、缺失维度及估算算法；异常判断继续使用原有证据门槛。
- 风险画像调度新增受 SYSTEM 身份保护的有界租户/分组发现。候选、窗口和异步分析任务使用该租户当前有效的普通委托身份，并重新授权。
- 不同资源允许独立统计快照；同一快照分页、候选截止时间和画像的三个窗口保持一致。ACTIVE_LINKS 使用显式 REQUESTED 范围。
- 恢复开放以固定的已关闭窗口边界判定，避免新点击不断加入补算任务而无法开放。FAILED 历史记录保留，只有对应窗口的已发布 manifest 完整覆盖原 source cut 才视为已经恢复。
- Kafka AdminClient 显式配置请求超时 5 秒、API 超时 10 秒，修复默认请求超时大于 API 超时导致客户端无法创建的问题。
- DeepSeek 解释节点默认显式关闭 thinking，保持 2000 token / 30 秒预算；空正文、截断和异常结束进入明确失败降级。该配置依据 [DeepSeek 官方 Thinking 说明](https://api-docs.deepseek.com/guides/thinking_mode/)。
- Graph 与 MysqlSaver 共用保留泛型的状态序列化器，修复框架将 `List<RiskReasonCode>` 读成字符串列表后再次保存失败的问题；保留原格式，并验证旧数据兼容及三轮嵌套对象读写。
- 安全 Graph 增加仅包含异常类型、代码位置和数据库错误码的诊断，不记录异常正文、模型输入或密钥。实测定位到完整模型回答写入 `agent_summary VARCHAR(2048)` 的越界问题；完整对话与持久化摘要分别处理。
- 所有五处风险摘要 SQL 写入统一限制为 2048 个 Unicode 码点，超限保留省略标记；完整回答保留在对话和 Checkpoint。测试覆盖中文、Emoji、原字段超限、事件/分组同时保存及幂等重试。
- 旧前端修复空分组列表触发表单异常、异步标题覆盖手工描述，以及成功对话后仍残留“服务不可用”提示的问题。
- 访问记录表适配新统计字段：`occurredAt` 按北京时间格式化，保留已有脱敏 IP 标记，访客哈希仅显示短前缀；旧字段继续兼容，缺失值显示“—”，不补造 HTTP 状态。
- 模型提示明确 TopK 名单长度不等于 UV/UIP，时间戳交由确定性格式化，部分采集的零值不代表真实零流量。

## 本地统计运行链路

```text
浏览器 → Vue/Nginx → APISIX → Redirect → 302 + Location
                        │          │
                        └── Kafka 原始主题 ──┐
                                           ├→ Flink → 派生 Kafka → 官方 Connect → ClickHouse
                                           └→ Worker → MinIO 归档 + 独立 MySQL 控制库
Admin / Agent Tools → Analytics API → 授权范围、快照和统计
```

新增服务使用当前开发 Docker 网络，未替换业务数据库。Flink 1.20.3、Java 17，parallelism=1、TaskManager slot=1；官方 ClickHouse Kafka Connect 1.4.0，tasks.max=1。checkpoint 使用本机持久卷。该运行方式参考 [Flink 官方 Docker 部署说明](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/deployment/resource-providers/standalone/docker/)。

| 新增组件 | 容器内存上限 | 关键配置 |
| --- | --- | --- |
| Analytics API / Worker | 各 512 MiB | JVM Xmx320m，ActiveProcessorCount=2 |
| ClickHouse | 1536 MiB | 查询 max_threads=2，查询内存 512 MiB |
| Connect | 640 MiB | JVM Xmx384m，1 task |
| Flink JobManager | 1024 MiB | process memory 768 MiB |
| Flink TaskManager | 1536 MiB | process memory 1280 MiB，1 slot |

本机 Docker 可见 16 个逻辑 CPU、约 15.6 GiB 内存。容器上限不是实测常驻占用，也不是生产容量承诺。

运行配置和凭据位于被 Git 忽略的 `.work/local-dev/`。组合启动文件为 `compose.yaml` 与 `analytics.compose.json`；`setup-analytics.py` 生成本地配置，`analytics-runtime.py` 执行真实恢复与作业提交。凭据不进入报告、前端或版本库。

## 初始化与恢复记录

- 创建独立归档 bucket，应用 ClickHouse 正式 DDL，并保持两个原始 Kafka 主题为 `LogAppendTime`、各 2 个分区。
- Worker 经 `recovery/begin → reconcile → activate` 获取并核验 Command fence；当前 epoch 为 `d353ea96-8064-464a-9a29-006ef152289b`。
- 首次 ClickHouse 配置/DDL 尚未就绪时出现三个失败构建；后续保留这些失败记录，用真实重建与 manifest 核验恢复。一个尚无替代发布的窗口通过正式 `/worker/rebuild` 重新提交，未直接修改任务状态或伪造发布。
- Flink 作业 `a4655272f70e80e85345a2cc4e3ba0eb` 已进入 RUNNING，并完成多次成功 checkpoint；Connect connector 和 task 均为 RUNNING。
- 21:51，Worker 激活返回 `ready=true`；coverage 同时包含两个原始主题的全部四个分区。
- 服务重建后曾出现 APISIX worker 缓存旧容器地址的瞬时 502；刷新网关后旧前端恢复正常。后续更换容器地址时需要同步刷新本地网关解析。

## 验收结果

通过旧管理前端创建 `http://localhost:19080/68hlRoGwl`，目标为 `https://example.com/?shortlink-uat=business-20260913`，归属 Jupiter 的默认分组。五次独立 GET 均经 APISIX 返回真实 302 与正确 Location。

首次默认七天投放分析已实际执行 `list_groups → get_group_stats`，工具返回两条短链及真实统计；同时暴露模型正文为空与 PARTIAL 卡片空白，继续修复后再作最终验收。该中间结果不计为完整通过。

22:00 的补验已显示 PV=11、UV≈11、UIP≈1，真实模型正文非空，`finishReason=stop`；卡片展示 PARTIAL、provisional 和缺失维度。随后继续纠正模型对 `requested` 窗口名的误解：它不是请求事件计数口径，PV/UV/UIP 使用有效 CLICK 数据。

22:00 闭合批次 `risk-profile:1789308000` 返回 SUCCEEDED，扫描 2 条、生成 2 份画像、失败 0、发布 1 个风险分析 Job。MySQL 中两份短链画像与分组画像均归属于 tenantId=2（Jupiter）。

后台 Job 初次遇到上述 Graph 泛型故障，三次尝试后进入 FAILED。原始状态保存在 [恢复前记录](risk-job-before-redrive.json)。第四次尝试仍失败，状态保存在 [第四次记录](risk-job-after-attempt4.json)；随后真实交互请求通过脱敏堆栈定位 `JdbcRiskEventRepository.saveEvent` 的 MySQL 1406 / SQLState 22001，发现模型回答超过摘要字段的 2048 字符边界。后续仅对此验收 Job 追加运行机会，保留原 attempt/error/trace 证据；最终状态必须由真实 Worker 写回，不直接修改为成功。

风险画像按原有两小时闭合窗口运行。新创建/点击的数据要进入对应闭合批次后才参与画像；不调整系统时间或写入伪造统计来提前验收。

22:34，旧前端的“诊断近 24 小时风险”通过真实授权分组解析，读取闭合画像、调用模型、保存风险事件及 Checkpoint，九个执行节点全部成功。Trace 为 `032d6cad-fa1b-41f9-9378-f08e9098212c`，回答 2752 字符，模型 `deepseek-flash`、`finishReason=stop`；两条事件摘要均未超过 2048 字符。

22:35:16，原后台 Job `risk-job-568dadc9-ecb6-39a2-80e2-bc9f99254ebc` 由真实 Worker 完成第五次执行并写回 `SUCCEEDED`，Checkpoint 保存 2444 字符完整回答。前四次尝试计数未重置；已恢复默认最大尝试次数 3，额外次数仅用于这一条开发验收任务。详情见 [最终 Job 收据](risk-job-after-redrive.json)。

22:37，恢复默认重试配置后再次从旧前端执行投放查询，Trace `2598848d-4a8b-4bf7-b000-01b78bd8ed98` 同轮执行真实分组、聚合统计与访问明细工具。统计为 PV=11、UV≈11、UIP≈1；访问明细第一页 10 条且 `nextCursor` 非空。浏览器表格核对 `occurredAt=1789307564253` 显示为 `2026-09-13 21:52:44`，脱敏 IP `hash: 429a…061d` 正确显示，未知字段为“—”。模型正文不再重复错误的毫秒换算或把 TopK 名单长度当作访客数。

同一时间的运行快照显示 16 个容器运行中，已配置的健康检查均通过；Flink 活动作业完成 107 次 Checkpoint、失败 0，Connect connector/task 均为 RUNNING，Worker readiness 为 ACTIVE。ClickHouse 保存 11 个去重、VALID、状态 302 的 CLICK 事件。交互诊断和后台批任务各写入两条事件，合计四条记录；这不是四条短链。

风险动作节点实际返回 `EVIDENCE_UNAVAILABLE`，没有把证据不足的自动限流报告为已生效；禁止短链、封禁 IP 等仍须人工审批。本轮验证了该拒绝边界，没有验证一次成功的策略下发或人工审批执行。模型建议是解释结果，不代表已执行的策略。

结构化证据：[运行状态与业务收据](business-runtime-evidence.json)、[Checkpoint 摘要](business-checkpoint-evidence.json)。历史 [Flash 接入补验](deepseek-flash-followup.md)保留原样；附件中的早期空回答、失败状态均为诊断过程记录，不计入最终通过结果。

## 测试与边界

| 验证批次 | 结果 | 范围 |
| --- | --- | --- |
| 最终 Agent 定向回归 | 93 项通过，0 失败/错误/跳过 | 9 个套件：投放规划/Graph、风险仓储/服务、摘要边界、状态序列化、安全 Graph、事件落库 |
| 默认风险范围补验 | 78 项通过 | 包含显式范围、默认别名、唯一分组、歧义、越权拒绝及双 Graph；与上行存在重叠，不相加 |
| DeepSeek 适配器 | 23 项通过 | 空回答、截断、异常完成、工具调用兼容、配置预算 |
| Admin 画像授权 | 29 项通过 | SYSTEM 范围发现、普通主体委托、真实 Facade 与 MVC |
| Worker 恢复与 Kafka 配置 | 12 项通过 | 固定恢复边界、失败任务覆盖验证、分页尾部、AdminClient 配置 |
| 前端 | lint / build 通过 | 新旧字段转换及真实浏览器 UAT；保留既有大体积 chunk 提示 |

未运行性能压测，不据此给出吞吐结论。测试仅用当前开发账号和本地两条短链；长期趋势、缺失维度补采、成功策略变更与人工审批不计为本轮已验收能力。风险评分在个位数点击样本上可能产生集中度高分，页面仍说明低基数与证据限制，不能把标签直接解释为攻击事实。
