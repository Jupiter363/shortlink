# 投放分析工具链与上下文验收（2026-09-19）

关联 Issue #59。按用户确认顺序完成多期间／多对象与续接、对比／排名、联合维度下钻。本次范围不包含费用、转化、ROI、UTM 归因或安全风控写操作。

## 查询与结果合同

- 规划器保存已解析的对象、期间、意图和筛选，逐轮重验授权；会话按用户名、租户、授权版本和 session 隔离。改变范围后废弃旧续页／任务引用，趋势问题不因“访问”一词自动拉取明细。
- 比较把对象与期间展开为独立查询，最多 16 个组合；每行保留来源窗口及质量。差值由程序计算；零基期、未完成／不等长／重叠期间、PARTIAL 或版本不一致时不输出确定的增长率。
- 短链排名读取完整授权范围后排序，支持 PV／UV／UIP。整窗 UV／UIP 独立去重，不累加每日值或各短链值。完整性、分页或 PV 对账失败时不发布确定排名。
- 下钻支持 day、hour、weekday、country、province、device、os、browser、isp、refererDomain；每次最多 3 个联合维度、8 个筛选，每项最多 20 个精确匹配值。结果来自同一批去重有效 CLICK；分母及 UV／UIP 以筛选后的整窗独立计算。
- UNKNOWN、NOT_APPLICABLE 和已知值分别呈现。ISP 不表示 Wi-Fi／蜂窝网络；离线 IP 地域不表示人的真实位置；空来源域不自动归类为“直接访问”。
- 长查询使用已有异步任务，保留服务端返回的任务和冻结条件；用户可用“查看分析结果”续接。单轮只查一次任务状态，不循环轮询。
- 下钻完整结果最多 5000 桶，超过上限整体拒绝，不展示截断结果。页面保留完整结果并分页；解释模型最多接收明确标注的前 50 桶样本，不能把样本当作完整分布。

## 本地取数修复

### 系统诊断表合并占用查询内存

ClickHouse 25.3 的 `system.metric_log` 有 1212 列；本地 Horizontal 合并在读入行前已占用约 1.72 GiB，触发服务端 3 GiB tracker，业务小查询出现 MEMORY_LIMIT_EXCEEDED。

保留容器 4 GiB、服务端 3 GiB、单查询 1 GiB 及 D 盘数据位置。仅对该系统日志表设置较小合并块，并把 `vertical_merge_algorithm_min_rows_to_activate` 调为 1。实际运行中的两组 Vertical 合并内存分别为 3.68 MiB 和 5.66 MiB。配置已持久化至 `deploy/clickhouse/development-memory.xml`；没有删除日志或业务数据。

