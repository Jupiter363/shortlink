# P3 首个真实统计探索工具 · 2026-09-20

关联 Issue #62，接续 E31。耐久原生探索已验证模型／回调／观察及恢复；本批接通真实 `statistics_query_job/1` 业务链，复用已有固定统计路径的校验、提交和结果接收协议。

## 本批边界

模型局部参数只描述输入引用与已注册的参数，不能提供任意 SQL、URL、executor 版本或自行注册能力。INPUT 必须属于当前探索步骤的授权冻结输入；ARTIFACT 除当前可读外，还必须在调用模型的冻结输入集合中可见。首次提交使用仍存续的真实父 CALL 许可；接收已提交任务使用 Receiver 新建的对账许可，不要求已经退出的旧 CALL 重新变为运行态。两条路径每次业务 I/O 都重查冻结请求与当前权限。

统计工具使用真实 CALL action 创建稳定子请求，提交后保存原 job。结果沿现有 StatisticsJobResultReceiver 完成分页和 StatisticsJobPages Artifact 发布，再由探索账本继续原生模型。重开实例不能重新提交已有任务。

提取复用现有 StepBindings 和冻结统计 descriptor 逻辑，不伪造 FIXED Step 或新的 Run。既有 FIXED 统计接口与行为保持兼容，生产 Driver REACT 仍关闭。

## 验证

4 个定向后端方法首次通过，0 失败／错误／跳过。日志：`.work/exploration-statistics-tool-tests.log`。使用 H2、原生 ReactAgent、脚本模型和脚本统计网关；没有启动 Docker、应用、真实模型或真实数据库。

| 用例 | 实际断言 |
| --- | --- |
| `NativeStatisticsExplorationToolTest#realStatisticsCallReceivesAllPagesAndNewWriterWithEmptyCheckpointContinuesWithoutResubmission` | 真实 typed INPUT／MODEL／CALL／ASYNC 提交一次，等待期间模型不推进；旧 Step／CALL 退出后新 writer 接收 500＋1 条数据，重建 Step／账本／空 saver 后继续。模型共两次，原 PENDING 与唯一 READY 观察配对，重复扫描零提交／读页。结果完整接收仍保留 PARTIAL／UNKNOWN，最终只为 CANDIDATE。 |
| `NativeStatisticsExplorationToolTest#closedBindingsCurrentAuthorityAndModelArtifactVisibilityRejectBeforeAnyStatisticsIo` | 未注册参数、Step 未暴露但当前有权的 INPUT、期间不符、模型返回前撤权、当前可读但源 MODEL 不可见的真实 query Artifact，五种情况均实际进入 CALL 后拒绝；ASYNC child 与统计网关调用均为零。 |
| `StatisticsJobFixedExecutorTest#firstSubmissionIsFrozenBeforeIoAndPublishesOnlyAfterAllPagesThenReusesReadyOutput` | 提取公共 descriptor／submit 逻辑后，原 CURRENT_GROUP 固定步骤仍冻结请求、全页发布及 READY 复用。 |
| `StatisticsJobFixedExecutorTest#frozenMembersRecoverOnDedicatedPathAndPublishProofWithoutClaimingParentCompleteness` | 原 FROZEN_SET 固定步骤保持嵌套范围、专用路径、丢 ACK 后恢复原请求及局部范围证明。 |

新原生工具主例使用 CURRENT_GROUP；FROZEN_SET 本批验证的是受提取影响的既有固定路径，不宣称已实测探索工具的冻结成员全链。没有重复运行无关回归。

恢复目标通过短 CALL／Action／Child ID 索引后逐项重建完整 ChildSpec，不解码全部历史 MODEL 请求。`parent_call_*` 表示当前子调用 attempt；独立对账后这些字段可以为空，因此归属校验使用真实 CALL、源 MODEL 冻结输入及稳定 child/request/wire 全等证明。

对应风险：R03 的真实任务／观察恢复、R05 的原请求协议复用、R04 的短索引加载，以及 V04／P3-02–03 的当前权限与类型绑定。Skill／全运行装配未覆盖，风险不整体关闭。

## 后续

首个工具只覆盖单个统计 job 的真实产物通路。模型观察仍是受信产物引用，尚未接入统计事实投影，不能把本批机制验证解释为模型已阅读完整统计内容或分析质量通过。非探索 Skill 的多子请求／多输出、容量拒绝后的 CALL 继续、步骤合同与目标报告终评仍需继续接线，不能因工具返回 READY 宣称用户目标完整。
