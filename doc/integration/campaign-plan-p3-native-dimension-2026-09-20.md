# P3 原生维度 Skill 与派生范围验收 · 2026-09-20

关联 Issue #62，接续 E41。接入同一 REACT Step 内的下降筛选→维度变化链路，消费上一轮真实具名产物，继续复用 Spring AI Alibaba ReactAgent。

## 实现边界

新增 `dimension_change/3` 能力、封闭定义及独立固定方法包，保留旧 v1/v2 的 FIXED 绑定和身份。模型只能选择本步骤已开放的 INPUT 与来源 MODEL 实际可见的 ARTIFACT。适配器校验真实 sealed selectedEntities／selectionEvidence 对、原范围、实际日期、当前权限及方法摘要，不要求生产者的外层 Step 已成功，不伪造 STEP_OUTPUT。

真实 CALL 拥有派生 SelectedScope LOCAL、两期 DIMENSION_BREAKDOWN 请求、分页比较 LOCAL 与最终 dimensionChanges。身份包含 callId，参数变化不能另造逻辑 child 绕过冲突；attempt 不改变原身份。FIXED 与 CALL 共用原有计算循环，保持省份×设备联合桶、SHARD_COHORT、OBSERVED_ONLY 与未知质量，不能把变化当成已证实原因，也不跨分片汇总 UV／UIP。WAITING／容量续接沿用 E41 的真实依赖协议。

维度结果属于真实选中子集，scopeRef 与原分组不同。候选验收新增显式服务端边界检查器：普通产物仍要求原 scope／period，维度结果通过真实 SelectedScope 及 sealed 源证据验证来源关系。边界版本纳入候选配置指纹；旧构造器保持原 exact 行为和指纹。模型无法声明范围继承关系。

结果读取器检查实际注册 LOCAL 发布、原输入与当前权限、具名输出和分页链；逐页读取，仅保留有限预览，不复制整份结果。原生观察包含真实行、各 cohort 的统计与质量、覆盖数量、明确预览遗漏数和完整 Artifact 引用。覆盖检查只判定原选中集合及观察分页是否完整；采集完整性与因果要求仍需独立条件。

## 验证与剩余范围

4 个定向后端方法首次通过，0 失败／错误／跳过；日志 `.work/native-dimension-tests.log`。使用真实 SAA Graph／ReactAgent、H2、脚本模型和统计响应。

| 用例 | 实际断言 |
| --- | --- |
| `NativeDimensionChangeSkillTest#oneNativeReactStepConsumesItsOwnCompletedSelectionThenResumesDimensionSkillAndPublishesTypedObservedEvidence` | 同一个真实 REACT Step：下降 CALL 接收原两期任务后，MODEL2 在 Step 仍 RUNNING 时取得真实 pair，再调用维度 Skill。SelectedScope 仅含下降成员 1，真实维度任务完成后，新 writer／空 saver 续接原 CALL；MODEL3 收到浙江/Mobile 与 UNKNOWN/Desktop 联合桶，PV 10→3、delta -5/-2、桶 UV 相加为 2 而独立 cohort UV 为 1。保持 UNKNOWN／UNVERIFIED／OBSERVED_ONLY，后端通过真实派生范围和覆盖检查，发布单一 dimensionChanges Step 输出。总 model=3、submit=4、pageRead=4、CALL=2；原 job/spec、派生 LOCAL attempt=1、Artifact 与 TTL 保持，重扫不重做。 |
| `NativeDimensionChangeSkillTest#genuineEmptySelectionNeedsNoDimensionQueryAndRevokedSourceCannotDispatchDimensionWork` | 真空选集输出 NOT_APPLICABLE／NO_DECLINES，维度请求为 0；另一实际 MODEL2 已读取来源后撤权的场景，维度派发和维度产物为 0，来源读被拒绝。没有以这个用例替代混配／不可见 pair 的独立反例。 |
| `DimensionChangeSkillTest#nativeSelectionThenDimensionBindsRealOutputsAcrossEightRecoveryPassesWithoutRepeatingJobs` | 受影响原 FIXED 下降→维度链、501 成员分片、八轮恢复与原请求复用通过，原身份和计算语义保持。 |
| `PersistentExplorationDriverTest#nativePlanDriverResumesTheOriginalSkillAndSettlesVerifiedOutputsBeforeAdvancingTheTypedConsumer` | 受影响旧候选 exact 边界及 Driver 具名输出／下游消费保持兼容。 |

生产入口仍关闭。未启动 Docker、应用、真实模型、浏览器或真实数据库；真实环境和客户端验收另列。新能力保持现有 province×device 合同，通用任意维度组合、生产工厂、公共 Goal／报告交付不在本批完成声明内。本批不新增独立的维度混合容量、release、伪造链或混配 pair 测试，不把既有保护误报为这些新增运行证据。
