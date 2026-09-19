# P1 第二批：耐久调用记录、Artifact 与原生计划扫描

跟踪：[Issue #62](https://github.com/Jupiter363/shortlink/issues/62)。前一批独立恢复协议已通过 [PR #65](https://github.com/Jupiter363/shortlink/pull/65) 合并。本批组件不注册为生产入口；P1 仍需完成步骤输出绑定、驱动器接线与进程重启对账，不能据此宣称整个新运行时已上线。

## 已实现的边界

- `CampaignRunStore`／`JdbcCampaignRunStore` 保存不可变 Run 与 Action 定义、所有同步／异步子调用的原请求及摘要，按版本和推进令牌隔离旧调用。取消与新 revision 撤销原推进权，实际 callback 未退出时不能开启下一次派发。
- 子调用在实际 I/O 前写入 DISPATCHING。丢失响应时，同步请求保存 READ_RESULT_UNKNOWN，异步提交保存 SUBMISSION_UNRESOLVED；已有 jobId 的结果未知与提交未知分开。未知调用不能重新走首次派发。
- Artifact 元数据、独立 payload 和子调用 READY 引用同事务提交。读取检查当前主体、授权回调、期限和内容摘要；完整数据不放在 Graph 检查点。定义、请求、Artifact 的字节保护可分别配置，默认 16 MiB／1 MiB／64 MiB，不限制分析目标或报告卡片数量。
- `StatisticsSubmissionReconciler` 使用耐久记录中的原请求调用专用 recover-existing；找回 jobId 只代表任务身份明确，仍需接收并验证数据，不能据此发布 Artifact。取消后的晚到 jobId 仅记录旧事实，不重新激活 Run。
- `NativePlanGraph` 复用 Spring AI Alibaba 原生 StateGraph。冻结图按计划顺序扫描，业务依赖从 Driver 的领域记录判断；A 等待时，依赖 A 的 B 暂停，独立 C 可以完成。再次扫描不重跑已完成步骤，原生 END 仅表示本次扫描结束。
- 图身份纳入主体、会话、Run／Plan／revision、运行器／拓扑版本和冻结计划摘要；状态只保留短引用、状态和计数。图递归保护按实际节点规模计算，不再套用旧固定 16 的值。

## 验证

只执行本批直接相关的 H2、原生 MemorySaver 与网关替身测试；不启动 Docker、应用服务或真实模型。最终 **24 项不同测试全部通过，无跳过**：

| 用例 | 数量 | 重点 |
| --- | --- | --- |
| JdbcCampaignRunLedgerTest | 8 | 重开、并发 CAS、冻结身份、未知结果、真实 callback 生命周期、取消／改版 |
| JdbcCampaignArtifactTest | 4 | 不可变数据重读、授权／过期、原子发布、字节保护 |
| NativePlanGraphTest | 6 | 等待与独立推进、重复扫描、身份隔离、取消、40 步计划、检查点体积 |
| StatisticsSubmissionReconcilerTest | 6 | JDBC 恢复桥接、失败不重提、同步未知、取消后晚到身份 |

首次运行暴露 3 个同源的原生 Graph 兼容失败：框架自动加入 `_graph_execution_id_`，应用检查点白名单未接受该字段，导致下一次扫描失败。仅允许这个原生 UUID 元数据后，只重跑受影响的 3 项并通过，其余 21 项没有重复运行。P1 首批恢复协议的 86 项同样未重复执行。

## 仍待接入

- 生产迁移和配置装配未执行。新增 SQL 为 `services/agent-service/src/main/resources/sql/migration/V20260919__campaign_run_ledger.sql`，仅由后端 H2 测试载入。
- 原生图的 Driver 是可信后端接口；步骤终态／具名输出、实际能力执行、当前授权和领域状态 CAS 的完整接线继续在 P1 推进。现有页面仍走旧运行方式。
- 重新实例化 Store 并不证明旧进程或 callback 已死亡；本批保留 callbackActive，不会通过租约超时或 Future 取消伪造退出。生产进程死亡确认、P0 原生探索的账本适配及调度准入后续完成。
- 跨主体／授权版本复用、固定对象范围、结果释放、复杂重规划和最终报告仍按 P2–P5 原计划推进；本批没有提前开放这些能力。
