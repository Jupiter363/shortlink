# 投放分析 Agent JVM OOM 定位报告

调查日期：2026-09-19（Asia/Shanghai）

故障时间：2026-09-19 01:44:30

范围：投放分析 Agent 的排名结果、Graph 状态及检查点序列化。本报告记录定位结果，不代表修复已上线。

## 1. 结论

**本次直接原因是应用代码重复保存大型统计元数据；Graph 的全量状态复制机制放大了占用，256 MiB 的 JVM 堆成为触发边界。**

故障属于 `agent-service` 投放分析链路的内存使用缺陷。现有证据不能认定为 JVM 自身漏洞、Spring AI Alibaba SDK 内存泄漏，也不支持“业务访问量太大”这一解释：触发故障的排名仅包含两条短链、13 次点击。

从安全角度，目前证实的是正常授权查询可以触发服务可用性故障。未发现越权、注入或数据泄漏的证据；本次并非完整安全审计，不据此宣称不存在其他安全问题。

| 层面 | 定位结果 | 本次作用 |
| --- | --- | --- |
| 排名工具与报告状态代码 | 完整来源位置、窗口版本信息被反复放入结果和状态 | **直接原因，已证实** |
| Graph 检查点机制 | 复制完整状态、序列化、反序列化、保存节点输出 | **放大因素，机制已证实；未证明 SDK 实现错误** |
| JVM 配置 | 显式 `-Xmx256m`，未随容器上限提高 | **触发边界，已证实** |
| Docker 容器限制 | 上限 768 MiB；取证时约 430.7 MiB，未被 OOM 杀死 | 本次日志不支持容器 OOM Kill |
| D 盘 Docker 数据位置 | 没有磁盘错误出现在本次异常链中 | 无证据与此次 Java heap 异常相关 |

## 2. 运行时证据

### 2.1 故障发生在哪里

Agent 日志明确记录：

```text
java.lang.OutOfMemoryError: Java heap space
StringUTF16.newBytesFor / AbstractStringBuilder.inflate
ObjectMapper.writeValueAsString
JacksonStateSerializer.writeData
Serializer.cloneObject
CompiledGraph.cloneState
GraphRunnerContext.addCheckpoint
```

异常发生在生成报告后的检查点复制阶段。前端随后收到远端请求不可用，当前排名报告未完整返回。它不能被解释为零访问量。

### 2.2 内存限制与实际状态

| 观测项 | 实测值 |
| --- | --- |
| `jcmd 1 VM.flags`：MaxHeapSize | 268,435,456 字节，即 256 MiB |
| Docker：容器 Memory | 768 MiB |
| Docker：OOMKilled / RestartCount | false / 0 |
| Docker：Agent 实际占用 | 约 430.7 MiB，占容器上限 56.08% |
| `GC.heap_info`：老年代 | 174,764 / 174,784 KiB，约 99.99% |
| Windows 剩余物理内存，事后快照 | 约 7.94 GiB |

本地 Compose 的公共 Java 启动参数仍显式写着 `-Xms64m -Xmx256m`。容器允许使用更多内存，并不意味着 JVM 可以突破这个堆上限。上述数据是故障后的现场快照，不冒充故障瞬间的完整内存曲线。

非强制 GC 的 `GC.class_histogram -all` 显示：LinkedHashMap.Entry 约 142 万个、56.9 MB；byte[] 48.1 MB；char[] 44.8 MB；HashMap.Node[] 24.7 MB；LinkedHashMap 16.8 MB。类型分布与 Map / JSON / 字符串复制异常栈一致。**`-all` 包含尚未回收对象，不能用它单独证明永久泄漏或对象保留关系。**

### 2.3 数据体积在哪里变大

请求：默认分组，2026-09-12 至 2026-09-18，短链 PV 排名前 5。

实际统计只有两行：PV 分别为 8、5；分组整窗 PV 13、UV 13、UIP 1。

