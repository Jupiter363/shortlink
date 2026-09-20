# P3 原生探索接入外层计划执行 · 2026-09-20

关联 Issue #62，接续 E38。本批将局部探索接入既有 `PersistentPlanDriver`，通过真实候选验收后发布具名 Step 输出，让同一计划的依赖步骤继续消费原产物。

## 执行与恢复边界

注册 FIXED 执行器与 REACT 策略是两个独立维度。新增显式装配入口只接受服务端注册策略；旧构造器继续拒绝未装配的 REACT。外层仍是原生 Graph 扫描，局部仍使用 Spring AI Alibaba ReactAgent，没有新增模型循环或为每个工具动作增建外层 Node。

外层步骤得到运行许可后创建对应原生会话。原 Skill 在等待时保留真实 CALL 与统计任务；数据接收完成后，新 writer／Step attempt 先按原 invocation 继续计算，并由真实 finally 释放 CALL，再让模型读取完整具名观察。候选模式向模型提供后端统一 schema 指令，冻结并版本化该消息，旧模式不改变。

成功分支读取真实耐久验收收据，验证注册输出合同，再于同数据源事务中重新核对 COMPLETE 与当前证据，使用原具名 Artifact 发布 Step 成功。产物 producer 不改写，不依赖模型返回的产物清单。缺输入、重规划、无进展与验收拒绝保留明确 BLOCKED 原因，不触发依赖步骤或自行变更计划。

同步补齐完成门：READY 是结果事实，不等于真实回调已经退出。Step 发布成功前要确认除自身 Step 外没有仍活动的 Step、child 或 CALL；拒绝时不写成功状态或输出。WAITING／错误状态仍可记录，实际回调占用只能由真实 finally 或既有死亡证明协议清理。

## 最小验证

4 个定向后端方法首次通过，0 失败／错误／跳过；日志 `.work/react-plan-driver-tests.log`。使用真实 SAA Graph／ReactAgent、H2、脚本模型及网关，不扩成全模块回归。

| 用例 | 实际断言 |
| --- | --- |
| `PersistentExplorationDriverTest#nativePlanDriverResumesTheOriginalSkillAndSettlesVerifiedOutputsBeforeAdvancingTheTypedConsumer` | 从未开始的真实两步计划出发，Graph 首扫进入 WAITING，接收原两期任务后新 writer／空内外 saver 续接原 CALL。Skill attempt=2，真实 LOCAL 双输出→后端 COMPLETE→Step SUCCEEDED→依赖节点通过 STEP_OUTPUT 读取真实下降 -7。总 model=2、submit=2、pageRead=2、continuation=1、consumer=1；原 job/spec/Artifact 不变，重扫零重调；旧 writer、撤权拒绝。模型实际收到 System schema。 |
| `PersistentExplorationDriverTest#needsInputAndRejectedCandidatesRemainBlockedWithoutPublishingOutputsOrRunningDependents` | NEEDS_INPUT 和非法候选两场景分别保留明确阻断原因；Step 无输出，下游不运行，业务调用零，重开仍为原收据且模型各一次、revision 不变。 |
| `JdbcCampaignStepStoreTest#readyChildMustActuallyExitBeforeStepSuccessCanPublishOutputs` | 实际发布 READY 但 child callback 未退出时拒绝 Step 成功，状态和输出不变；实际 finally 退出后才发布，随后自身 Step finally 前仍阻后继。原两迁移 fixture 保持兼容。 |
| `PersistentPlanDriverTest#waitingJobSurvivesReopenAndNamedOutputReusesEvidenceWithoutSubmittingOrRepeatingCompletedSteps` | 受影响旧 FIXED Driver 的等待、新 writer、具名产物复用和独立已完成步骤不重做保持兼容。 |

## 未覆盖边界

本批为显式装配的后端组件，生产 Spring 入口、完整请求工厂、混合 UNKNOWN／容量等待、多阶段 Skill 及多 Run 组合峰值仍继续实现。新 System 合同纳入候选模式配置指纹，已有不同指纹会话明确拒绝，不静默混合消息；旧无候选模式不变。Step SUCCEEDED 只证明本步骤合同满足，不代表用户目标已回答或报告已交付。真实数据库／模型／跨服务链路及客户端验收仍保留待验，未启动 Docker、应用或浏览器。活动 CALL 分支复用已有门，本批新反例专测 child 实际退出，不声称新增了独立 CALL 并发测试。
