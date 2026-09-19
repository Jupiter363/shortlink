# P1 第六批：统计任务读取的结构化错误合同

跟踪 [Issue #62](https://github.com/Jupiter363/shortlink/issues/62)。在 [PR #69](https://github.com/Jupiter363/shortlink/pull/69) 的进程接管与恢复协调合并后，继续补齐已有 job 结果接收的前置合同。本批没有实现完整分页接收，也没有开启新运行入口。

## 问题与修改

已有通用 GET 会丢弃错误码，Admin 的任务代理也会把业务失败改成通用 `C000001`。恢复器因此无法可靠区分当前无权访问、证据过期、结果未就绪、协议缺失和远端不可用。

Agent 新增仅供后端调用的 `readStatisticsJob`／`readStatisticsJobPage`，固定读取原 job 的状态或一页结果。旧适配器默认返回协议不可用，不回退普通 GET、submit 或 recover-existing；没有新增模型工具。当前可信主体必须存在、与上下文一致且不是 SYSTEM。

Admin 继续复用现有 GET 路由和 Analytics 的 POST status/page 路由，保留当前 UserContext 的 tenant／subject／authVersion。任务读取专用分支保留合法业务码，错误信息使用固定文字，不把远端 body 或私密细节返回给 Agent。普通查询、提交与只找回不创建的协议继续使用原路径。

| 情况 | 读取合同 |
| --- | --- |
| HTTP 401／403 | `FORBIDDEN` |
| HTTP 404／405／501 | `STATISTICS_READ_PROTOCOL_UNAVAILABLE`，不能据此推断 job 不存在 |
| 网络错误及其它未承诺的 HTTP 失败 | `REMOTE_UNAVAILABLE` |
| 坏 JSON、缺失包络或不符合成功结构 | `STATISTICS_READ_PROTOCOL_UNAVAILABLE` |
| HTTP 200 的合法业务失败 | 保留原有界机器码，不信任 `success=true` 绕过 code |

真实统计服务把“无此任务”和“当前主体无权访问”统一为 `FORBIDDEN`，没有新增任务枚举接口。现有业务码包括 `SNAPSHOT_EXPIRED`（也可能是 epoch 已变化）、`QUERY_SCOPE_CHANGED`、`NOT_READY` 和 `UNAVAILABLE`；不能把这些状态转换为零行成功。尚未实现的结果释放语义也不能凭空标成已支持。

成功状态必须匹配原 jobId 和实际 state。分页使用真实 DTO 的 `items`、`metrics`、`meta`；空 items 可以是真实零行结果，分页齐全及总行数仍须由后续接收器证明。Admin 额外核对页的 snapshotId 和 pageIndex 与本次请求一致。

当前 Analytics 使用固定每页 500 条的已物化结果页；原 Admin 放行 1–500 与它不一致。本批新后端读取及 Admin 校验统一为 500。这只是传输页大小，没有限定总页数或全集大小，也不改变后续分批续接的设计。

## 验证与剩余工作

仅做受影响范围的后端定向验证，使用 HTTP 替身和当前可信主体 fixture；不启动 Docker、应用服务或真实模型。不进行前端测试或全模块回归。

本批 **15 个不同用例最终全部通过，无失败、无跳过**：Agent 的 `StatisticsJobReadContractTest` 5 例；Admin 同名合同测试 4 例，加 `AgentAnalyticsJobTest` 中直接受影响的 6 个方法。Admin 通过 MockMvc → 实际 Facade／AnalyticsJsonClient → HTTP 替身验证包络和授权，不启动 Spring Boot 应用。

Agent 测试先修正了一处泛型断言编译问题；随后发现当前 Mockito 不能替换 final transport，改用真实有界传输与短生命周期 HTTP 替身，只重跑受影响的 4 例，已通过的正常读取例未重跑。Admin 10 例首次通过，没有重跑 PR #69 或全模块用例。

后续仍需耐久分页账本、游标与 checksum、每次 I/O 的运行资格及授权复核、全页对账和 Artifact 最终发布。固定集合与 scopeProof 由 P2 提供；当前读取成功不代表冻结全集、数据完整、逐目标交付或报告成功。P1 继续实施，新入口保持关闭。