| 数据 | 实测体积 |
| --- | ---: |
| LINK_METRICS 原始 API 响应 | 1,375,753 字节 |
| `meta.sourceCut` 的 JSON | 1,212,546 个字符 |
| `meta.manifestVersion` 的 JSON | 161,281 个字符 |
| 新请求 START 检查点 | 22,037 字节 |
| intake 检查点 | 22,513 字节 |
| tool_call 检查点 | 7,451,889 字节 |
| insight_compute 检查点 | 7,452,405 字节 |
| llm_analysis 检查点 | 7,451,985 字节 |
| response_compose 检查点 | 复制时 OOM，未产生本次成功快照 |

七天对应 2016 个五分钟窗口。主要数据量来自这些窗口的来源位置和发布版本证明，不是两条排名记录本身。

原生 `GRAPH_CHECKPOINT` 使用带 Base64 的 `binaryPayload` 保存状态。因此表中的 7.45 MB **不是堆占用的直接等值**：运行时还会有反序列化后的 Map、字符串、字符／字节缓冲和临时副本。业务摘要表 `t_agent_graph_checkpoint` 与它不同，失败后仍停留在上一条成功请求。

## 3. 代码缺陷与机制的边界

### 3.1 直接缺陷：审计元数据复制进每行 quality

出错版本的 `CampaignStatisticsTools.rankShortLinks` 把统计服务的完整 meta 放入：

1. 第一条排名的 `quality`；
2. 第二条排名的 `quality`；
3. 排名结果顶层 `meta`。

Java 中暂时共用对象引用并不能避免 JSON 重复展开。序列化没有按业务引用去重，这一步形成三份完整来源／版本信息。

这些完整 `sourceCut`、`manifestVersion` 用于冻结快照的追溯和校验；它们不是每条排名的独立业务指标。它们必须在服务端保留，并在分页、任务范围和质量校验期间按原合同使用，但没有必要完整复制到每行报告和模型输入中。

### 3.2 直接缺陷：Graph 同时保存四套完整结果

`DefaultCampaignAnalysisGraphExecutor` 首先把工具 data 原样放入 `toolExecutions`，随后 `response_compose` 又生成：

- `cards` 中的完整工具数据；
- `toolCalls` 中的完整执行数据；
- `dataSources.executions` 中的完整执行数据。

原 `toolExecutions` 仍在状态中。`sanitizeForPrompt` 只递归复制并脱敏，没有压缩这些大型字段。对本次两行排名而言，完整来源／版本信息因此会经历约 **3 × 4 = 12 次 JSON 展开**。

### 3.3 次要缺陷：上一轮派生结果未在下一轮输入中清空

出错版本每轮只重置 `toolExecutions`、`derivedInsightCards`、`traceEvents`，没有重置上轮 `cards`、`toolCalls`、`dataSources`。

SDK 恢复检查点时将旧 state 与新 input 合并，未提供的新 key 不会删除旧值。因此这些上轮结果会继续参与新一轮 START / intake 的状态复制。

但本次 START 只有约 22 KB，随后 tool_call 才升到约 7.45 MB，说明**本次主爆点是新排名结果进入状态**。不能把全部责任归给长会话或历史累积。

### 3.4 Graph 机制：全量隔离与持久化，不等于已证明的 SDK 漏洞

核查本地依赖 `spring-ai-alibaba-graph-core 1.1.2.3` 的字节码后确认：

- `GraphRunnerContext.addCheckpoint` 先复制全 state，再交给 saver；
- `CompiledGraph.cloneState` 调用 `stateSerializer.cloneObject`；
- `Serializer.cloneObject` 通过写出再读回完成复制；Jackson 期间生成完整 JSON 字符串；
- 节点输出构建还会复制 state；
- `MysqlSaver.encodeState` 另做序列化、Base64 和 JSON 包装。

这解释了为什么数 MB 的保存内容可能产生远大于保存体积的瞬时堆占用。机制本身承担状态隔离和持久化职责；本次没有证据证明它发生无限递归或错误地递归嵌入全部历史。

MysqlSaver 正常 get 使用最近检查点，数据库查询带 `LIMIT 1`，不是每次把全部历史检查点加载到堆中。其默认缓存最多 1024 个 thread 的最近检查点、按条数 LRU 淘汰，缺少字节预算可能放大大状态的成本；**本次没有采集该缓存的 GC 引用关系，不能把它认定为已证实主因。**

## 4. 受控离线量化

