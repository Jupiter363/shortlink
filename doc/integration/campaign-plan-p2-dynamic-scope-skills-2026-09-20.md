# P2：同一 Plan 的动态范围 Skill 绑定

日期：2026-09-20，关联 Issue #62，承接 E23/E24。

## 版本化输入

新增 `decline_selection/2` 和 `dimension_change/2`，复用原 Skill 的执行、分页、LOCAL 发布和恢复逻辑。旧 v1 接口、方法包和冻结范围用法保留。结果结构未改变，继续使用原 Artifact 输出合同。

下降 v2 从直接依赖的 `scopeArtifact` 具名输出取得真实范围；维度 v2 从同一下降步骤的 `selectedEntities` 和 `selectionEvidence` 取得真实来源 pair。两个 v2 描述均移除预填 scopeRef，INPUT 类型及 schema 版本为 2，方法包固定原生 v2 pin。

静态模板只校验冻结计划。上游未完成时不提前读取 Artifact、不构造查询；上游成功后从步骤账本与真实 Artifact 绑定，核对主体、生产步骤、范围、gid、期间和当前授权，再生成实际取数请求。原始完整范围和筛选后的派生范围分开，范围 hash 来自实际成员，不伪造 Run/Plan/token 或已完成上游。

SelectionPair 的内部读取结果现在包含数据库验证后的 producerStepId，维度绑定核对当前 Run/Plan/revision 和冻结上游步骤；不能仅凭相同类型和主体替换成其他步骤的产物。此字段不改变对外 Artifact schema。

## 验证与边界

三项定向后端测试首次通过，日志 `.work/dynamic-scope-skill-tests.log`：新增 `DynamicScopeSkillPlanTest` 两项，及 `DimensionChangeSkillTest#nativeSelectionThenDimensionBindsRealOutputsAcrossEightRecoveryPassesWithoutRepeatingJobs` 一项。后者同时覆盖两个 v1 Skill 的实际执行，不重复运行单独下降 Skill 主链。

真实三步 NativeGraph/H2 从零 Artifact 开始，501 成员仅读取两次权威页，随后四个 LINK_METRICS＋四个 DIMENSION_BREAKDOWN 任务均只提交一次、接收一次、释放一次；原期限和枚举版本保持，派生范围与原范围分开，最终发布真实四页链及 manifest，复扫零 I/O。小数据分支验证错期间、已完成上游缺少声明输出、枚举后撤权，均在后续取数前拒绝；外部网关为脚本替身。

联合维度仍为 province/device，UV/UIP 保持分片 cohort 的独立统计。生产入口、Goal 终评、报告和客户端仍待后续实施；不启动 Docker、应用、真实模型或浏览器，不以脚本网关/H2 代替真实部署验收。
