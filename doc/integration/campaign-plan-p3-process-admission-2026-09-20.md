# P3 原生推进的进程准入 · 2026-09-20

关联 Issue #62，接续 E42。把已验收的进程容量执行器接到原生探索路径，解决 Future 超时或取消后真实工作仍在运行，却可能提前放入下一个 Run 的问题。

## 实现边界

`AdmittedCampaignAdvance` 共用一个 `ProcessCapacityExecutor`，每次推进原子预留活跃推进、模型、大载荷各一个许可。容量必须由调用方明确配置，不新增默认低上限。排队仅持有有界 `WorkRef`；可信工厂在准入后才读取完整 Run、历史、Artifact，构造 Driver／恢复协调器和模型运行对象。拒绝保留明确未接纳原因，发生于创建 MODEL 派发记录之前。

`ProcessExecutionScope` 跟踪实际生命周期，不另建线程池或模型循环。整个原生 Graph 扫描节点、Driver、探索工厂、模型处理和工具回调共享同一个 scope。探索执行器验证 adapter 的 scope 对象一致，避免接线遗漏。工具在提交执行器前取得 lease，覆盖携带上下文的队列驻留；只能在真实 Runnable 的最外层 finally 归还，包含 durable 退出记录及 Future 完成回调。超时和取消不代替实际退出。退出记录失败也不能遗漏进程 lease 清理。

推进结束先封闭 scope，拒绝迟到节点及新子派发，再等已进入的真实工作退出，最后交还原容量许可。等待过程中收到中断不会提前归还，完成清理后恢复中断标记。真实远端 WAITING 且本地回调已退出时正常归还；再次推进重新准入并复用原任务。

恢复无需另建运行器：可信工厂在取得许可后按短引用读取当前 RunToken、构造恢复协调器，再调用现有 resume。不能在 submit 前捕获完整 RunToken 或预先构造运行对象。旧构造方式仍兼容；本批是显式装配能力，尚未覆盖旧投放入口和风险 Agent 的生产调用。执行器必须运行已接受任务或明确拒绝，不支持静默丢弃已接受 Runnable。

## 验证与剩余范围

4 个定向后端方法首次通过，0 失败／错误／跳过；日志 `.work/process-admission-tests.log`。仅使用 H2、原生 SAA Graph／ReactAgent 与脚本模型，不启动 Docker、应用、真实模型或真实数据库。

| 方法 | 实际证据 |
| --- | --- |
| `ProcessAdmittedExplorationTest#actualNativeCallbackRetainsAdmissionAfterCancellationAndQueuedRunLoadsOnlyAfterExitThenResumesItsOriginalJob` | 三个真实 H2 Run 共用准入。Run1 原生工具超时返回后回调仍在，外部 Future 超时再 cancel，三个许可仍各占 1；Run2 factory／历史／Artifact／模型读取为 0，Run3 满队列明确拒绝且 MODEL 派发为 0。真实 finally 退出后 Run2 才加载，实际 ASYNC WAITING 后全部许可为 0。同 WorkRef、新 writer／空 saver 接收原 job，唯一 PENDING／READY 观察、模型累计 2 次、原子工具提交仅 1 次。远端接收使用脚本工具及真实 ASYNC 账本，未调用完整 StatisticsJobResultReceiver。 |
| `PersistentExplorationDriverTest#admittedDriverUsesOneScopeThroughNativeWaitingAndRejectsALateScan` | 真实外层 Graph、Driver、探索执行器和 Native 共享 scope，下降 Skill 提交原两期任务后 WAITING；三个许可和实际 lease 全部归零，封闭后的旧 Graph 不能重新扫描或新增模型／提交。 |
| `NativeExplorationAdapterTest#queuedNativeTimeoutStillExecutesCleanupWithoutDispatchingTheExpiredCallback` | 受影响旧构造路径：尚在工具队列时超时仍执行退出清理，过期回调不派发。此旧构造用例没有进程 scope，不将其称为独立的带 scope 排队超时验证。 |
| `NativeExplorationAdapterTest#rejectedExecutorBalancesAdmissionAndCannotTriggerAnotherModelTurn` | 受影响旧构造路径的 executor 拒绝保持回调清理，无新增模型轮次。 |

本批不声称已测得真实多 Run 的 RSS 峰值，不据此给出生产容量数值。共享风险 Agent、统一生产请求工厂、真实环境及客户端门槛仍待完成，新运行入口保持关闭。