使用出错版本的实际 JAR，在独立 Java 进程中设置 `-Xmx256m`，构造两条业务行、2016 个窗口的合成数据，调用真实 `rankShortLinks` 和 `composeResponse` 方法。没有启动 Docker，也没有执行全 Graph 压力测试或主动制造 OOM。

| 阶段 | UTF-8 JSON 字节数 | 来源／版本字段出现次数 |
| --- | ---: | ---: |
| 合成原始响应 | 768,169 | 各 1 次 |
| 实际排名工具结果 | 2,304,705 | 各 3 次 |
| 实际报告与工具状态合并 | 9,219,536 | 各 12 次 |
| 诊断对照：仅移除两项原始 provenance 后的合并状态 | 6,824 | 0 次，仍保留 snapshot 和质量字段 |

SDK 对单工具状态的实际序列化为 4,067,406 字节，约为普通 JSON 的 1.765 倍。探针正常退出，**没有复现生产 OOM、没有测量生产堆峰值**；它证明的是同一代码路径的数据展开比例。诊断对照直接移除字段只用于归因，不代表最终修复应删除审计能力。

合成输入 SHA-256：`c0cabb4dee18126df5cc95d0c3a4eed409431191dcdf66a475df90656d7b8a21`。完整探针、命令与数字保存在本地 `.work/agent-oom-probe/README.md`、`OomSizeProbe.java`、`size-report.json`。

## 5. 证据版本与核查方式

出错时部署的 Agent 制品：`services/agent-service/target/shortlink-agent-service.jar`，构建时间 2026-09-19 01:40:14。

SHA-256：

```text
6393D7D463B6269CE8F9FB134BD16B8769F26E4046009DAA3F0A57ED025E079A
```

定位使用该制品、当时的容器日志、`jcmd`、Docker inspect / stats、数据库检查点长度，以及对应修复前源码。SDK 字节码核查记录位于本地 `.work/campaign-memory-sdk-evidence.txt`。

之后落盘的 `CampaignEvidenceContext.java` 和 Executor 压缩入口不属于出错制品。用户要求先定位时，已暂停实现与测试；随后用户授权优化、后端测试及 PR 合并。修复验证结果在第 7 节单独记录，不反向作为原始故障证据。

调查后段 Docker Desktop 的 Linux Engine 管道变为不可用，阻止了进一步容器取证。此前内存和检查点数据已完成读取；本次没有启动 Docker、改动内存限制或根据这一后续状态推断新的故障原因。

## 6. 修复建议与验收标准

定位时提出的修复与验收顺序如下，实际完成情况见第 7 节：

1. **修复数据进入 Graph 的边界。** 分页和完整性校验仍使用原始证明；进入解释与报告状态时，将大量内部来源／版本信息转换为数量、摘要与可追溯快照引用。保留完整业务行、指标、期间、授权对象、质量和近似口径。
2. **消除重复存储。** 排名行保存自己的指标，公共质量信息集中保存；来源目录引用证据，避免重复嵌入完整执行结果。每轮重置派生报告输出，仅继承明确的会话选择和服务端续接引用。
3. **再校准 JVM 预算。** 256 MiB 对当前 Spring / Graph 进程偏紧，但只增大堆会推迟同类故障。先量化修复后的峰值，再为 Java 堆、Metaspace、线程和缓冲留出容器余量；不能把 `-Xmx` 直接设成容器上限。
4. **验证真实完整链路。** 使用同一七天窗口、两条短链完成排名、续问、报告生成和检查点持久化；指标必须保持 13 / 13 / 1。记录各阶段序列化体积、堆峰值及 GC，确认不再出现 OOM / 503；同时覆盖对比和多维下钻，确保压缩不丢业务证据、质量或任务引用。

用户最终限定仅使用后端测试，不启动 Docker。因此真实浏览器验收、生产堆峰值和 GC 引用关系不属于本次新增验收结果。本报告没有运行压力测试、删除检查点或业务数据，也不把单元测试通过当成运行环境 OOM 已复测消失。

## 7. 修复与后端验证记录

### 7.1 已完成的代码优化

