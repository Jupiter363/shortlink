# P3 Skill 混合等待与容量续接 · 2026-09-20

关联 Issue #62，接续 E40。修复同一 Skill 中部分统计请求已受理、其余请求被明确拒绝受理时，父调用无法恢复的问题。

## 原因与协议

原业务循环优先返回 WAITING，但父 Skill 只接纳全部 READY／WAITING 的子调用。另一个子调用为 PREPARED／QUERY_CAPACITY_EXHAUSTED 时，保存等待合同的事务被拒绝，父 invocation 仍为 RUNNING。原 job 没有丢失，缺少的是父调用的可恢复状态。

现有等待合同增加两类服务端依赖：JOB_READY 固定真实 job 与原请求；CAPACITY_DUE 固定明确未受理的原 child、attempt、请求摘要、容量类型、拒绝次数及退避时间。未知提交、缺失或变更的拒绝证明、活动回调均不能取得普通续接资格。全部未完成子调用必须被合同覆盖；已有 READY 结果和 producer 保留。

有真实等待任务时保持 WAITING；全部为容量拒绝时使用 DEFERRED，外层映射 BLOCKED／REMOTE_CAPACITY，不伪造 job。原任务全部接收且容量依赖到期后，在新 Step attempt 中续接同一 CALL。只推进原未受理请求；再次等待时保存新一轮依赖。新模型调用仍须等待 Skill 的真实具名完成产物。

读取恢复提示及实际领取续接均重新检查持久依赖、来源 MODEL、当前权限与真实回调退出。提示不构成执行许可。沿用原 Graph、ReactAgent、统计提交协议和 Step 容量恢复，不新增模型循环或自动更换计划。

## 兼容与验证边界

新增 v16 增量迁移。`JdbcCampaignSkillInvocationStore` 仅在显式 `capacityWaits=true` 时使用容量依赖列；旧构造器保持原 JOB-only 路径，不要求旧 fixture 加载 v16。生产入口仍关闭。

迁移使用的 `DROP CONSTRAINT` 在 MySQL 8.0.19 起受支持；这里只核对了[官方语法说明](https://dev.mysql.com/doc/refman/8.0/en/alter-table.html)，不代表真实数据库迁移已运行。CHECK 对可空证明字段显式要求非空。

## 最小验证

4 个定向后端方法首次通过，0 失败／错误／跳过；日志 `.work/skill-capacity-tests.log`。真实 SAA Graph／ReactAgent、H2 和脚本网关共同执行。

| 用例 | 实际断言 |
| --- | --- |
| `SkillCapacityContinuationTest#mixedAcceptedAndCapacityDeferredJobsResumeTheSameCallTwiceWithoutResubmittingReadyEvidence` | A 已受理、B 明确容量拒绝；B 到期但 A 未完成时不派发。接收 A 后只重提原 B，保留 A 原请求、job、完整 Artifact 与回调记录。B 再等待、接收、恢复后 CALL attempt 1→2→3，原生候选经后端验收发布双输出并推进真实依赖步骤；总 model=2、submit=3（B 首次未受理）、pageRead=2、continuation=2，重开重扫不重做。 |
| `SkillCapacityContinuationTest#unknownSubmissionMissingProofAndChangedCapacityProofNeverCreateAnOrdinaryContinuation` | 丢 ACK、外部拒绝回执缺 `admitted=false`、持久拒绝证明的容量类型被替换三场景，均不领取新 CALL attempt、不再次提交、不发布 Step 输出、不运行下游；原受理任务／已接收产物保留。缺回执场景不是删除持久 deferral 的测试。 |
| `SkillCapacityContinuationTest#pureCapacityWithoutAnyAcceptedJobStaysDeferredUntilDueThenWaitsForRealOriginalJobs` | 零受理任务为 DEFERRED→BLOCKED／REMOTE_CAPACITY；到期前零续调，到期后沿两原请求提交，得到真实 job 才转 WAITING，接收后继续原 CALL 完成。model=2、submit=4（两次首次未受理）、pageRead=2、continuation=2。 |
| `PersistentExplorationDriverTest#nativePlanDriverResumesTheOriginalSkillAndSettlesVerifiedOutputsBeforeAdvancingTheTypedConsumer` | 受影响旧 JOB-only／v12 路径保持兼容，真实原任务续接、后端具名输出发布和依赖消费正常。 |

未启动 Docker、应用、浏览器或真实模型；H2 与脚本网关不替代真实数据库和跨服务验收。未知提交的已有恢复协议没有放宽，本批也不宣称完成 UNKNOWN 的自动对账接线。没有新增单独的删除证明、容量重拒多轮、真实 MySQL 或跨 revision 采用验收。
