# main 合并前验收

日期：2026-09-09 至 2026-09-10。跟踪 [Issue #2](https://github.com/Jupiter363/shortlink/issues/2)，通过 [PR #3](https://github.com/Jupiter363/shortlink/pull/3) 合并 `codex/production-refactor-v07`。

受测代码提交：[ddfb9ec](https://github.com/Jupiter363/shortlink/commit/ddfb9ec39f2f1c3a780f4118104214df276706ef)，Git tree 为 `9c72bab0082175dfc8541331dec581c9c31a1612`；main 对照基线为 `71133e5`。本记录新增后只改变文档，合并前另核对代码树不变。

## 验收结果

| 检查 | 结果 |
| --- | --- |
| Java 默认单元测试 | 11 个模块、830 项，0 失败、0 错误、0 跳过 |
| Python 全量 discovery | 154 项通过 |
| Node 离线回归 | 64 组通过 |
| 元数据抓取定点回归 | 14 项通过；已包含在 Java 模块计数中，不重复累加 |
| 源码与合并模拟 | 受测提交和 1026 份候选输入一致；main 无独立新增提交，合并模拟无冲突且代码树一致 |
| 既有 Kafka / Lua 验收 | 核对来源及输入身份，适用范围见下文；本轮没有重新执行这些集成或压测 |

Java 各模块分别为：event-contract 41、id-generator 19、risk-core 101、Command 86、Redirect 157、Flink 2、Analytics Worker 3、Analytics API 20、Admin 113、Gateway 17、Agent 271。

默认完整回归先完成 9 个模块的 557 项；Flink、Agent 因离线缓存缺依赖而未运行。随后按原 POM 固定版本、原公开仓库补齐依赖，补跑 Flink 2 项、Agent 271 项通过。补跑中的 event-contract 41 项，以及定点的 14 项均不重复计入 830。原失败记录保留，没有把缺依赖的一次 Maven 退出码改写为成功。

## 检查发现与修复

- 元数据抓取的 deadline 可能在 HttpClient5 取得连接端点之前或之后取消请求，引发非预期 `IllegalStateException`。现在仅本次 deadline 已触发时转换为 `IOException`，保留原因；无关异常继续原样抛出。原 150ms 请求预算、900ms 返回断言保留，新增真实连接端点取消与无关异常透传回归。
- 压测器关闭 HTTP 诊断时，`NOT_ENABLED` 曾被误标为字段缺失。现在保留精确的关闭状态，未知或不一致结构仍拒绝。
- 离线 Redis 配置测试补齐新增 Kafka 探针 preflight 的 mock，并断言探针调用及状态写入，不启动实际服务。

## 历史证据的适用范围

失效订阅 18 项单测、2 项真实 Kafka 集成测试对应的 4 个 Java 源文件/测试文件 SHA 与候选一致。另 7 个 Lua 和 2 份部署 YAML 经比对，只有 logger 在 Git 中严格从 CRLF 规范化为 LF；其余原字节相同。三份生成 Lua 程序的 SHA 与历史真实 LuaJIT 输入完全相同。本轮进行了输入比对，没有重新执行 Lua、Kafka 故障注入、E2E 或压力测试。

历史真实网关跳转的 7k 两轮完整 60 秒通过、8k 保留失败，详见[压测归档](../../压测报告/2026-09-09/README.md)。这不等于仓库默认 2 worker 配置的容量证明，也不证明硬件上限、生产 TLS 或完整统计消费者容量。

## 凭据

- [Java 去重验收汇总](java-summary.json)、[Java 完整回归原记录](java-full.json)、[补齐依赖后的补跑](java-dependency-supplement.json)、[元数据定点](java-targeted.json)
- [Python discovery](python-discovery.json)、[Node 回归](node.json)
- [Lua 生成输入一致性](lua-input-identity.json)、[网关历史来源](gateway-source-identity.json)、[Git 换行规范化](git-normalization.json)
- [失效订阅 Kafka 测试来源](bootstrap-integration-association.json)

JSON 保留实际命令、计数和来源标识；其中本机日志/XML 路径用于定位原始证据，这些大日志未随本目录重复复制。GitHub PR 当时没有配置自动检查，此次验收以以上实际执行及源码核对结果为依据。
