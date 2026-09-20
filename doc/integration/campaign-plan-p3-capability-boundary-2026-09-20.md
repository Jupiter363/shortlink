# P3 固定能力与局部 CALL 共用执行边界 · 2026-09-20

关联 Issue #62，接续 E33。为把既有多步骤分析 Skill 接入真实局部 CALL，本批提取固定执行已经使用的子请求与 LOCAL 计算协议，供两种上下文共同调用。

## 本批边界

`CapabilityExecution` 仅暴露资格检查、子请求、LOCAL 计算和关闭当前上下文，不包含规划、模型循环或重试调度。`CampaignStepExecution` 保留既有 Step／输入 API；`CampaignCallExecution` 使用真实 CallPermit／CallSpec，所有子请求必须归属该 CALL Action。具体 HTTP、LOCAL 原子发布、UNKNOWN／READY 复用、容量回执、迟到 job 留存和实际 callback 退出由同一 `CampaignChildExecution` 实现。

统计探索 Tool 迁移到这个共同入口，保留 typed 输入、源 MODEL、当前查询权限和原生 dispatch 检查。局部 Skill 后续可接相同接口，不需要伪造固定 Step、另建 Action 或复制执行协议。关闭 Java 上下文不冒充外层 CALL 的真实退出。

本批不实现已返回 CALL 的新阶段 attempt、多 child 的完整 Skill receipt／多输出模型观察或 Driver REACT。子请求／LOCAL 输出已有真实完成事实，也不等于整个 Skill、Step 或用户目标完成。

## 验证

4 个定向后端方法首次通过，0 失败／错误／跳过。日志 `.work/capability-child-boundary-tests.log`；没有运行 Docker、应用、真实模型、真实数据库或前端。

| 用例 | 关键断言 |
| --- | --- |
| `CampaignCallExecutionTest#realCallPublishesSyncAndAtomicLocalEvidenceReusesReceiptsAndFencesForeignOrRevokedWork` | 真实 H2 MODEL 响应／CALL 准入后，同步证据只读一次；注册 LOCAL 基于该证据计算双输出，实际第二 payload 插入被 CHECK 拒绝时两输出和 READY 全部回滚，同 CALL／child／冻结输入重放完成，READY 再次复用零计算。ASYNC 保持原 job，重复进入 submit 总计一次。跨 Action 与撤权均在 I/O／计算前拒绝，真实 finally 退出各 callback。 |
| `NativeStatisticsExplorationToolTest#realStatisticsCallReceivesAllPagesAndNewWriterWithEmptyCheckpointContinuesWithoutResubmission` | 迁移到共享 CALL 边界后，原生统计 501 行两页、新 writer／空 saver 恢复及唯一观察仍通过，submit=1。 |
| `LocalCalculationExecutionTest#rollsBackBothOutputsThenReplaysFrozenInputsAndReusesReadyOutputsAcrossParentRecovery` | 固定步骤的 LOCAL 双输出原子回滚、UNKNOWN 重放、父步骤恢复和 READY 复用保持兼容。 |
| `PersistentPlanDriverTest#cancellationInsideChildRejectsFurtherIoRetainsLateJobAndPublishesNoOutput` | 固定步骤取消后阻断后续 I/O，迟到 job 保留且不发布成功。 |

容量分类／退避调用仅迁移原分支，本批没有扩大到九任务容量回归，也没有证明 CALL 已返回后的容量续接。`IoBoundary`／`LocalBoundary` 改成同名接口，仓库所有适配器一并重新编译通过；它们不进入持久化 Graph state，不支持仅替换单个 class 的混合二进制部署。

对应 P1-06／P3-07、R15 的统一子调用复用与 LOCAL 完整输出基础，以及 R16 的执行资格边界；真正多阶段 Skill 续接和整体完成协议仍需后续实现。
