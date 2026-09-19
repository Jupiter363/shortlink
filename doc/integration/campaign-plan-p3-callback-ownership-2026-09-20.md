# P3 外层工具回调存续 · 2026-09-20

关联 Issue #62，接续 E26/E27。模型调用和工具内部 HTTP／LOCAL 子请求已有耐久身份，但它们不能代表外层工具回调的实际存续。原生超时或父 Step 结束后，真实工具可能仍在运行。

## 本批边界

新增独立 CALL 回调事实，绑定真实已保存 MODEL 响应中的唯一工具调用和冻结的 capability action，复用现有 Run writer／StepPermit 与进程 owner。CALL 返回仅表示回调返回，不表示业务输出完整或分析目标已回答。

全局推进与模型调用必须等待所有 revision 的活跃 CALL 退出。工具自己的子请求仅能携带精确 CallPermit 派发，且每次后续 I/O 和结果发布都需验证父回调资格。超时撤销资格，实际 finally 才释放活跃标记；取消和改版继续立即 fencing，不等待工具配合，也不伪造退出。

崩溃恢复复用原有双快照和进程死亡证明。ALIVE、UNKNOWN 或 owner 缺失均不能接管；只有所有回调的 owner 已证实死亡且前后快照一致，才清除 CALL 活跃标记。未返回的 CALL 保留 `CALL_RESULT_UNKNOWN`，既有子任务和已保存结果不重做。

## 验证

三项定向后端用例通过（H2、真实原生 ReactAgent、脚本模型），日志 `.work/callback-ownership-tests.log`：

- `NativeDurableCallbackGateTest#actualNativeTimeoutRetainsCallOwnershipAfterParentExitAndRejectsFurtherIoUntilActualFinally`：真实 callback 忽略中断；原生超时后 CALL 仍活跃且已撤销。父 Step 退出和 revision 切换都不能提前放行，新模型真实调用仅一次；无父许可／错误 action 的子请求被拒绝，晚到发布不生效。实际 finally 后才允许新 revision 推进。
- `NativeDurableCallbackGateTest#processProofAloneClearsDeadCallAndPreservesReadyArtifactAndKnownJobWithoutDispatchingAgain`：可控进程存活探针下 ALIVE／UNKNOWN 不接管，DEAD 后 CALL 变为未决并记录审计；既有 READY Artifact 与 WAITING job 完整保留，不重新派发。
- `JdbcCampaignRecoveryStoreTest#provedDeadCallbacksAreClassifiedExactlyWhileReadyWaitingAndUnresolvedEvidenceIsPreserved`：扩展恢复快照后的旧账本兼容性通过。

只执行这三项，不启动 Docker、应用、真实模型或真实数据库。H2 和可控存活探针不代表真实 MySQL、操作系统死亡探测或生产链路已验收。

## 后续

这里管理回调身份、派发资格与真实退出，尚不代替 typed 工具参数授权、完整 Skill 能力闭包、耐久探索消息与轮次分配、预算或终评。Driver REACT 和生产入口保持关闭。新迁移需先于探索回调 writer 应用，旧 schema 的兼容不代表可在运行中热切换 schema。
