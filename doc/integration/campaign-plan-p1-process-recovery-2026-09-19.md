# P1 第五批：进程死亡确认与恢复协调

跟踪 [Issue #62](https://github.com/Jupiter363/shortlink/issues/62)，承接 [PR #67](https://github.com/Jupiter363/shortlink/pull/67) 的步骤驱动与 [PR #68](https://github.com/Jupiter363/shortlink/pull/68) 的授权进度视图。新组件尚未注册为生产 Bean 或 HTTP 入口。

## 本批解决的问题

数据库中的 `callback_active` 不能因 Future 超时、心跳过期或重建 Store 就清空。即使 child 已是 READY、step 已是 SUCCEEDED，原回调也可能还没有退出；反过来，进程直接退出后没有机会执行 finally，会留下永久阻断。

新增不可变的 `campaign_run_owner`，把每次推进 token 与可信进程身份关联；新增 `campaign_callback_recovery`，逐次保存确证死亡后释放回调的依据。旧记录缺少进程归属时保持阻断，不推测其已退出。

`LocalProcessLiveness` 使用 JDK ProcessHandle 检查 PID 与操作系统启动时间。PID 不存在、原进程已退出或 PID 已由不同启动时间的进程复用，才形成死亡证明。权限不足、启动时间缺失或 PID 域不匹配均为 UNKNOWN。适配器重建产生不同 instanceId，也不会把同一 JVM 判死。依据见 [ProcessHandle](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ProcessHandle.html) 与 [ProcessHandle.Info](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ProcessHandle.Info.html)。

## 接管与恢复次序

1. 校验当前主体和权限；在短事务中锁定同一 Run 的全部 revision，读取所有活跃 step／child 的精确 attempt 与归属快照。
2. 在事务外检查本进程存活、全部旧回调所属进程确证死亡。不使用等待、轮询或 TTL 推断。
3. 重新加锁核对快照。期间状态、attempt 或 token 改变则拒绝接管，不清理新回调。
4. 同一事务内释放确证死亡的回调、写审计、领取新 token 并登记新 owner。任一写入失败全部回滚。取消的 Run 只清理已死亡回调，不重新启动。
5. 恢复协调器先构建并校验完整冻结计划，再对未知异步提交走原有 recover-existing；保留原 requestId、正文和已知 jobId。同步未知响应不重查，已有 jobId 不等于数据就绪。
6. 只由已经耐久落库的 READY 回执唤醒步骤，然后执行原生 PlanGraph 的一次扫描。独立步骤可继续，未知结果及其依赖保持阻断。Graph 扫描结束仍不代表用户目标已经交付。

接管保留 READY 的原 Artifact、WAITING 的原 jobId 以及 SUCCEEDED 的具名输出。未完成派发分别标记 `READ_RESULT_UNKNOWN`、`SUBMISSION_UNRESOLVED` 或 `JOB_RESULT_UNKNOWN`；中断步骤标记 `STEP_RESULT_UNKNOWN`，没有 child 的步骤不会因空集合被判定可恢复。

权限在实际恢复 I/O 前及响应后再次核验。撤权或取消后返回的 job 只能保存为迟到事实，不能发布成功或继续 Graph。I/O 前撤权也会正确退出本次回调，不留下无人执行的 DISPATCHING 状态。

## 最小验证

本批定向验证覆盖以下五个测试类，共 **18 例，最终全部通过，无失败、无跳过**：

| 测试类 | 数量 | 验证重点 |
| --- | --- | --- |
| LocalProcessLivenessTest | 3 | 当前 JVM、PID 复用、跨域／不可观测，以及轻量子 JVM 自然退出 |
| JdbcCampaignRecoveryStoreTest | 5 | 缺少／存活／未知 owner 零写入，跨 revision 精确快照，竞争单赢家，事务回滚，取消与身份隔离 |
| StatisticsSubmissionReconcilerTest | 6 | 既有 recover-existing 协议的定向回归，本批新增实时授权门控与 finally 收尾 |
| CampaignProcessRecoveryTest | 1 | 轻量子 JVM 提交 H2 文件后直接 halt，另一 JVM 确证退出、复用原 Artifact、跳过已完成步骤并推进依赖 |
| CampaignRecoveryCoordinatorTest | 3 | 混合同步／异步恢复，存活／未知／拒权零派发，以及 I/O 前后撤权与取消 |

真实进程用例只运行测试辅助 Java main，不启动应用、网络服务、Docker 或模型。其余用例复用真实 H2 账本、PersistentPlanDriver 与原生 MemorySaver；仅进程观测和远端响应按用例替换。

首轮 15 例中 14 例通过，真实进程例在所有业务断言通过后的 H2 SHUTDOWN 清理失败；修正为直接 JDBC 关闭后，仅重跑该例并加入协调器 3 例。后者发现测试租户不符合现有数字 tenant 校验，修正 fixture 后仅重跑这 3 例，全部通过。未重复此前已通过的其它用例，也未运行全模块测试。

## 仍未开放的边界

- `processDomain` 必须由部署端配置，准确标识同一主机和同一 OS PID 命名空间，不能由用户或模型填写，也不能仅拿 hostname 代替。不同容器／主机的旧身份保持 UNKNOWN；本批不提供分布式故障检测或租约。
- 生产入口仍需通过共享进程容量执行器准入，再加载 Run；本批没有新增调度循环。生产装配需统一使用登记 owner 的推进路径，不能混用不登记 owner 的低层 token 领取方法。
- 已知异步 job 的状态／分页接收、冻结范围证明、结果释放与业务执行器适配仍未完成。本批恢复的是执行权与已存在身份，不冒充统计取数全链路完成。
- 迁移仅在测试库执行；未连接现有业务库。H2 验证不代替正式数据库或跨主机部署验收。
- P1 保持实施中。旧客户端的状态兼容和后续图文报告尚未完成，新运行入口继续关闭。
