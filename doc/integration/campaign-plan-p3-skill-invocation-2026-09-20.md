# P3 耐久 Skill 等待、续接与完成合同 · 2026-09-20

关联 Issue #62，接续 E34。本批为真实非探索 Skill 增加领域级调用合同：保留原 MODEL／toolCall／Action／CALL 身份，允许在已声明依赖齐备之后以新的 callback attempt 继续原能力。

## 实现边界

服务端按注册 Skill 版本及输出合同冻结最终 LOCAL child 和具名输出身份；模型不声明完成条件。阶段等待保存精确子请求回执，同步写入 CALL 返回状态，但活动 callback 仍由实际 finally 退出。

续接要求当前 Run／Step／权限、原 MODEL 与输入仍有效，旧 CALL 已返回且真实退出，冻结等待集全部 READY，使用 invocation version 的 CAS 取得一次新 attempt。仍在执行、UNKNOWN、容量未受理和部分子请求 READY 不等于可以继续。

最终通过真实 LOCAL 多输出和当前授权核验后封存完整结果，不能选第一个 READY child 或任意一个 Artifact 作为整个 Skill 的答案。旧 attempt 的清理不能关闭新 callback。

本批先实现与验收领域组件，不注册真实 Decline Skill 的探索入口，不修改原生消息协议或打开 Driver REACT。Skill 待完成的业务接线与整体观察继续保留在总计划。

## 验证

3 个定向后端方法首次通过，0 失败／错误／跳过。日志 `.work/skill-invocation-contract-tests.log`。未启动 Docker、应用、真实数据库、真实模型或前端。

| 用例 | 关键断言 |
| --- | --- |
| `JdbcCampaignSkillInvocationStoreTest#allOriginalJobsMustBeReadyBeforeOneCompetingContinuationCanPublishAndSealItsTypedLocalOutputs` | 真实 MODEL／SKILL CALL 内提交两期任务；仅一期 READY 仍等待，两期均 READY 后新 writer／Step 竞争同 invocation version，仅一个续接成功。当前撤权、缺必要输出、旧 attempt 清理被拒绝；实际 LOCAL 双输出封存，原任务 submit 总计 2、计算总计 1、MODEL 响应总计 1。 |
| `JdbcCampaignSkillInvocationStoreTest#failedReturnedWriteRollsBackSkillWaitAndDependencyRegistrationBeforeARealRetry` | 真实数据库 CHECK 拒绝 CALL RETURNED 写入，invocation、CALL 与等待回执一起回滚；解除故障后正常等待，不重提原任务。 |
| `NativeDurableCallbackGateTest#actualNativeTimeoutRetainsCallOwnershipAfterParentExitAndRejectsFurtherIoUntilActualFinally` | 旧原生超时／真实 callback 退出门保持兼容，旧 CallStore 路径无需新 Skill 表。 |

新增迁移 v12 的 invocation、等待回执与旧 attempt 归档表。仅新组件依赖这些表；旧首次 CALL 准入仍只接纳 PREPARED，未开放通用重试开关。扫描按同 Action 的短 child ID 逐项读取，不加载整个 MODEL 历史。

对应 P3-03／P3-04／P3-07 与 R15／R16 的 Skill 领域合同基础。真实 MySQL 迁移与锁语义、实际 Skill 业务适配、多输出原生观察及生产组合尚未验收。
