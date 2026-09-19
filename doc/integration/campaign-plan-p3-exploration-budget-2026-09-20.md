# P3 探索预算与上下文加载 · 2026-09-20

关联 Issue #62，接续 E29。耐久轮次恢复已接通；本批继续约束累计工作量，并减少历史请求前缀在内存和 turn 表中的重复。

## 实现边界

服务端配置模型轮次、工具调用、协议修复和上下文字节边界。同一 Run 的逻辑预留在事务内记账，重放已有槽位不重复计数；重新打开会话、建立新 Step 或切换 revision 都不能清零累计值。配置不由模型参数改变。

默认模型及工具额度各 4096 次、协议修复 32 次；单次规范请求沿用已有 1 MiB 上界。可信构造参数可替换默认配置，同 Run 冻结完整策略。它们是保护边界，不是经生产压测确认的并发容量。新旧构造器均保留。

升级前已有探索轮次的 Run，首次预算初始化从真实 MODEL／CALL／修复事实恢复累计值，不能按空预算重新计算。单次请求大小包含消息、工具 schema、JSON 转义和结构，按 UTF-8 字节检查，不以 Java 字符数代替。

达到边界时保留已经取得的 Artifact，返回明确的 BLOCKED 原因。上下文内容不静默截断，有限证据不被包装为完整分析。这里尚不代表已实现 token 费用计量、多 Run 公平准入或完整无进展策略。

历史 turn 只保留 MODEL 引用和哈希；按需读取真实 MODEL 请求，避免一次常驻所有轮次的完整历史前缀。迁移删除 turn 的冗余 invocation 正文，原 MODEL 账本仍保存真实冻结请求与公开响应。

`V20260920_11` 和新二进制需同步升级：旧探索 reader／writer 不能继续访问删除冗余列后的 schema。未执行实际数据库迁移，生产 Driver 仍关闭。

## 验证

三项定向后端用例首次通过，日志 `.work/exploration-budget-tests.log`：

- `NativeExplorationBudgetTest#completedEvidenceAndModelBudgetSurviveFreshLedgerAndRevisionIncludingBackfillOfExistingFacts`：真实两轮模型与两次工具完成后，第三轮被固定预算原因阻断。新账本、空 saver 和新 revision 都不增加实际模型／工具调用；模拟升级缺少预算记录时，从原 MODEL／CALL／turn 回填 2／2 用量，原 Child 与 Artifact 保持不变。并断言 turn 冗余正文列已移除、真实 MODEL 正文仍在。
- `NativeExplorationBudgetTest#completeChineseRequestIsLimitedByUtf8BytesBeforeAnyModelToolOrCheckpoint`：完整请求字符数低于上限而 UTF-8 字节数高于上限；模型、工具、原生 checkpoint 写入均为零，重新打开仍明确阻断，未保存截断输入。
- `NativeDurableExplorationLedgerTest#emptyCheckpointRecoveryReusesCommittedModelTurnAndCompletedToolBeforeContinuingDependentAnalysis`：仅复验受到历史读取修改影响的三轮依赖恢复场景，继续证明完整消息配对及模型／工具不重复。

没有重复执行其余未受影响用例。验证使用真实原生 ReactAgent、脚本模型和 H2，不启动 Docker、应用、真实模型或真实数据库。修复预算耗尽、全量无进展策略、token／费用、并发容量与实测内存峰值未由本批三个场景证明，仍按对应后续门槛处理。
