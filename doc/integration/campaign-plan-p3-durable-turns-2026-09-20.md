# P3 耐久多轮探索 · 2026-09-20

关联 Issue #62，接续 E26–E28。原生 Graph checkpoint 是消息投影；MODEL 请求和公开响应、CALL 实际回调、Child／Artifact 事实仍由后端账本管理。

## 本批实现边界

在原生 ReactAgent 之上增加 JDBC ExplorationLedger，以数据库事务分配模型轮次并保存决策、观察及消费位置。复用已有单轮 DurableModelCallBoundary 和独立 CALL 生命周期，不增加模型循环或业务 runner。

- 已保存模型响应、尚未执行 CALL：回放同一轮的冻结请求和响应，不能提前新建下一轮。
- 工具已有真实业务回执：重建对应 assistant／tool 消息，不能重新调用已完成工具。
- 原任务 WAITING：按真实 Child／job 恢复；READY 是新增观察，不能伪造第二个原调用响应。
- checkpoint 缺失或滞后：规范上下文从耐久事实重建；事实消费位置与原生 checkpoint 完成确认分开。
- 每次模型派发继续检查当前 Step、已使用 Artifact 的权限、期限和内容身份。模型文本及工具返回字符串不构成 READY 证明。

`pending_projected` 仅在真实工具 PENDING 回执记账时设置。恢复为 READY 后，已记录 PENDING 的路径保留原 tool 响应并新增稳定 ID 的 READY 观察；直接 READY 的路径只有一个 tool 响应。多个子结果缺少明确输出选择时返回缺口，不擅自挑选一个结果。

观察在下一轮 MODEL 请求准备事务中标记已消费；原生确认字段目前保守地保持未确认，缺少精确 checkpoint 回执时不推断确认成功。累计协议修复次数按同 Run 所有步骤及版本计算，不能通过重建会话清零。

## 最小验收

仅运行 `NativeDurableExplorationLedgerTest` 两项原生 ReactAgent＋H2＋脚本模型后端用例，首次通过；日志 `.work/durable-exploration-turns-tests.log`。

- `emptyCheckpointRecoveryReusesCommittedModelTurnAndCompletedToolBeforeContinuingDependentAnalysis`：第二轮 MODEL 已保存、第二个工具尚未执行时注入真实 saver 故障；新账本对象和空 saver 复用该响应，第一工具不重跑，三个模型槽各调用一次、两个依赖工具各执行一次。原业务载荷留在 Artifact，模型文本中的假 Artifact 不落库。
- `newWriterAndStepResumeOriginalAsyncJobWithOnePendingPairAndOneAuthorizedReadyObservation`：真实 ASYNC Child WAITING 后换 writer，通过原 Child 接收 READY，再取新 StepPermit 继续。原 job 和请求不变、提交一次；历史中原 PENDING 配对与新增 READY 观察各一次。撤销权限时模型／工具调用不增加，恢复权限后可继续。

返回的是 CANDIDATE，测试不宣称 Step 合同或整个用户目标已满足。未重跑已通过且未受影响的测试，未启动 Docker、应用、真实模型、真实数据库或浏览器。

## 后续门槛

本批不开放 Driver REACT 生产入口，不以 CANDIDATE 代替 Step 输出合同或用户目标验收。通用 typed Tool／Skill 绑定、完整运行预算、生产组合与逐目标报告仍需后续接线；真实数据库、真实模型和客户端验收仍待完成。

当前 turn 保留冻结 invocation 副本并校验哈希，规范历史重建仍会读取历史模型 envelope；状态展示只读短字段。历史规模和累计预算的组合上界、精确原生确认、未记录 PENDING 的恢复窗口仍需进一步验收，不能将两项组件测试当作全部 P3 完成。