- `CampaignEvidenceContext` 在工具完成原始分页、范围及完整性校验之后、数据进入 Graph 之前处理溯源信息。只处理顶层 `meta` 和排名／对比行 `quality` 中的 `sourceCut`、`manifestVersion`、`windowVersions`；达到 64 项或超过 4096 字节的大 Map 转为条目数、原始 JSON 字节数、流式 SHA-256 和快照／任务审计引用。未知扩展字段和完整业务行不裁剪。
- 排名公共来源信息不再复制进每一行。对比行来自独立快照，各自保留质量及审计引用；分页校验始终使用未压缩的原始信息。
- `dataSources` 保存工具、范围、快照和 `toolCalls[index]` 引用，不再嵌入第三套完整结果。卡片和工具结果仍提供业务证据。
- 每轮请求显式清空旧卡片、回答、工具调用、来源目录和警告等派生输出；仅续接已确认的范围、意图、选项及服务端任务／分页引用。
- 全量排名分页完成后清除已失效的 `nextCursor`／`nextPageIndex`；近似值警告只在 UV／UIP 合同明确为 `APPROXIMATE` 时出现，不从未知格式推断精确或近似。

本次没有扩大 JVM 堆、删除旧检查点或修改 SDK 缓存策略。数据库已有的大检查点仍需在首次恢复时读取；这次修复约束新写入状态，不包含历史记录迁移。

### 7.2 离线后端回归结果

| 测试 | 结果 | 证明范围 |
| --- | --- | --- |
| 5 套 OOM 相关测试，Surefire `-Xmx256m` | 153 / 153 通过 | 上下文压缩、会话、Executor、排名／对比和下钻工具合同 |
| Agent 完整单元套件 | 678 项；677 通过，1 项 Windows 符号链接条件跳过；0 失败／错误 | 本轮全部 Agent 修改后的回归 |
| Admin 完整单元套件 | 196 / 196 通过 | 转发、授权及查询合同；之后无 Admin 代码变更 |
| Analytics 完整单元套件 | 190 项；189 通过，1 项 Windows 符号链接条件跳过；0 失败／错误 | 聚合、批量证明、成对窗口谓词及异步分页；之后无 Analytics 代码变更 |

OOM 回归使用真实 Graph 和 MemorySaver 执行两轮，每个检查点再使用生产 `AgentStateSerializerFactory` 复制验证。合成排名输入含 2016 个窗口，原始结果 JSON 为 **10,364,057 字节**；修复后测试记录的最大原生序列化检查点为 **18,901 字节**。另外验证 5000 行联合维度结果经过生产序列化后完整保留，整窗 UV 仍为独立汇总，第二轮 START 已清空旧派生报告并保留会话范围。

上述合成输入与第 4 节的旧 JAR 探针不是同一份夹具，不能把两节数字直接作为同数据的性能前后对比；它们均不代表生产堆峰值或数据库实际保存字节数。本轮使用内存 saver 和生产序列化器，未重新验证 MySQL 保存、实际模型服务或浏览器链路。

最终日志为本机忽略文件 `.work/campaign-evidence-bounded-tests.log`、`.work/campaign-evidence-full-agent-tests.log`、`.work/campaign-admin-full.log`。测试计数取各次最终 Maven 汇总，不累计 `target/surefire-reports` 中遗留的历史／集成报告。代码内已保留可复现夹具：`CampaignEvidenceContextTest`、`CampaignConversationTest` 和两个工具测试。

在仓库根目录复现本轮 Agent 测试的命令（本机 JDK 21；GBK 参数用于现有 Windows 编译状态路径兼容）：

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=GBK'
mvn.cmd -o -pl :shortlink-agent-service -am test '-Dtest=CampaignEvidenceContextTest,CampaignConversationTest,DefaultCampaignAnalysisGraphExecutorTest,CampaignStatisticsToolsTest,DimensionBreakdownToolTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dnet.bytebuddy.experimental=true' '-DargLine=-Xmx256m'
mvn.cmd -o -pl :shortlink-agent-service -am test '-Dnet.bytebuddy.experimental=true'
```

沿用根 POM 的 Surefire 排除规则，不启用集成测试 profile；不依赖 Docker。

**交付状态：修复已通过上述后端验收；没有启动 Docker、重新部署 Agent 或宣称运行环境 OOM 已复测消失。** 后续真实链路、历史检查点恢复和 JVM 预算观测仍需单独完成。
