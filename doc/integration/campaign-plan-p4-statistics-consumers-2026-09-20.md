# P4 原任务跨版本消费关系 · 2026-09-20

关联 Issue #62，接续 E45。本批把同一 Run 的跨 revision 采用连接到实际原统计 child、分页接收和产物归属；运行中自动重规划入口仍关闭。

## 原任务与消费者

共享 binding 由受控 RECONCILE 回调读取原任务状态后登记，固定真实 jobId、原请求摘要、执行器版本、输出合同、原结果 Target 和服务端有效期。Analytics 现有状态接口在 QUEUED／RUNNING 时也返回 expiresAt；Agent 协议保留并验证该值。旧响应没有有效期时仍可显示等待，但不允许据此建立可采用证明，不能本地推算期限。

新 revision 显式登记 consumer，逐项匹配可信 expectation 和当前权限。同一 DataSource 的 REQUIRED 事务可以组合原 RunStore.revise 与 adopt，校验失败时整个版本切换回滚。本批限定同一 Run 内跨 revision；没有复制 producer child、重新提交原统计查询或修改远端 worker lease。

采用后的 RECONCILE permit 同时记录原 producer 的历史 token 和当前 consumer 的推进 token。实际状态／页读取前及发布前都验证当前 consumer 资格；取消或换 writer 后不能发布成功。分页 receipt、已收页与最终 Artifact 仍写到原 producer 身份，后续只续收原任务缺少的页。实际 callback finally 可以在 consumer 已取消后清除自己的原 attempt，不能把 Future 取消当作回调退出。

取消意图与采用锁定同一物理 job binding；有有效 consumer 时不能申请远端取消，退休旧 consumer 不影响新 consumer。REQUESTED／CONFIRMED 不重开 NONE，未知或丢失回执不自动恢复采用。本批记录取消意图及已验证状态事实，不增加远端取消适配器。

原释放路径接入同一 binding 门。远端页释放后只能依赖已耐久接收并可授权读取的原本地结果；不能在采用的同时释放尚有消费者需要的远端页。旧 schema 的固定执行链保持兼容，新跨版本能力要求新迁移。

## 最小验证

四个定向后端方法首次通过，0 失败／错误／跳过；Maven 49.037 秒，日志 `.work/statistics-consumer-tests.log`。仅运行两个新消费者方法和各一个受影响的旧 Receiver／Releaser 方法，覆盖新行为及旧 schema 兼容，未运行全量回归。

| 方法 | 验证内容 |
| --- | --- |
| `CampaignStatisticsConsumerTest#atomicRevisionAdoptionResumesOriginalPagesWithoutResubmissionAndRevokedConsumerCannotPublish` | 原 query 只提交一次，真实 Receiver 已接第一页后，错误范围的 revise＋adopt 事务整体回滚；合法新 revision 续收原第二页，结果仍归原 producer。撤权后不发布，真实 finally 后授权恢复再读原 job，旧 writer 无权推进。 |
| `CampaignStatisticsConsumerTest#adoptionAndCancellationShareOneInterlockAndReleasePinsTheVerifiedLocalCopyWithoutReopeningRemoteUse` | 原 RUNNING 状态返回真实有效期；采用先行、取消先行和双线程竞争共享同一 binding；旧 consumer 退休不取消活 consumer，丢 ACK 的 REQUESTED 不重开。原结果全页就绪后验证共享 release 实际 permit、本地原产物及远端使用门。 |
| `StatisticsJobResultReceiverTest#restartContinuesAtTheDurableNextPageAndReadyEvidenceNeverRereadsTheJob` | 原 schema／构造器的分页恢复与 READY 不重读保持兼容。 |
| `StatisticsJobResultReleaserTest#confirmedReleaseKeepsReadyEvidenceAndOriginalBindingsAndNeverCallsRemoteAgain` | 原 schema／构造器的已确认释放与原身份保持兼容。 |

风险映射：R06／本地 adopt 与 cancel 共 binding 协议，由新第二方法验证两个顺序及双线程竞争；R03／原任务与原产物身份，由新第一方法验证续收、旧 writer 拒绝和零重提交；R18／耐久接收后释放，由第二方法共享释放许可和旧释放方法验证本批保护。仍不关闭真实远端取消、跨版本主动释放或全部生产竞争风险。

新用例通过真实 RunStore 派发许可和专用脚本网关提交、真实 Receiver／Protocol／ResultStore 接收及发布；没有运行完整 FixedExecutor／Graph。主例原远端已 SUCCEEDED、第一页已落盘，跨 revision 续收第二页；另有 RUNNING 任务采用后进入 SUCCEEDED 的撤权／恢复用例。当前状态接口 TTL 的实际代码兼容已静态确认，未发真实 HTTP。代码还允许实际已退出的 RECONCILE 恢复 DISPATCHING 状态，此额外窗口未单独故障注入，不与已测 JOB_RESULT_UNKNOWN 恢复混同。

## 剩余范围

本批提供内部版本消费协议，尚未把模型 REQUEST_REPLAN 自动连接到新 revision，也不证明自然语言规划正确。跨 Run 复用、远端取消与丢回执对账适配、由新 consumer 主动释放已退休 producer 的远端结果、释放后的新消费者本地复用、生产调度／路由、逐目标交付和历史清理继续推进。当前 adopted permit 只授予原任务 RECONCILE；本批 release 保护不冒充跨版本主动释放能力。真实 MySQL 方言／锁未由 H2 替代验收；不启动 Docker、应用、真实模型／数据库或前端测试。
