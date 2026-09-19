# P3 协议故障窗口验收 · 2026-09-20

关联 Issue #62，接续 E29/E30。本批只补之前尚未验证的实际恢复窗口和协议修复预算，不重复普通 WAITING、正常多轮或已验超时场景。

## 场景

- MODEL 响应已保存，但探索接纳事务失败：重开可复用真实响应，不重新请求模型，不永久误判协议失败。
- 原 ASYNC job 已提交，但外层 PENDING 回执事务失败：恢复查询原 job；没有已记录的 PENDING 时，仅形成唯一 READY tool 响应，不补造过去的 PENDING。
- 模型反复返回多调用批次：每个未执行 ID 有唯一拒绝响应，真实工具调用为零；修复额度按耐久 Run 记录执行，重新打开不能清零。

## 验证

三项定向后端用例首次通过，日志 `.work/exploration-protocol-recovery-tests.log`；本批仅新增测试及验收记录，没有修改生产实现。

- `NativeDurableProtocolRecoveryTest#committedModelResponseSurvivesAcceptanceTransactionFailureWithoutRepeatingModelOrTool`：真实 CHECK 约束使 `response_hash` 接纳写入失败。MODEL 响应保持 READY、CALL 尚未建立，会话未永久 FAILED；解除约束后新账本／空 saver 复用原响应，工具执行一次，两个逻辑模型槽各调用一次。
- `NativeDurableProtocolRecoveryTest#failedPendingObservationRollsBackProjectionAndResumesKnownJobAsOneReadyToolPair`：RETURNED 写入被真实 CHECK 拒绝，前面的 PENDING 标记也随事务回滚；原 job 和未决 CALL 事实保留。真实回调退出后，新 writer 接收原 job，再以新 Step／空 saver 恢复，只有唯一 READY tool 响应，不虚构 PENDING 历史或额外观察，提交次数保持一次。
- `NativeExplorationRepairBudgetTest#repeatedBatchRejectionStopsAtDurableRepairAllowanceWithoutDispatchOrUnpairedMessagesAfterReopening`：两个违规批次的四个 ID 均有唯一拒绝响应；工具调用零次、模型两次、修复计数一次。模型文本 COMPLETE 不绕过协议；空 saver／新账本也不重置额度或重开执行。

使用原生 ReactAgent、脚本模型和 H2，未启动 Docker、应用、真实模型或真实数据库，未重复执行已通过的普通场景。CANDIDATE 仍不是完整目标验收；真实工具授权与生产组合继续保持门槛。

## 原生循环边界

本地 `spring-ai-alibaba-agent-framework/1.1.2.3` 和 `spring-ai-alibaba-graph-core/1.1.2.3` JAR 经 `javap` 只读核验：

- `Builder.buildConfig()` 未指定外部 CompileConfig 时，偏移 33–36 设置 `recursionLimit(Integer.MAX_VALUE)`；当前 NativeExplorationAdapter 使用此路径。
- `CompiledGraph` 构造器初始的 25 会在偏移 34–37 被 CompileConfig 覆盖；单独 CompileConfig builder 的 100 也不是此路径的最终值。
- `GraphRunner.run` 每次新建 GraphRunnerContext，图步数计数从零开始，包括 checkpoint 恢复。因此跨恢复累计仍必须由 E30 的 JDBC budget 负责。

本批没有因此修改原生循环，也没有新增长循环测试。未来显式传入 CompileConfig 或升级框架时，需要重新核对此配置；图步数包含 Hook／工具节点，不能直接等同于模型轮次预算。
