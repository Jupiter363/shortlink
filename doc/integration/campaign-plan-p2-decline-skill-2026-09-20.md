# P2：原生 Plan 中的真实下降筛选 Skill

日期：2026-09-20。关联 Issue #62，承接 E18 LOCAL 调用和 E19 分页产物。此批注册确定性 `SKILL / decline_selection / 1` 固定执行器，沿用 `PersistentPlanDriver` 与 Spring AI Alibaba `NativePlanGraph`。

## 冻结输入与方法版本

四个端口分别为冻结的 `ScopeRef`、真实 `ScopeArtifact`、冻结的 `PeriodsRef`、闭合的 `DeclineSelectionDefinition`。范围产物通过 ARTIFACT 绑定及当前主体授权读取；定义仅包含原 gid、两期日期／时区／引用和批准的 SkillPin，不放入全部成员或每片请求。普通参数指定 PV／UV／UIP，排序固定为绝对差升序后按对象 ID 排序。

方法包 `decline-selection/1/SKILL.md` 使用现有原生 SkillScanner／PinnedSkillRegistry 固定内容摘要，业务执行器明确映射为 `decline_selection`。固定 Skill 复核原批准内容，不调用模型、不执行脚本，也不借方法文档激活工具。

## 实际执行链

每次恢复先验证已提交的选择产物前缀，只准备当前分片的两期 `LINK_METRICS` 请求。实际 `context.child` 归属于父 Skill，原 wire、requestId、childId 先耐久保存；共享统计提交边界核对原 wire、当前授权、严格 ACK 和可信容量拒绝。已受理任务返回 WAITING，未知提交保留原身份，不当作缺页或零值。

原 `CampaignRecoveryCoordinator` 对账、接收完整结果并释放远端页，原节点重新扫描后调用 Publisher 发布本片，再推进下一片。LOCAL READY 复用原件；若本地计算结果未知，恢复准备只重建同一冻结调用的注册批准对象，原 StepStore 检查输入、当前授权、期限与回调退出后才允许重算。已知无效结果不自动重试。

最终两具名输出须匹配真实已封存集合，才通过 Step 输出验收。当前结果仍为观测变化；未知采集质量、对象存续和共同数据基础限制继续保留。Step 成功不替代后续逐目标 Assessor。

## 最小验证

3 项定向测试首次全部通过，失败、错误、跳过均为 0。日志 `.work/decline-skill-tests.log`。

| 选择器 | 关键断言 |
| --- | --- |
| `DeclineSelectionSkillTest`（2 项） | 独立已发布 Scope501 输入与真实单 Skill NativeGraph，四个统计任务提交4／远端恢复0／页读取4／释放4；WAITING 后多次重建运行器和 Coordinator 恢复，三个 LOCAL 子任务均 READY 且 attempt=1，500下降全局有序、501证据齐全。方法摘要或范围不符时零 I/O；取消／页读取时撤权不发布。真实数据库约束拒绝 LOCAL 页后，恢复使用同 child/调用定义、attempt=2，已完成统计不重提。 |
| `StatisticsJobFixedExecutorTest#firstSubmissionIsFrozenBeforeIoAndPublishesOnlyAfterAllPagesThenReusesReadyOutput`（1 项） | 共享提交逻辑抽取并加原 wire 绑定后，既有固定 Tool 的准备／接收／READY 复用路径仍通过。 |

只替换外部网关响应与进程存活证明；PlanGraph、Driver、JDBC Store、接收／释放、LOCAL 发布和分页索引均为真实组件。范围在独立源 Run 预先收集，此断言不证明枚举节点已经进入当前 Plan。

## 明确边界

成员枚举在本批作为预先完成的真实 ScopeArtifact 输入，尚未把 ScopeCollector 的有界 PROGRESS 编入同一个 Native 节点；不会把无远端任务的本地继续推进伪装成 WAITING。真实生产请求装配、客户端协议、`dimension_change`、原生探索的耐久恢复和后续阶段继续按总计划实现。

不启动 Docker、应用、真实模型、MySQL 或前端；H2／脚本网关和原生 Graph 的后端证据不能替代真实跨服务部署或大规模性能验收。分页前缀读取仍完整核验已有链，性能优化另行处理。
