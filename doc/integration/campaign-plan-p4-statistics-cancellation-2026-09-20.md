# P4 统计任务取消与真实终态 · 2026-09-20

关联 Issue #62，接续 E46。本批把同一统计 job 的取消从 Agent 业务层接到 Admin 与 Analytics 原有取消接口，并为取消意图、唯一 POST 资格、丢回执对账和回调退出建立耐久记录。取消不是模型工具，也不创建新的统计任务。

## 取消链路

Agent 通过 `ShortLinkBusinessGateway.cancelStatisticsJob` 发起专用 POST。Admin 只使用当前可信主体和原 jobId，把请求转发到 Analytics 的 `/internal/analytics/v1/jobs/{id}/cancel`；请求体不接受替代主体、范围或查询。Analytics 在获得任务行锁后重新校验主体、授权 epoch、资源范围和 TTL，解决等待锁期间授权窗口变化的问题。

本地 `campaign_statistics_cancellation` 记录与原 `campaign_statistics_job_binding` 使用同一个 REQUIRED 数据源事务。首次 `NONE → REQUESTED` 且没有活跃 consumer 时，只有一次事务提交可以产生 `PREPARED` 的取消 POST 资格；已有 `REQUESTED` 但没有本地操作回执的记录进入 `UNKNOWN`，只能查询原 job，不能推断还可以重新 POST。HTTP 前后都重新检查当前主体、绑定、TTL、consumer 和运行 token；外层事务和 Future 取消都不能伪造回调退出。

取消回执只接受原 job、正整数原 TTL 和闭合状态。`CANCELLED` 才确认原绑定的取消意图；`SUCCEEDED`、`FAILED` 和已释放结果保持真实终态，操作记录为 `TERMINAL_ALREADY_FINISHED`，不会显示为“已取消”。未知、错误或丢失回执只保留 `REQUESTED/UNKNOWN`，后续恢复读取同一 job；不会重新提交统计查询、创建新 child 或写入业务 Artifact。

## 最小验证

本批只运行直接受影响的后端用例，未启动 Docker、应用服务、真实 Analytics、真实 MySQL、模型或浏览器。

| 测试 | 结果 |
| --- | --- |
| `StatisticsJobCancellerTest#lostCancellationAcknowledgementReopensOnlyTheOriginalStatusAndConfirmsAfterActualCallbackExit` | 通过：丢失 POST 回执后只 GET 原 job；RUNNING→CANCELLED 只确认一次，POST=1，统计原任务提交=1，实际 callback 退出后才允许下一次对账。 |
| `StatisticsJobCancellerTest#realTerminalStatesRemainTruthfulWhileLegacyIntentAndInvalidOrRevokedRepliesNeverGainAnotherCancelDispatch` | 通过：SUCCEEDED/FAILED 保真，历史 REQUESTED 不获得 POST，错误 job/TTL、撤权和环境事务均 fail-closed。 |
| `StatisticsResultReleaseGatewayTest#cancellationUsesOneDedicatedRequestAndPreservesRealTerminalStateWithoutFallback` | 通过：loopback HTTP 身份、固定 cancel 路径、终态/RELEASED 回执、错误码和 404/405/501/403/503 均按合同处理，无备用路由。 |
| `QueryJobServiceTest#cancellationRechecksExpiryAndCurrentScopeAfterWaitingForTheJobLock` | 通过：等待行锁期间过期或 ownership 变化均拒绝取消，任务仍 RUNNING 且 lease/pages 不变。 |
| `QueryJobServiceTest#cancellationFencesInFlightWriterAndPublishesNoPartialPages` | 通过：取消仍与现有 writer fencing 合同兼容。 |

Agent 定向 Maven 运行共 3 个测试方法，0 失败/错误/跳过；Analytics 定向运行 2 个方法，0 失败/错误/跳过；Admin 使用 `-DskipTests package` 完成受影响源码编译。H2 和 loopback 替身不能证明真实 MySQL 锁方言、跨服务部署或进程崩溃后的回调接管。

## 风险与边界

- 取消响应丢失后的恢复只能 GET 原 job，无法证明远端 POST 是否已到达时保持 `UNKNOWN`，因此不会重复 POST。
- 进程死亡且没有可信 callback-exit 证明时保持 fail-closed；本批没有加入跨主机租约或自动接管。
- 仍未注册生产路由、调度器或默认业务入口；新取消协调器必须在现有授权和 consumer 账本装配完成后启用。
- 真实 MySQL 迁移、Analytics worker lease 和生产跨服务 HTTP 仍待环境验收。