设置依据：[ClickHouse 25.3 合并算法实现](https://raw.githubusercontent.com/ClickHouse/ClickHouse/v25.3.6.56-lts/src/Storages/MergeTree/MergeTask.cpp)、[系统日志表 settings 实现](https://raw.githubusercontent.com/ClickHouse/ClickHouse/v25.3.6.56-lts/src/Interpreters/SystemLog.cpp)。

### 冻结快照校验的逐条网络调用

七天数据包含约 2016 个五分钟发布窗口。原同步查询逐 build 发起原始事实及维度摘要校验，实测单次授权 LINK_METRICS 查询耗时 35.27 秒，超过 Admin 和 Agent 的 5 秒请求期限；返回事实为 PV 13、UV 13、UIP 1。

同步查询按每批 256 个 build 读取原始／维度摘要，2016 个窗口最多 16 次校验请求。每个 build 仍逐项对照发布证明；空 build 必须满足零计数和零摘要，非空缺行、冲突证明、未知／重复返回行均拒绝。未跳过校验或延长调用链超时。

首次部署后 LINK_METRICS 暖调用为 3.36 秒。随后完整 METRICS 仍超时：ClickHouse 在执行前展开大量窗口 OR 条件，日志记录 19–29 秒的 TIMEOUT_EXCEEDED。同步与异步现共用精确 `(build_id, window_start)` 集合谓词；少于等于 16 段时保留相邻窗口压缩，更多段改用成对 IN，避免混用版本／窗口。异步原 180 天和 4 MiB SQL 预算不变。

### Agent 堆内存不足排查

2026-09-19 01:44:30，真实短链排名在 Graph `cloneState → JacksonStateSerializer.writeValueAsString` 处出现 `OutOfMemoryError: Java heap space`；前端最终收到 503。

只读证据：

- Agent 容器上限 768 MiB，事后实际占用约 430.7 MiB，`OOMKilled=false`、`RestartCount=0`；此次不是容器被内核杀死。
- `jcmd VM.flags` 显示实际 `MaxHeapSize=268435456`（256 MiB），本地 Compose 公共 Java entrypoint 仍是 `-Xmx256m`。提高容器上限不会覆盖这个显式 JVM 参数。
- `GC.heap_info` 显示老年代 174,764／174,784 KiB，接近 100%。非强制 GC 的对象直方图中，LinkedHashMap.Entry 约 142 万个／56.9 MB、byte[] 48.1 MB、char[] 44.8 MB，与 JSON／Map 复制栈一致；该直方图包含尚未回收对象，不能单独证明长期泄漏。
- 本次 LINK_METRICS 原始返回 1,375,753 字节，只有两条业务结果。`sourceCut` 占 1,212,546 个 JSON 字符，`manifestVersion` 占 161,281 个字符；主要体积来自 2016 个窗口的内部来源位置与版本信息。
- 排名把完整 meta 放入每行 quality 及顶层 meta，随后 Graph 又生成 cards、toolCalls、dataSources 中的独立副本。
- 原生检查点（`GRAPH_CHECKPOINT`）本次查询前约 22 KB，tool_call 后 7,451,889 字节，insight_compute 和 llm_analysis 后均约 7.45 MB。随后 response_compose 的检查点复制失败；业务摘要表 `t_agent_graph_checkpoint` 仍停在上一条成功请求。两张表的作用不同。

已证实的直接链路是：大量内部快照信息重复进入 Graph 状态 → 生成报告时继续复制 → 256 MiB 堆无法分配序列化临时字符串。Docker 数据迁移位置与此次 Java heap 异常无直接关系；此次证据不支持把故障归因于物理内存耗尽或已证明的长期泄漏。修复应先压缩内部审计元信息、保留真实指标与质量及快照引用，再按运行观测决定 JVM 堆预算。

本次已经在完整校验后、入 Graph 前将大型内部来源／窗口版本 Map 转为流式摘要及审计引用；去除排名行的公共溯源副本，来源目录改为工具引用索引，每轮重置旧派生报告。业务行、整窗指标、质量和会话续接范围完整保留。另修正全量分页后的失效续页指针及近似值警告。完整归因、量化及修复边界见 [JVM OOM 定位报告](../development/agent-jvm-oom-investigation-2026-09-19.md)。

## 自动验证

- Agent 最终完整单元套件：678 项，677 通过，1 项 Windows 符号链接条件跳过；0 失败、0 错误。OOM 定向 5 套测试使用 `-Xmx256m`，153／153 通过，属于完整套件的子集，不重复计入总数。
- OOM 回归覆盖真实 Graph 两轮、2016 窗口溯源和生产序列化器复制；10,364,057 字节的合成排名输入压缩后，最大原生检查点 18,901 字节。另覆盖 5000 行联合维度完整保留。该结果不代表生产堆峰值或 MySQL 保存字节数。
- Admin 对比、排名、联合下钻转发及授权合同：30 项通过；最终完整单元套件 196 项全部通过。
- Analytics 最终单元套件：190 项，189 通过；1 项外部符号链接保护测试因 Windows 条件跳过。新增批量证明校验 40／40、同步查询 5／5、成对谓词与异步范围测试 13／13。
- 用户限定仅后端测试之前，隔离 ClickHouse 多维集成套件 5／5 通过。夹具使用独立数据库 `shortlink_dimensions_it_20260919`，只复制 `event_receipts`、`rebuild_input` 表结构并写入小型样例；未复制业务数据、运行压力测试或清理业务库。该隔离数据库保留，本轮没有重新运行。
- 用户限定仅后端测试之前，前端 `npm.cmd test` 116／116，lint 和 build 均通过；其后没有前端改动，也没有重跑前端测试。新表格采用文本插值，已覆盖零基期、部分／等待状态及切换卡片后的页码重置。

按用户最新要求，OOM 修复阶段仅执行后端测试，没有启动 Docker 或重做浏览器验收。此前排名浏览器请求暴露了本报告的 OOM，不能算成功验收；本轮不宣称已完成新制品部署或真实环境故障复测。

## 已知边界

当前业务历史可能返回 PARTIAL、UNKNOWN 或采集质量警告。工具成功读取快照只证明返回的数据可用，不代表采集完整；页面、对比增长率及模型解释须保留这些限制。

Issue #59 仍跟踪更广的统一结果状态、解释型历史摘要及导出报告合同，本轮不将这些未覆盖事项标记完成。
