# 真实跳转压测失败修复与复测

状态：调查与实施中，尚未形成新的容量结论。

关联交付：APISIX 事件批次就绪扫描优化为 Issue [#21](https://github.com/Jupiter363/shortlink/issues/21) / PR [#24](https://github.com/Jupiter363/shortlink/pull/24)；真实入口、运行副本与发生器生命周期加固为 Issue [#22](https://github.com/Jupiter363/shortlink/issues/22) / PR [#25](https://github.com/Jupiter363/shortlink/pull/25)。本归档对应 Issue [#23](https://github.com/Jupiter363/shortlink/issues/23)。

证据哈希按实施阶段解释：`evidence/tests/native-and-python.json` 固化初始发生器版本，当前提交的 24 个 Go 源文件与其记录一致，随后更新的说明 README 不作为运行输入；停止排空修复后的 `native_lifecycle.py` 及其测试以 `evidence/ext4/native-stop-followup.json` 为准，当前源码与其中哈希一致；APISIX 前缀优化以 `evidence/prefix/tests/index.json` 为准。不同阶段的哈希不相互覆盖，也不用于改写旧运行结论。

已完成的确定性修复：

- 造数接口改走 APISIX 的公开管理入口，使用实际登录 Session。旧脚本直连 Admin 并伪造内部身份头，在单网关版本会返回 403；修复后通过公开 API 创建 267 条数据，并完成 12 项路径检查、64 个来源地址检查。
- 原生发生器在所有请求、连接和 worker 排空后，先发布绑定 PID、启动时间与单调时钟的回执，再进行大 JSON 汇总。外层分别约束请求活动和最多 60 秒的结果导出；失败请求、提前停止、未发请求仍保留失败判定。
- APISIX 运行文件将使用 Linux 原生目录中的逐文件 SHA256 校验副本，保留工作区原件。该变更消除运行时直接读取 Windows 共享目录的因素；是否改善吞吐，以后续对照为准。

当前版本已复测：7k 单次完整 60 秒通过，正式请求 420,000 条，全部正确 302，P99 63.629804ms，三路消息生产交付对账通过。8k 在有、无高频诊断两种情况下都触发 EDGE 队列条数上限，分别拒绝 962、29 条事件；已发 HTTP 均为正确 302，没有复现历史 504。两次 8k 都提前停止，不能用其部分窗口折算值作为稳定 QPS。

基线：main `14273ab`，工作分支 `codex/fix-redirect-load-failures`。承接 2026-09-09 OUT11：7k 两次完整 60 秒通过；8k 曾在同一 APISIX worker 出现 119 个上游建连超时。旧失败记录保留。

## 执行顺序

1. 核对当前三服务生产 JAR、单 APISIX 入口、历史配置与资源身份。
2. 修复压测工具把请求排空后的汇总/文件导出误计入活动超时的问题。活动、排空及导出分别有界，HTTP 失败和未发请求仍判失败。
3. 使用真实创建 API 准备 10 个热点短链接，验证网关到 Redirect 的 302/Location、来源与三路事件链路。
4. 在原应用 CPU 0–7、依赖 8–11、发生器 12–15，以及原缓存/风控/事件约束下，采集单 worker 调度、日志写入与上游连接证据，复现并按证据修复。
5. 先通过 7k 基线，再复测 8k；目标档至少两次完整 60 秒。记录正确完成 QPS、P99、错误/未发、click/BUSINESS/EDGE 和 Kafka 确认、资源及诊断开销。
6. 归档配置、构建、测试和运行证据，停止本轮负载及拥有的服务/依赖并核验资源释放。

## 验收边界

- HTTP 从 APISIX 进入真实 Redirect，核对原始 URL，收到 302 后不访问外部目标站。
- 保留 Caffeine/Redis/权威回源、1000ms 权威租约、500ms 请求预算、业务风控和三路统计；不启用网关 302 响应缓存。
- 不测 Agent、Analytics 下游消费能力，也不把 broker ACK 当作统计入库完成。
- 新一轮使用当前单网关版本，和历史四 JAR 运行的环境差异单独记录；不将跨环境差值归因于单项修复。
- 失败轮完整留档，不通过扩大业务超时、丢弃事件或修改历史门槛宣称通过。
