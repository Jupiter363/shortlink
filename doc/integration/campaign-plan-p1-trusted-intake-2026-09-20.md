# P1 请求幂等与可信装配 · 2026-09-20

关联 Issue #62，接续 E43。新增内部 typed 计划入口，将请求身份、持久冻结、进程准入和现有恢复协调器连接起来。

## 实现边界

请求身份由可信 tenant／subject／session／幂等键确定；requestId、runId 和 planId 使用确定性 SHA-256 身份。模型不能覆盖它们。登记固定原 authVersion、服务器 profile/version、完整定义摘要和请求摘要。同键更换正文、身份版本或 profile 会冲突，不通过另建 Run 绕过。新分析需新请求键。

登记只保存有界服务器 typed proposal，状态为 PENDING，尚未创建 Run 或加载 Artifact。用户身份和 session 归属由可信 resolver 验证；队列仅有 WorkRef。获进程许可后先读取短 header，再查询当前身份，之后才读完整定义、验证目录／计划和当前输入／Artifact 权限。

通过校验后，在同一 DataSource 的 REQUIRED 事务中锁定请求、创建初始 Run 并提交 FROZEN。并发重入读取同一冻结事实；FROZEN 重放不再次 createRun，取消或升级后的 Run 不会因此复活。请求最初 revision/hash 与最新运行不符时明确停止，后续跨 revision 采用另由 P4 处理。请求状态不代表分析完成。

首次及后续推进均复用 `CampaignRecoveryCoordinator.resume`，由它负责 writer 获取、进程死亡证明、原任务对账／接收和原生 Graph 扫描。入口不提前重复 acquireRun。每次可信 profile 工厂获得本次 scope 与当前权限门，返回的 Driver／Graph 必须使用同一 token、scope 和权限对象；旧 scope 不能被缓存复用。

登记与入队拒绝环境中的未提交事务，避免 worker 看到尚未提交的请求。容量拒绝保留可重试的持久请求；Future 完成只表示推进结束，不能当作 Goal 已回答。定义大小和进程容量均由配置传入。

本批不实现自由文本规划，不将旧 Planner 的 Invocation 列表伪装成 PlanSpec，也不注册 HTTP／Spring 生产路由。proposal 在冻结后保留一份有界副本以严格重放，暂未做存储去重。

## 验证与剩余范围

2 个定向后端方法首次通过，0 失败／错误／跳过；日志 `.work/trusted-intake-tests.log`，使用真实 H2、恢复协调器、进程准入和原生 Graph，远端能力为脚本。

| 方法 | 实际证据 |
| --- | --- |
| `CampaignRunIntakeTest#concurrentRegistrationAndFreezeKeepOneRunWhoseOriginalJobAndOutputsSurviveReplayButNotCancellationOrRevision` | 两线程同键登记和两线程冻结只生成一个 receipt／Run。实际 Intake→Coordinator 登记 writer→scoped Driver 提交原 ASYNC 任务并 WAIT；原 job 在真实账本接收后续扫发布同产物，再扫零重提。不同正文／profile／authVersion 拒绝；取消或升级后原请求不重建、不装配旧运行或误推进新 revision。 |
| `CampaignRunIntakeTest#queuedRevocationStopsBeforeProposalParsingOrRuntimeAndReferencesCannotBypassIdentityOrTransactionAdmission` | 一个真实获准工厂占位，另一请求排队后撤权；有界非法 JSON 哨兵仍先得到 SecurityException，证明拒绝先于解析，PENDING 原样保留且无 Run／runtime／tool。跨主体、错误 WorkRef 与环境事务中的登记／入队均拒绝，无额外队列推进。 |

本批没有独立注入“Run INSERT 后 receipt UPDATE 失败”的故障，也未使用完整 StatisticsJobResultReceiver、真实模型或完整 Skill 链，不把结构性同事务保障误报为这些故障／业务集成的运行证据。未启动 Docker、应用、浏览器或真实数据库。

真实 MySQL 方言／锁、生产请求与身份 resolver、自由文本混合规划、跨 revision 采用和客户端状态消费仍待完成；新运行器继续不对用户开放。
