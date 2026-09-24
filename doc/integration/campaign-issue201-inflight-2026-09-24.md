# Issue #201 · 真实在途撤权最终验收（2026-09-24）

**结论：#201 剩余的真实 Admin／Analytics 同一 job 在途撤权场景通过。** 一个真实统计 job 进入 RUNNING 后，测试账号经正式 API 自禁用；放行原 worker 授权请求与原 Admin 提交回执后，真实 Command 返回 403，Analytics 将原 job 置为 `FAILED/FORBIDDEN`。Agent 保留原 request／job，退出旧 callback，没有生成 artifact、结果页、release 或报告，后续推进被当前身份门禁拒绝。

本文接续[部署后补记](campaign-issue201-postdeploy-2026-09-24.md)；结合[先前补充验收](campaign-issue201-followup-2026-09-24.md)，完成 #201 所列范围。代码、定向后端验证与本次真实场景分别记录，不宣称所有模型输出、所有视口、active-active 或压力容量均通过。结构化脱敏证据及原文件 SHA-256 见 [evidence JSON](evidence/issue201-inflight-2026-09-24.json)。

## 实际拓扑与版本

- 一个独立完整 Spring Agent、一个独立真实 Admin 和一个独立真实 Analytics API，均由生产 jar 装配；Analytics 执行生产 `QueryJobRuntime`。它们连接既有真实 MySQL、Command、Redis 和统计基础设施。Agent 与 Analytics 使用独立账本 schema，单 writer；没有复制共享 job 或操作既有用户数据。
- 测试账号 `issue201_pair_0d959e1f1670`，tenant `10`；自有空组 `0092df1835f94926ae4657b959b2f900`；期间 2026-09-22。私有控制库只复制真实 ACTIVE epoch 及该日 **288 个 finalized manifest**，源库事务为只读一致快照，初始 job／page 都为 0。
- 调用生产 FIXED ranking 的 typed plan／intake 登记与提交，不调用真实模型。该实验检查授权与回调竞态，不重新验收自然语言解释、ReAct 或报告生成；这些证据分别保留在前序报告。
- 两个透明 HTTP 屏障分别暂存真实 Admin 的原提交回执，以及原 job 进入 RUNNING 后、尚未转发给 Command 的首次 worker 授权。只有同一 attempt／Run／request／job 均匹配才撤权；共同截止时间为原 POST 开始后 4000 ms，没有调高生产 HTTP 预算。撤权成功后放行原字节，未伪造统计或授权响应。
- 私有 Admin 在普通 singleton 初始化前移除 `AccountRecoveryWorker`，避免其后台恢复工作影响共享业务；其他业务授权路径保留。三个新增容器各 1 GiB、独立 JVM；没有提高共享服务并发或资源上限。

| 实际制品 | SHA-256／来源 |
| --- | --- |
| Agent jar | `3afd64e88619777ffc84848dfc33a30d6e470efb4246de091a09e175233d3c73`，包含已合并 PR #203 |
| Admin jar | `f6f27415af48238cebf2a41977aa1ffe96ded71eb302488564c89bfedd99138f` |
| Analytics jar | `cdd2ee0a6b934a118dcfad80ec4dacaf7c81debde935312e1f71dcc23d632972`，来自已合并 PR #207 |
| 屏障／启动 helper jar | `8b23672e8ab135b4988b04e4967c54396f20078124ea5fd6f3e084e53824703d`，仅在本次隔离容器加载 |

