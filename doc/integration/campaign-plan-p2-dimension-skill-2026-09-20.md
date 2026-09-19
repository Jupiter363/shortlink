# P2：原生 Graph 的筛选后联合维度 Skill

日期：2026-09-20，关联 Issue #62，承接 E20/E21/E22。

## 真实依赖绑定

`dimension_change@1` 使用固定 Skill 注册，静态定义只保存范围／期间／方法 pin／联合维度及过滤配置。入选集合和筛选证据必须来自同一直接依赖步骤的两个具名 `STEP_OUTPUT`，不预填尚不存在的 Artifact ID。构造 Skill、检查授权和准备接收目标时，上游未完成只是依赖未就绪，不阻止 NativeGraph 执行上游。

上游 SUCCEEDED 后，通过 `StepBindings`、实际步骤输出、当前 Artifact 合同及 `inspectPair` 校验真实来源；日期、原 gid、原范围和期间引用均须一致。仅在执行时经真实 LOCAL 发布派生 SelectedScope，再构造当前分片的两期 DIMENSION_BREAKDOWN 请求。每次 I/O 重新核对当前来源和明确成员范围。

## 推进与恢复

沿用现有 NativeGraph、PersistentPlanDriver、提交／接收／释放及容量等待机制。每个分片只保留两个待接收目标，原 child/requestId/wire 在首次调用前耐久冻结。新提交任务由下一次协调恢复读取，已完成任务不重提。

Publisher 的 `progress` 按稳定 LOCAL child 身份逐项读取已完成比较页，验证原生产步骤、定义、前驱 ID/hash、期限、位置及同分片源页数；不扫描全部 children 或缓存所有桶。恢复准备分别处理派生范围、当前比较页和最终 manifest 的 LOCAL_RESULT_UNKNOWN，使用同一冻结调用的注册批准对象；已知无效结果不自动重试。

空集合通过真实派生范围后直接发布 NOT_APPLICABLE 或 INSUFFICIENT_EVIDENCE，无维度查询。最终步骤成功前验证实际页链已完成和真实 LOCAL 具名输出；成功不替代后续 GoalAssessor。

## 最小验证与边界

`DimensionChangeSkillTest` 两项定向后端测试最终通过。首轮实际八任务链已完成，两条测试断言按既有合同纠正：释放前有额外一次原任务状态核验；完整扫描后的 INCOMPATIBLE 空集合仍保持 selectionComplete=true，结果说明为证据不足。修正后两项均通过，日志 `.work/dimension-skill-retest.log`。

随后仅在第二项增加真实 H2 CHECK 写入故障：维度页 LOCAL_RESULT_UNKNOWN 时父步骤 BLOCKED，解除故障后同 child/spec 的 attempt 从 1 到 2，原查询提交／读取／释放均保持四次，真实回调均退出。只重跑该方法，1 项通过，日志 `.work/dimension-skill-local-recovery-test.log`；未重复执行已通过的主链用例。本批共两项独立测试，不把重跑次数累加为覆盖数。

验证真实“下降筛选→维度变化”两步 NativeGraph，501 入选对象经过八次恢复完成八个统计任务；外部网关为脚本数据，范围枚举作为已发布的真实源 Artifact。空集合不提交维度查询；接收时撤权不发布维度产物。

生产入口仍关闭。本批不包含当前 Plan 内的范围枚举节点、原生 ReAct 耐久探索、报告／客户端和真实 MySQL／跨服务／模型／性能验收；不启动 Docker、应用或前端。联合维度仍限定 province/device，UV/UIP 保持明确分片 cohort 口径。
