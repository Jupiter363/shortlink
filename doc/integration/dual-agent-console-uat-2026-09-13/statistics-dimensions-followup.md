# 统计维度补全与旧前端适配补验

记录日期：2026-09-14。证据来自 2026-09-13 的实现与组件验收，以及 2026-09-14 的本地恢复和真实 API 复核。本文件补充[业务链路补验](business-unblock-followup.md)，不修改之前报告的结论和时间范围。

**当前结论：地域、运营商及有保留期边界的新老访客统计已接入共享异步统计链路；真实 API、固定截止回放、隔离 ClickHouse 测试、已封账空窗口 HTTP Job，以及管理前端和双 Agent 的真实调用已通过验收。后台画像批次成功扫描并生成 4 条画像，失败 0。** 本轮按用户要求验收调用链；模型回复的 schema 与内容留待后续迭代。未运行压测，也未形成生产容量结论。关联 [Issue #13](https://github.com/Jupiter363/shortlink/issues/13)。

## 缺失原因与修复位置

这些维度不是让 Agent Tool 重新采集即可补齐的。Tool 复用 Admin → Analytics API / 查询 Job；原始事件已经携带解释所需的输入，缺口在解释、存储和展示的不同环节。

| 环节 | 原问题 | 本轮处理 |
| --- | --- | --- |
| 共享事件解释 | `country` 原先固定为 `UNKNOWN`，派生契约没有省、市、运营商及其来源状态 | `EventEnricher` 在 Flink / Worker 中使用同一离线 XDB；派生记录增加 `province/city/network/geoStatus/geoVersion` |
| ClickHouse | landing、receipt、rebuild 表和物化视图缺少新增列 | 新装 DDL 与幂等 `004-geo-dimensions` 迁移同步补列、补映射 |
| 官方 Kafka Connect | 运行任务缓存旧表结构；首次升级只执行 DDL，仍可写入 `parserVersion`，新增维度却落成默认值并推进 Kafka offset | 配置 `tableRefreshInterval=60`；迁移后显式重启 connector **及其 tasks**，以实际新列落库作为恢复生产者的条件；同一固定 cut 再回放补齐已消费明细 |
| 查询与历史解释 | 旧、新维度并存时任意选值可能仍取到旧 `UNKNOWN`；原始计数 proof 不覆盖地理内容 | 即时查询和 Job 共用一致的地理元组选取；新 build 增加独立维度 proof，保留原计数 proof |
| 新老访客 | 查询窗口内 UV 无法证明访客此前是否访问过 | 在当前授权短链 / 分组、保留期及固定 source cut 内证明历史覆盖，计算首次观测 |
| 旧 Vue 前端 | 旧图表读取扁平统计对象，新后端返回 `metrics.requested/items/meta`；访问记录改为快照游标分页 | 适配器保留全部维度和质量元数据，正确展开汇总并处理游标；图表说明 ISP、未知值和新老访客口径 |
| 访问记录结果 | 即时查询和异步 Job 漏投影已有 `kind/status`，前端只兼容部分别名 | 两种查询与 CSV 透传真实字段；前端兼容旧别名，未观测状态 `0` 仍显示未知 |
| 投放 Agent 日期 | 单个明确日期未补成完整日期范围，导致只查询分组列表 | 明确单日可规划统计和访问记录；开放范围、非法日期保持拒绝或待补充 |
| 安全 Agent 范围规划 | 分组名解析成功但无画像时，提前终止只读统计请求 | 当前授权 gid 传给已有统计工具；无画像仍提示未知，未知 / 重名 / 越权范围不查询 |
| 风险画像维度 | 网关 DTO、持久化读回和 Tool 上下文只保留旧标量 | 新画像按 `2h/24h/7d` 分别携带维度及覆盖度；从原 `profile_json` 读回，旧画像维度空，原 SQL 标量与评分保持权威 |
| Graph 快照恢复 | 新 `Map<String,RiskWindowDimensions>` 被框架恢复为普通 Map | 为该字段增加类型明确的反序列化，三轮恢复及进入工具上下文测试通过 |

实现入口：

- [事件解释](../../../libraries/event-contract/src/main/java/com/jupiter/shortlink/contract/EventEnricher.java)、[兼容派生记录](../../../libraries/event-contract/src/main/java/com/jupiter/shortlink/contract/EnrichedRecord.java)。
- [维度迁移及部署要求](../../../deploy/clickhouse/README.md)、[ClickHouse 配置静态验收](../../../scripts/integration/component_adapters.py)。
- [访客历史证明](../../../services/shortlink-analytics-api/src/main/java/com/jupiter/shortlink/analytics/api/VisitorHistory.java)、[旧前端响应适配器](../../../frontend/console-vue/src/utils/analyticsStats.js)。

Redirect 不进行同步地理查询，Agent 不新增独立采集器或旁路统计库。投放和风控读取相同统计结果，同时保留各自的授权、证据和受控动作边界。

## 指标口径与质量边界

| 字段 | 口径 |
| --- | --- |
| `countryStats` | IP 数据库返回的 ISO 国家代码；本轮受控样本为 `CN` |
| `localeCnStats` / `topRegionShare` | 中国省份分布及最大省份占比；不把未知地域补为某个省份 |
| `city` | 数据库提供的城市，作为明细维度传递；省级图表不误用城市粒度 |
| `networkStats` | IP 数据库的运营商 / ISP 标签，**不表示 Wi-Fi、移动网络或用户接入方式** |
| `uvTypeStats` | `FIRST_OBSERVED_IN_RETAINED_DATASET`：当前短链或当前授权分组内，在可读且证明完整的历史中首次观测；回溯上限 180 天，不承诺终身首次访问 |

同一访客可以是分组旧访客、另一条短链的新访客。分组 UV 需按访客去重，不能累加各短链 UV；每日新老划分使用每日边界，不能复用整个请求窗口的新老分类。

访客历史只依据 click 流，网关请求日志落后不应单独阻塞首次观测。但 click 分区缺失、历史范围不完整或当前 source cut 缺少证明时，仍返回 `UNKNOWN` / `HISTORY_NOT_COVERED` 等质量原因；不能把未知访客并入新访客。证明可来自连续 offset 前缀，或同一恢复世代、同一 cut 的 Worker receipt 数量证明。

地理字段从同一个元组中选取，优先固定非空 `geoVersion` 的解释，避免国家、省份和运营商分别来自不同记录。发现多个非空地理版本时披露 `GEO_VERSION_CONFLICT`，`topRegionShare` 返回 `null`，不能把任意一个版本当作可信风控证据。

## 固定离线数据与失败策略

本轮使用 ip2region Java 库 `3.3.7`，数据库文件来自官方仓库 `v3.15.0`，固定提交 `5244ebad081e9f32b2113bc08e980489f0e6c6fe`。Java 库版本与数据库仓库 release 是不同概念。

| 制品 | 字节数 | 完整 SHA-256 |
| --- | ---: | --- |
| `ip2region_v4.xdb` | 10,647,009 | `9f9c76a8bcb234d55be3a8d2e4d828f76624470652ab33697b42e5129a1319ec` |
| `ip2region_v6.xdb` | 36,088,996 | `98e8af04c288b16a6a70ca4d0047b54d1e7d51d99f625593f8a843ed6bad331f` |

运行解释版本为 `ip2r-3.3.7-r5:9f9c76a8bcb2:98e8af04c288`，证据源为本地 `.work/local-dev/geo/manifest.json`。运行进程固定读取同一份不可变双栈数据库；发布时验证完整 SHA-256，而非只校验显示用短指纹。

- 四项 IPv4 / IPv6 路径与 SHA 环境变量须同时配置，详情见[部署说明](../../../deploy/clickhouse/README.md)。默认查询并发 4，可配置范围 1–32；无在线 IP 查询服务依赖。
- 未配置为 `NOT_CONFIGURED`；旧记录缺字段保留 `UNKNOWN` 和空版本。非公网输入为 `NON_PUBLIC`；不适用的请求事件为 `NOT_APPLICABLE`。这些状态不冒充地域识别成功。
- 无法识别、非法输入保持明确状态；文件缺失、校验不符、数据库读取或解析故障使初始化 / 当前处理失败，不静默转换成有效地域。已配置数据库的非公网或未命中结果仍带有地理版本，便于区别“确实无法归属”与“新列未写入”。
- 受控双栈样本仅验证数据库与完整链路，不能证明任意真实用户地域都可识别。报告不保存样本地址、原始 IP、访客标识、令牌或模型密钥。

## 历史兼容、去重与发布证明

原始 `ClickEventV1` / `rawClickV1` 保持兼容，`payloadHash`、事件身份和 broker receipt 身份不因新增维度变化；基础 `detailDatasetVersion` 保持 `detail-v1`。旧派生记录和旧归档解释仍可读取。

两种历史处理的作用不同：

1. **有界明细回放**：开发工具按固定 Kafka cluster / topic 身份、分区及 `[start,end)` offset 读取原始事件，只发送两条允许的派生主题。默认 dry-run，不创建 producer；显式 `--apply` 才在整批校验后提交一个 Kafka 事务。它不写 raw、不改 Archive、不提交生产消费 offset，也不创建第二套 Flink 作业。重放增加的是同一事实的派生解释，查询仍按原身份去重；它不替代规范窗口 manifest。
2. **显式规范窗口重建**：默认沿用不可变 Archive 中的解释。只有 `ANALYTICS_REBUILD_ENRICH_DIMENSIONS=true` 时才基于原始归档重新解释地理，先核对身份、hash、校验和其他基础事实，再将地理复制到新 `rebuild_input` / build 并发布新 manifest。原 Archive 与旧行不就地改写；版本不一致的重试被拒绝，需创建新 build。

新 `coverage_proof` 保留原 `n`、`digest`，新增 `dimensionVersion=geo-v1` 和 `dimensionDigest`，后者覆盖 `receipt_id,payload_hash,validation_result,country,province,city,network,geo_status,geo_version`。维度去重数量必须与原 receipt 数量一致，且各副本 proof 一致才能发布；API 与 Job 同样核验。旧 proof 没有新增键时继续执行旧验证，不伪造地理证明。

### 固定 cut 回放实测

本轮 2026-09-13 固定 cut：

| 原始主题 | 分区 | 起始 offset（含） | 截止 offset（不含） |
| --- | ---: | ---: | ---: |
| `shortlink.click.raw.v1` | 0 | 0 | 4 |
| `shortlink.click.raw.v1` | 1 | 0 | 7 |
| `shortlink.gateway.request.v1` | 0 | 0 | 62 |
| `shortlink.gateway.request.v1` | 1 | 0 | 59 |

cut 文件 SHA-256 为 `7485a072abb11cdbd04166dc03fe30ba63dad88ab18bfbaefc96680004d6c521`。执行时仍须核验文件保存的真实 cluster / topic ID，不能仅凭上表在另一个集群回放。

| 验证 | 结果 |
| --- | --- |
| dry-run | 读取 / 准备 132 条，非法跳过 0；未创建 producer |
| 事件分布 | 点击 11，网关请求 121；分别为 `NON_PUBLIC=11`、`NOT_APPLICABLE=121` |
| 两次 apply | 同一 cut、同一 receipt identity digest；各获 ACK 132、事务 `COMMITTED`；第二次在 Connect schema 刷新后完成 |
| 数据边界 | 各报告 `rawWrites=0`、`archiveWrites=0`、`consumerOffsetCommits=0` |
| 逻辑计数 | 回放前原点击 11；回放后原业务组仍 `PV=11/UV=11/UIP=1`，没有因两次派生回放翻倍 |
| 原点击地理结果 | 11 条均为非公网，维度维持未知并解释 `NON_PUBLIC`，不会伪造城市；这是正确结果 |

receipt identity digest 为 `eb401fb797c406868187fc2559a0beed28f1688e2006c62ddf4b27d6829dc606`。证据：`.work/local-dev/geo-before-counts.json`、`geo-replay-cut.json`、`geo-replay-dryrun.json`、`geo-replay-apply.json`、`geo-replay-apply-after-schema-refresh.json`，以及 `geo-stats-1ff4df996bf244bdb86865533a92b12c.json`。这些本地文件不随报告直接发布；此处只摘录非敏感计数和版本证明。

## 已有测试与真实 API 证据

以下为已存在的执行报告，不表示编写本文时又启动了一轮测试。

| 范围 | 已有结果 | 证据来源 |
| --- | --- | --- |
| event-contract 单元测试 | 258，失败 / 错误 / 跳过均为 0 | `libraries/event-contract/target/surefire-reports/` |
| 固定双 XDB 集成 | 1 通过，0 跳过 | `PinnedGeoDatabaseIntegrationTest` 的 failsafe XML |
| Flink / Worker 单元 | 分别 2 / 24，全部通过 | 两模块 `target/surefire-reports/`；包含旧 Archive 兼容、geo replay 身份核验、proof 和恢复边界 |
| Analytics API 当前标准单元报告 | 44，失败 / 错误 0，跳过 1 | `services/shortlink-analytics-api/target/surefire-reports/`；跳过位于 `RepositoryFilesTest`，不重复计入 `java-prep` 副本 |
| 真实 ClickHouse 维度 IT | 3 通过，0 跳过 | `.work/local-dev/geo-api-clickhouse-it.log`，2026-09-13 23:20:18 |
| Agent 定向回归 | 74 通过，0 跳过 | `.work/local-dev/agent-dimensions-tests.log`，2026-09-13 22:59:40 |
| CH schema / Connect 静态检查 | `CHC00-schema-refresh-contract=PASS`，检查 5 个 schema 文件，无网络 / Docker 调用 | `.work/component-results/schema-20260913T151007Z.json` |
| 旧 Vue 构建 / lint | 最后一次构建成功，用时 15.57 秒；已包含首次懒加载竞态与访问记录别名修复 | `.work/local-dev/frontend-dimensions-build-final.log`；定向 lint 通过 |
| 统计与游标适配器 | 11 通过，0 跳过 | `node --test frontend/console-vue/scripts/test-analytics-stats.mjs` |
| Agent 明细前端适配 | 5 通过，含真实状态、旧别名及 `0` / 缺失未知 | `frontend/console-vue/src/utils/agentAccessRecords.test.mjs` |
| 投放日期及工具透传 | 56 通过，0 跳过 | `CampaignBusinessPlanningTest`，2026-09-14 01:13 |
| 访问记录投影 | 新增单测 1 通过；真实 CH 3 项再次通过 | `.work/local-dev/geo-api-clickhouse-it-final.log`，01:14；含即时与 Job 的 `302/307/0` 源值一致性断言 |
| 风控规划、画像与恢复终验 | 9 个测试类共 81 项通过，失败 / 错误 / 跳过均为 0 | `.work/risk-natural-statistics-final-tests.log`，01:22；含 H2 真实存取、旧画像兼容和三轮 Graph 恢复 |

3 项真实 CH IT 分别覆盖：非空即时 / Job 维度与冻结历史、按日首次观测边界、缺失 click 分区时保持未知。它们在隔离测试 schema 中执行，不能当作浏览器 E2E，也不能代替真实 HTTP Job 的任务状态验收。

### 2026-09-14 真实服务复核

Flink 已从之前作业的 checkpoint 恢复；Worker 在原 recovery epoch 保持 `ACTIVE`。没有通过重置 epoch、伪造采集质量或修改封账状态解除业务限制。

| 场景（Asia/Shanghai） | 真实返回 |
| --- | --- |
| 2026-09-13 全日受控样本组 | `PV=3/UV=2/UIP=2`；国家 `CN`、省份 `广东省`、ISP `电信`；分组新访客 2、旧访客 0 |
| 同组 23:09:24–23:09:30 | `PV=2/UV=2/UIP=2`；新访客 1、旧访客 1，历史证明为 `CONTIGUOUS_OFFSET_PREFIX` |
| 同窗另一条短链首次访问 | `PV=1/UV=1`；该短链新访客 1，证明短链与分组分类范围不同 |
| 原业务组历史回放复核 | `PV=11` 保持不变；非公网地域继续未知 |
| 历史记录游标 | 同一 snapshot 分两页返回 10 + 1 条，无重复，无第三页；11 条地域均 `NON_PUBLIC` |
| 新接口访问记录 | 受控样本 3 行均返回真实 `kind=CLICK/status=302`，国家、省、市、ISP 与 `uvType=newUser` 正常透传 |

证据文件位于 `.work/local-dev/`：`geo-stats-a5368545d24b4cf792c3c874597eb40e.json`、`geo-window-a5368545d24b4cf792c3c874597eb40e.json`、`geo-link-window-a5368545d24b4cf792c3c874597eb40e.json`，更新时间为 2026-09-14 00:56。全日地理覆盖为 AVAILABLE，但这些近期查询仍明确返回 `freshness=STALE`、`provisional=true`，且 `collectionQuality=UNKNOWN/PRODUCER_HISTORY_NOT_COVERED`；维度可用不等于采集完整或自动风控已获授权。

### 封账与真实 HTTP 查询 Job

2026-09-13 21–22 点窗口在复核时尚未到 `windowEnd + 24h + 5s` 的封账条件，真实 Job 返回 `NOT_READY` 符合约束。**不得手改 `finalized` 绕过该门禁，也不得把该拒绝描述成 Job 功能缺失。**

另选 2026-09-12 21:00–21:05 的已封账空窗口，完成真实重建及 HTTP Job：任务从 `QUEUED` 到 `SUCCEEDED`；`rowCount=0/pageCount=0`，结果页仍返回 `metrics.requested` 的零值汇总、`dimensionQuality=EMPTY` 和 `historyProof=CONTIGUOUS_OFFSET_PREFIX`。返回 `AVAILABLE/FRESH/COMPLETE`、`provisional=false`，同时保留 `missingMetrics=[producerCollectionCompleteness]` 与 `collectionQuality=UNKNOWN`。

证据为 `.work/local-dev/geo-canonical-job-status.json`、`geo-canonical-job-page.json`。这是空闭窗 HTTP 任务链路通过；非空及跨日维度由上述 3 项真实 CH IT 验证，不混写为非空 HTTP Job 已验收。

## 复现方式

从仓库根目录执行只读 schema 检查；本机 Windows 使用 `py -3`：

```powershell
py -3 scripts/integration/component_adapters.py schema
```

依赖已安装后，可运行对应单元回归；以下不含 package，不覆盖正在绑定使用的 JAR：

```powershell
mvn -B -ntp -pl libraries/event-contract test
mvn -B -ntp -pl jobs/analytics-flink,services/analytics-worker test
mvn -B -ntp -pl services/shortlink-analytics-api test
node --test frontend/console-vue/scripts/test-analytics-stats.mjs
```

固定 XDB 集成需显式设置 `shortlink.geo.fixture-directory` 指向本轮数据库目录，并核对报告未跳过；CH 维度 IT 必须配置独立 `ANALYTICS_DIMENSION_IT_CLICKHOUSE_*` 环境，使用测试类要求的隔离数据库。完整连接参数与凭据不写入报告。实际 CH 定向入口：

```powershell
mvn -B -ntp -pl services/shortlink-analytics-api test-compile failsafe:integration-test failsafe:verify "-Dit.test=AnalyticsDimensionsClickHouseIntegrationTest"
```

有界回放的编译、capture-cut、dry-run、显式 apply 命令见[回放工具说明](../../../scripts/development/geo-replay/README.md)。每次使用新报告文件名，先核对 cut、版本、处理数和 dry-run；Connect 升级次序见[部署说明](../../../deploy/clickhouse/README.md)。本轮 evidence 不能当作另一环境可以省略这些检查的依据。

## 最终浏览器与后台调用验收

浏览器使用旧 NageOffer 管理前端和当前登录身份，通过同源 APISIX → Admin 进入 Agent；工具继续调用 Admin → Analytics API，没有另建采集链路。

| 场景 | 结果 |
| --- | --- |
| 最终前端构建首次打开短链统计 | 即刻显示 `PV/UV/UIP=1/1/1`；广东、中国、电信均为 1 次、未知 0；显示历史范围与实际最早观测，修复首次数据先于懒加载组件返回时显示 0 的问题 |
| 日期快捷筛选 / 历史明细 | 切换“昨天”后正确返回 23:09:24 的真实明细；包含省市、ISP、新访客及脱敏标识；本轮检查无新增浏览器错误 |
| 投放 Agent 明确单日 | trace `35615e72-5982-4587-912b-efc14352fbd7`；6 节点成功，真实执行分组、统计、明细 3 个工具，无调用警告；页面展示 3 行 `302 / CLICK`、省市、ISP 与首次观测类别 |
| 安全 Agent 分组名 + 单日，无画像 | trace `458bb87e-5e14-4665-a850-a01aef23617a`；9 节点成功，复用真实分组、统计与明细工具，保留无画像和统计质量说明；本轮 0 风险事件、0 自动策略 |
| 风险画像批次 | `risk-profile:1789315200` 于 01:39 合法重试后 `SUCCEEDED`，扫描 4、生成 4、失败 0；新画像各自保存三个窗口的维度 |

快照和维度验收的脱敏摘录见 [statistics-dimensions-evidence.json](statistics-dimensions-evidence.json)。旧失败 trace 作为修复前证据保留，不能与最终成功运行混为一轮。回复文字不作为本轮 schema 或表达质量验收。

## 七天历史初始化与画像查询超时

午夜画像需要固定截止时间的 7 天窗口。延迟重试时，该窗口起点已早于 live 七天范围，API 正确要求已发布的不可变历史构建。本地自动补算从第一条归档的时间开始，因此此前空日尚无 manifest。通过现有 Worker 入口，按 2026-09-07 至 2026-09-14 的七个自然日提交七个有界任务，均一次 `PUBLISHED`，覆盖 2016 个 5 分钟窗口。

提交时四个归档分区均从 offset 0 连续覆盖，共 203 条归档记录（14 CLICK、189 REQUEST）。空日依照归档 cut、校验与零记录 proof 发布；未伪造事件、未修改原始日志，`finalized` 仍由既有时间条件计算，生产者采集质量未知继续披露。

历史构建齐备后，原逐窗口 OR 条件使 ClickHouse 在读取数据前超时：`ExceptionBeforeStart`、错误码 159、约 18.7 秒、读取 0 行。现在只合并**同一 build 中连续且已选中的窗口**；遇到其它 revision 或未选窗口仍分段，source cut、版本与副本证明保持原语义。

定向 6 项测试通过，含新版本空洞隔离；真实 CH 3 项再次通过。最终单条短链三个窗口复验：Admin 2.216 秒、Analytics 0.813 秒；CH 查询 317–349 ms、读取 1117 行、无异常。响应约 1.33 MiB，保持原 5 秒 / 2 MiB 调用预算。该数据是功能查询诊断，不是 QPS 压测结果。

非空真实 HTTP 查询 Job 仍需符合自然封账条件；本轮非空及跨日 Job 由隔离 CH 集成验证，真实 HTTP Job 验收采用已封账空窗。未将这些结果解释为生产容量、全量采集完整性或自动风控动作验收。

## 后台风险分析的证据体积与执行终止边界

画像批次成功后，还必须检查其下游风险分析任务。01:39 的首次分析在 `risk_scoring` 前发生 `Java heap space`，原生检查点最大为 15,530,565 字节；任务线程等待 Graph 终态，而心跳仍继续续租。根因是七天完整 `sourceCut`、`manifestVersion` 在分组画像、候选、工具结果等位置重复携带；每条新增维度本身仅约 11 KiB。

现在持久层仍保留完整证据，进入 `ProfileRiskAnalysisContext` 时生成独立投影：保留全部已定义窗口维度、评分、质量、身份、快照和动作凭证，逐窗原文改为独立 `evidenceReferences` 中可校验、幂等的 SHA-256 引用。单证据上限 32 KiB、上下文规范 JSON 上限 1 MiB、候选最多 100；超限明确失败，不截断候选或评分。原生 Graph 的类型封装和状态副本仍有额外开销，上限不等同于 JVM 堆占用。

风险执行参数 `short-link.agent.risk.analysis.execution-timeout-millis` 默认 120,000，批次同时受租约剩余时间约束，交互请求最多 40 秒。总期限涵盖 Graph 订阅、节点执行及最终检查点保存；节点进入/返回、每次事件/快照/摘要写入和策略命令发送前检查执行状态。期限耗尽后取消上游、停止续租，不允许迟到结果提交成功或启动后续动作。

批次每次执行使用独立原生检查点 UUID，从权威画像重建；原业务任务、session、稳定 trace 与动作幂等标识保持。旧巨大检查点保留供审计，无需删库重试。已经发出的远端请求或 SQL 无法撤回，继续依赖原有授权、幂等和数据库 CAS。该投影不解决仓储首次物化完整画像时的峰值内存，也不构成模型回复 schema 迭代。

最终真实恢复：任务 `risk-job-af1b7618-6e57-3598-9f2a-058672dece89` 在 02:11:17 第二次尝试 `SUCCEEDED`，业务 trace 保持 `risk-trace-2a6203d3-a512-4954-ad71-b501949151d9`。新原生线程 `2451fdf3-df37-41a7-8a53-71114edf3e4e` 完成 9 个检查点，最大 473,505 字节，旧失败线程完整保留。Agent 仍使用原 384 MiB 容器 / 256 MiB 最大堆，未依靠加内存通过；重启后的日志没有 OOM 或 `onErrorDropped`。

该次后台分析写入 2 条派生风险事件，2 个策略判定结果均为 `EVIDENCE_UNAVAILABLE`，没有 `COMMITTED` 策略。不能把响应中 `activatedPolicies` 数组的长度直接解释为已生效策略数；这验证了画像存在时，证据门禁仍可拒绝自动动作。

## 合并审查补充的统计边界

- 访问记录摘要复用查询 Job 原有 `MAX_ROWS=200000`，修复新增摘要误限 100,000 行的回归；超过上限拒绝且不污染已累计摘要。
- 可选访客历史证明使用独立 1 秒预算；资源不足或临时不可用时，仅新老访客变为 `UNKNOWN` 并披露原因。当前事实、授权、epoch 和维度证明继续执行原校验；中断及明确授权/证明错误不降级。
- GeoReplay cut 升级至 schema 2，固定与 Worker 相同算法的 `hashKeyFingerprint`。缺指纹、旧 cut 或密钥变化在初始化 Kafka 客户端之前拒绝。捕获 cut 必须采用可信 Worker 配置；指纹只能检测捕获后的变化，不能验证任意历史密钥来源。此前真实回放的旧 cut 作为历史证据保留，重新执行需用新版工具重新捕获。
- 非空归档范围找不到任何 segment 时明确返回 `ARCHIVE_COVERAGE_GAP`，不再将 SQL 聚合的默认首尾值当作覆盖证据。空范围仍合法，非空范围的首尾和内部连续性检查保持；H2 用例先复现缺档被接受，再验证修复后拒绝。

## 最终隔离分支验收

发布分支从 `origin/main` 的 `14273ab` 建立独立 worktree；仅纳入本轮 229 个文件，保留工作区另 82 个性能与图像改动。最终发布副本的 149 份单元测试报告共 1175 项：1173 通过、2 个既有环境条件跳过、0 失败、0 错误。Agent 模块为 435 项（1 跳过），统计 API 为 83 项（1 跳过）；包含授权隔离、七天证据、原生检查点、总截止时间、迟到写入、导出容量和历史证明降级。

真实 ClickHouse 维度集成 3 项、固定 IPv4/IPv6 XDB 集成 1 项均通过且无跳过。前端干净安装、lint、生产构建和 11 项 Node 测试通过；17 项网关配置测试、4 项静态限流配置检查及 CH schema 检查通过。GeoReplay 的 3 个独立 CLI 拒绝场景在创建 Kafka 客户端前退出，无消息写入或 offset 提交。

重启后的旧管理前端再次登录并成功执行安全 Agent；trace `e6f10c5b-ad6c-441b-8974-c9ee3e192eb9` 已持久化 `FINISHED`，实际执行画像、分组、统计、明细 4 个工具。浏览器复用了此前会话，因此轨迹区也包含该会话旧 trace 的节点；本轮新 trace 的调用结果与旧失败记录分别核对。当前批次画像与指定日期统计可能使用不同窗口，仍按各自时间与质量披露。

本仓库当前没有 GitHub Actions 工作流，不能将本地通过写成远端 CI 已通过。Issue / PR 的合并检查将以已验证的分支内容、远端可合并状态及脱敏证据为依据。

最终 API / Worker 构建部署后，投放 Agent trace `8bde4a05-d267-49cd-942e-64730ca996a4` 再次完成 6 节点、3 工具，零调用警告；页面显示 `PV=3/UV=2/UIP=2` 与 3 条真实 `302 / CLICK` 明细，国家、省市、ISP、新老访客均正常。02:22 的运行证据显示服务健康、统计门禁 `ACTIVE`，Flink 173 次检查点完成、0 失败，原 recovery epoch 保持。公开证据中的完整 source cut / manifest 列表以条目数、字节数和 SHA-256 摘要表示；本地保留完整版本，不把数万行重复逐窗元数据加入 PR。