PR [#207](https://github.com/Jupiter363/shortlink/pull/207) 将 QueryJobService 的数据库时钟从 `CURRENT_TIMESTAMP` 改为 `CURRENT_TIMESTAMP(3)`，避免把毫秒字段写成整秒；对应 **33 个定向后端用例通过，BUILD SUCCESS**。合并提交为 `86597cf6a1523f6ee99a0c648dcead7b9e99df87`。新 jar 仅在本次隔离 Analytics 实测，**共享 Analytics 实例未部署该 jar，历史时间戳未重写**。

## 同一身份与实际时序

- attempt：`0d959e1f1670eb1162246755de3f49f9`
- Run：`campaign-run-339f05972abb6b46912d136a537a7f5891e40cda78039e70531f5acd4f59c216`
- request：`stat_cbdef06c7da6bc02a4e43979069a8e9d3fbbf4ca9a57d5c8f560e546389000c8`
- job：`f72ca015-1302-40fd-b9d6-6d2e2d1cd0ab`

| 事件 | UTC epoch milliseconds | 实际证据 |
| --- | ---: | --- |
| 原 Agent POST 开始 | 1790234165092 | 持久 child 身份与提交 marker；共同 deadline 为 1790234169092 |
| 真实 Analytics job 创建 | 1790234166215 | 数据库 `created_at` |
| 原 Admin ACK 被屏障持有 | 1790234166480 | 回执含同一 job，原回执 SHA-256 已保存 |
| worker 确认同 job 为 RUNNING，首次授权尚未转发 | 1790234166842 | `worker-authorize-held.json` |
| 自禁用 API 请求开始 | 1790234166883 | 正式账号 API；不是直接写用户表 |
| 自禁用 API 返回 HTTP 200／code 0 | 1790234167225 | `disableAcknowledged=true`，早于两个屏障放行及共同 deadline |
| 原 worker 授权放行 | 1790234167243 | `MATCHED`，仍为原 request／job |
| 原 Admin ACK 放行 | 1790234167269 | `MATCHED`；原响应 SHA-256 `6f63f130c0604d6ec9ff6818f08240c7b9cbb14ec67673e8e0b34df869cb714b` |
| 真实 Command 授权返回 403 | 1790234167310 | 授权请求 SHA-256 `fcc37b7f9555045849e96faafe8d4d74c068ac03409babae267e1fd71dcd6828` 与持有时一致 |
| 原 Analytics job 更新为 FAILED/FORBIDDEN | 1790234167326 | 数据库 `updated_at`，attempts 1／pageCount 0 |

先后关系由“确认 RUNNING → 正式禁用成功 → 身份匹配后才放行”的因果屏障保证，数据库毫秒时间提供交叉核验；不是靠两个自然轮询快照推测竞态。该窗口在首次 worker 授权之前，未执行 ClickHouse 数据读取；不扩展为查询完成后最终提交窗口的验收。

## 撤权后的账本与后续推进

最终只读数据库快照取得于 `1790234169331–1790234169742`：

| 对象 | 真实结果 |
| --- | --- |
| 账号 | `disabled=true`，`authVersion=2` |
| Run | **ACTIVE、revision 1**，没有冒充 CANCELLED 或分析成功 |
| 原 child | **UNRESOLVED / JOB_RESULT_UNKNOWN**，原 job／request／wire hash 保留，`callbackActive=0`、`artifactId=null` |
| 原 Analytics job | **FAILED / FORBIDDEN / UNAVAILABLE**，`attempts=1`、`pageCount=0` |
| 登记与派发 | 私库 Run／child／job 各 1，intake 1；统计提交计数 1，status／page／release 读取均 0 |
| 发布 | artifact、statistics receipt／page／release、report lifecycle／reference／synthesis、model response、Analytics page 均 **0** |

旧 ACK 只用于保留原任务身份，没有被当作可交付结果。原 Future 正常退出后，helper 再次提交同一 WorkRef 得到异常，没有第二次统计提交；随后生产 due dispatcher 于 **07:16:07.866 UTC** 明确记录 `ACCESS_DENIED → SecurityException → AgentAuthorityClient.verifyCurrentPrincipal`。这是授权门禁阻断，不是把原 Run 标为已完成。

保留两处观察器局限，避免将失败日志改写为通过：

1. `post-revoke-advance.json` 原值为 `securityDenied=false`、根异常 `HttpStatusFailure`。helper 用 `root(Throwable)` 一直追到最深 cause，跳过外层 `SecurityException`，导致分类字段误判。生产源码在 current-principal 传输非 200 时包装安全异常；实际 dispatcher 日志和零新增账本也支持拒绝结论。**该次 observer 的精确 HTTP 状态没有记录，不能声称它是 403。** 之后仅补一次相同旧身份的只读 current-principal 请求，实际为 **401 / Account is unavailable**，响应原文哈希与该固定错误体匹配；它不追溯修改原 observer 记录。Command 的 worker 授权 403 与这里的 Admin 身份 401 是不同接口。
2. `proxy-failed.json` 有 `HttpTimeoutException`，原文件时间为 `1790234150103`，比本次统计 POST 早约 15 秒；路由未记录，无法进一步归属。它不代替本次原 ACK、worker、禁用时序和最终账本证据，也不被隐去。

## 风险条款 → 定向验证 → 实际结果

| 风险 | 验证与关键断言 | 实际结果／边界 |
| --- | --- | --- |
| 撤权发生在 job 结束之后，误记在途通过 | 同一身份双屏障；RUNNING marker 早于禁用，禁用成功早于放行及终态 | 本次真实服务场景通过；两次此前自然窗口尝试仍不计通过 |
| 撤权后旧 ACK 发布或重新提交 | 原 child 保留 job；callback 退出；零 artifact／page／release／report；统计提交仅 1 | 通过；不是强行取消远端查询，也没有伪造 Run CANCELLED |
| 后续 due 工作继续越权推进 | 实际生产 dispatcher ACCESS_DENIED；旧身份只读补证 401；原身份无新 job | 通过；observer 原始 HTTP 状态保持未知 |
| 秒级数据库时钟无法区分顺序 | PR #207 的 33 个定向后端用例；真实 MySQL 固定毫秒精度及本次 job 写入 | 通过；共享旧实例和历史行未据此声称已升级 |
| 验收夹具影响共享账户恢复或现有账本 | 私有 schema、源控制库只读快照、Admin 后台 worker 移除、精确 owned container 清理 | 所选场景通过；业务源库仅新增本测试账号和自有空组 |

首次隔离启动尝试 `90f4ea2c489be9313f64912e90bdc9bf` 因 helper 按文件名排序迁移而停在 Agent 初始化，未提交 Run 或统计 job；复用现有 Spring 测试的数值排序后，相关离线检查 3/3 通过。首个账号及组按清理路径处理，三个容器已移除。该启动失败不算业务验收通过，也没有反复重跑已通过的业务场景。

本次沿用 PR #204 fixture 对历史 `V20260920_21` 唯一 `CLOB NOT NULL` 的精确 `LONGTEXT NOT NULL` 适配；它不等于生产迁移修复，仍由 [#205](https://github.com/Jupiter363/shortlink/issues/205) 跟踪。

## #201 完成矩阵与清理

| #201 条款 | 当前结论 | 证据范围 |
| --- | --- | --- |
| 浏览器指定版本下载 | 已验 | 同一空结果 report revision 的浏览器／服务端文件均 1332 UTF-8 bytes，文件名及 SHA-256 一致；见先前补充验收 |
| 当前权限读取与真实在途撤权 | 已验 | 已完成报告撤组后 report／rows／export 403、history 空；本次补齐真实 RUNNING job 撤权及旧 callback 隔离 |
| 故障接管 | 已验，保持单写入边界 | 完整 Spring + 原生 MySQL 顺序 A→B 接管，同原 job 不重提；远端为 HTTP fixture；见部署后补记 |
| UNKNOWN 地域 | 已验 | 实际 UNKNOWN／PV 11 数据及 1600px、390px 组件呈现；未伪装成零或完整叙述 |
| 叙述安全原条款 | 已验所列断言 | 无证据检验／因果／采集完整断言的拦截与定向验证；真实模型漏引用另跟踪 #206 |
| 容量前置条件 | 保持 | 未提高并发、未启用双 writer；所记内存／OOM 观测不是负载容量结论 |

三个本次隔离容器已按精确 container ID 停止并移除，均 `OOMKilled=false`；原 Agent／Admin／Analytics／Command／MySQL 保持 healthy、restartCount 0，未被本次重启。测试账号保持禁用；本次保留两个自有空组（显式组及异步默认组，均 linkCount／jobRefs 0）、两个私有 schema 和审计证据，**不声称测试数据已全部删除**。

代码交付、所选后端验证和 #201 约定现场补验已完成。全新 MySQL 历史迁移 [#205](https://github.com/Jupiter363/shortlink/issues/205)、真实模型遗漏 `evidenceArtifactIds` [#206](https://github.com/Jupiter363/shortlink/issues/206) 保持开放；跨容器 active-active、容量压测、任意提示词质量及所有视觉断点不属于本次已证明范围。
