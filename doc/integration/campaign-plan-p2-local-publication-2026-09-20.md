# P2：本地计算与具名输出原子发布

日期：2026-09-20。关联 Issue #62，承接父集合覆盖与逐对象比较组件。本批给确定性本地计算建立独立调用身份，为 `decline_selection` 的集合、证据双输出提供发布边界；不把本地计算伪装成 HTTP 调用。

## 实现边界

- `LocalCalculationRegistry` 由后端注册计算合同、实现指纹、输入输出类型与校验函数。调用冻结实际输入 Artifact 元数据、参数、具名输出绑定及期限。只有注册合同能生成批准对象；模型提供的名称或标志不构成重算许可。
- Child 增加 `LOCAL` 模式，持久化规范化调用与摘要，HTTP 字段为空。远端 SYNC／ASYNC 仍使用原请求合同。
- `CampaignStepExecution.local` 先提交调用准备，再于事务外计算。每次读取声明输入和发布前检查当前执行资格。发布依次锁 Run、Step、Child，核对三个层级的真实 attempt、当前授权和输入载荷摘要；所有输出元数据、载荷、具名关联和 Child READY 在同一事务提交。
- 结果未知使用 `LOCAL_RESULT_UNKNOWN`；格式或计算合同明确拒绝使用 `LOCAL_RESULT_INVALID`。未知结果只有原回调已退出、原调用仍获注册合同批准、实际输入未变化且未到期时才能重算。已知错误不会自动重试；进程死亡接管保留这一分类。异步统计恢复器不会把 LOCAL 送往远端恢复接口。
- READY 输出读取校验实际载荷及关联。进程重开后可复用整组输出，不再次执行计算，也不延长期限。Step 的具名输出验收接受真实 LOCAL 发布关联，保留远端单结果证明路径。

## 最小验证

4 项定向测试首次全部通过，失败、错误、跳过均为 0。日志：`.work/local-publication-tests.log`。

| 选择器 | 验证内容 |
| --- | --- |
| `LocalCalculationExecutionTest`（2 项） | 通过输出校验后，第二份载荷 INSERT 的真实数据库约束故障使两份输出、关联与 READY 一起回滚；重开按原输入重算后，父步骤未结算再次重开也不重算，hash／TTL 保持不变。覆盖活跃回调、旧 Step attempt、取消、撤权、到期、非法合同／输出、真实 JDBC 死亡接管分类，以及 LOCAL 未知结果不会调用远端网关。 |
| `PersistentPlanDriverTest#waitingJobSurvivesReopenAndNamedOutputReusesEvidenceWithoutSubmittingOrRepeatingCompletedSteps`（1 项） | 受影响的旧远端等待／重开／具名输出复用链，未加载 LOCAL 迁移的原夹具仍通过。 |
| `JdbcCampaignRecoveryStoreTest#provedDeadCallbacksAreClassifiedExactlyWhileReadyWaitingAndUnresolvedEvidenceIsPreserved`（1 项） | 原同步、异步、已完成及等待回调的死亡恢复分类保持正确。 |

新增用例直接验证执行上下文与真实持久账本；进程存活证明使用替身，不宣称完整业务 Skill 或生产 Driver 装配已经完成。

只使用后端 JVM／H2 夹具，不启动 Docker、应用、真实模型、MySQL 或前端。H2 不证明 MySQL 的锁与迁移行为。

## 剩余交付

本批是明确注册的本地计算边界，不提供通用代码沙箱，也不增加独立执行循环。可信业务适配器仍须保证计算确定性并使用冻结输入。真实下降筛选 Skill、分页入选成员／证据、覆盖与计算进度耐久化、后续动态集合维度分析仍按总计划继续。生产装配和客户端新结果入口保持关闭。
