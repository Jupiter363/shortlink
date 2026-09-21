# P4/P5：重规划编排、原子发布与报告生命周期持久化（E50/E51/E52/E53/E54/E55/E56/E57/E58/E59/E60/E61/E62/E63/E64/E65/E66/E67/E68/E69/E70/E71/E72/E73/E74/E75/E76/E77/E78/E79/E80/E81/E82/E83/E84/E85/E86/E87/E88/E89/E90/E91/E92/E93/E94/E95/E96/E97/E98/E99/E100/E101/E102/E103/E104/E105/E106/E107/E108/E109/E110/E111/E112/E113/E114）

记录日期：2026-09-21。此批次只做后端合同、JDBC 持久化和 H2 定向验证；没有启动 Docker、应用、真实 MySQL、真实模型或浏览器。

## 本批次落地

### E50：候选重规划编排边界

`ReplanCoordinator` 将重规划固定为四段：

1. 以当前 Run 的 caller、runId 和 base revision 查找既有收据，重复请求直接返回幂等结果。
2. 调用 `ReplanRequest.assess`，只有保留原目标／requirements 且 candidate revision 严格前进的 ACCEPTED 结果才能继续。
3. 通过无副作用 `GraphPrecompiler` 预编译候选图；该接口不创建 `PersistentPlanDriver`，也不写 Graph checkpoint。
4. 把 `RevisionApplier` 作为唯一发布边界。实现者必须在同一个 writable REQUIRED transaction 中完成 consumer adoption、revision CAS 和 ACCEPTED receipt 写入；预编译失败不会触碰发布边界。

收据查询新增 owner-scoped `findFinalized`，因此基准 revision 被 SUPERSEDED 后，原 caller 仍可进行安全幂等重放查询；不放宽写入时的 ACTIVE token 校验。

### E51：报告生产生命周期

`ReportLifecycleStore` 与 `JdbcReportLifecycleStore` 持久化 reportId/revision、runId/planRevision、完整 manifest JSON 与 checksum、evidence/retained/reuse 三类时间、owner/capability、payload 和引用计数。发布按 `(report_id, revision)` 幂等，正文或 manifest 改变会拒绝冲突；读取按 `HISTORY_VIEW` 与 `EXPORT` 分别检查留存和复用期限及能力/主体；引用采用版本递增，清理使用 version + referenceCount CAS，并且留存期未结束时拒绝删除。

迁移为 `V20260920_22__campaign_report_lifecycle.sql`。报告发布器仍负责构造并校验 manifest；本批次没有注册 HTTP 路由或前端入口。

### E53：报告发布器持久化接线

`CampaignReportPublisher` 增加带 `ReportLifecycleStore` 的显式 durable 构造方式和 `publishDurable`/固定 key `read`。它复用证据校验与逐目标 `GoalAssessor`，把完整 `EvidenceManifest` 和包含 Draft/GoalAssessment 的稳定 JSON payload 交给生命周期 store；owner、capability、retainedUntil 和 reuseExpiresAt 必须由调用方显式提供。durable 构造器没有内存 fallback，旧的内存构造器和 API 保持兼容。

稳定序列化按 record 字段排序，允许合法 null 值并拒绝规范化后的重复 map key，避免重试时因插入顺序或空字段改变 manifest/payload。

### E52：重规划原子应用边界

`JdbcReplanRevisionApplier` 用同一个 writable `PROPAGATION_REQUIRED` `DataSourceTransactionManager` 包住四个有序持久化回调：加锁、consumer adoption、revision 插入和 ACCEPTED receipt 记录。每个回调前后都检查实际事务仍然存在；任一回调异常、重复键或事务外回调都会让整次应用失败并回滚，避免只更新 consumer、只切换 revision 或只留下 receipt 的半状态。

该组件是可审计的事务边界适配器，回调由可信运行装配提供实际 SQL，因此本批次没有把通用回调误称为已经接入 `JdbcCampaignRunStore`／`JdbcCampaignStatisticsConsumerStore`。后续生产接线必须让四个回调使用同一个 `JdbcTemplate` 和这条事务模板，并补充 run token、candidate hash、scope 及 owner 校验。

### E54：typed 重规划应用边界

`CampaignReplanApplicationService` 将重规划入口固定为服务端类型化请求：owner 必须与当前 `RunToken` 一致，authVersion、run/revision/token 和显式 `CAMPAIGN_REPLAN` capability 必须有效，并在调用 `ReplanCoordinator` 前重新执行 capability 授权。结果保留 `APPLIED`、`IDEMPOTENT`、`REJECTED` 及 receipt/run/assessment，不把自然语言或任意字符串 token 当作写权限。该边界没有注册到 chat、tool callback 或 Spring 默认入口。

### E55：无副作用 Native Graph 预编译

`NativeGraphPrecompiler` 按一次可信 base Run 绑定 `FrozenInputSet`、`PlanValidator` 和 `NativePlanGraph.RunIdentity`，使用 SAA 原生 `NativePlanGraph.compile`、`MemorySaver` 与禁止执行的 inert driver 只构建候选拓扑。它不创建 `PersistentPlanDriver`、不调用 `advance`、不写 checkpoint；候选 graph identity/hash 返回给协调器后，只有原子发布成功才允许正式运行图。

### E56：JDBC 重规划真实原子应用

`JdbcCampaignRevisionApplier` 接入现有 JDBC ledger：锁定 base run 并确认没有活跃 callback，快照并校验活跃 consumer 的 step、executor、output contract、冻结 scope/period，复制 base 持久化定义中的 `FrozenInputSet`，在同一个 writable `PROPAGATION_REQUIRED` 事务中先记录 ACCEPTED receipt，再 `revise`，最后接管所有兼容 consumer。历史 producer revision（`producerRevision <= baseRevision`）可继续被接管；接管失败、重复键或 callback 活跃都会回滚 receipt、revision 和 consumer。精确已提交 revision 重放返回同一 token/receipt，不重复写入。

### E57：可信重规划运行时工厂

`CampaignReplanRuntimeFactory` 将 E54–E56 的 typed 边界显式组合成一次可信运行：启动时校验 `JdbcTemplate` 与 writable `REQUIRED` 事务管理器使用同一数据源，`open` 只接受 owner、ACTIVE 状态、完整 `RunToken` 和可解析的 `FrozenCampaignRun`，每次 `execute` 按当前 base run 新建 `NativeGraphPrecompiler` 与 `ReplanCoordinator`。该工厂不注册 Spring、HTTP、chat 或 tool 入口，调用方仍需在受保护的业务 profile 中显式装配。

### E58：并发重规划精确回放

`JdbcCampaignRevisionApplier` 对旧 token 被 fencing、旧 run 已失效或旧 revision 不可写的窄失败窗口执行 owner-scoped finalized receipt 回放。只有 request JSON、candidate revision、plan hash 和提交 revision 全部匹配时才复用已提交结果；不匹配或没有收据时保留原始错误。H2 并发用例验证两个相同请求最终只有一个 revision/receipt，两个调用返回同一结果。

### E59：类型化耐久报告边界

`CampaignReportApplicationService` 将报告发布与读取固定为 typed `PublishRequest`/`ReadRequest`，在任何 durable I/O 前校验 owner、能力、revision、留存期限和复用期限；构造阶段拒绝没有 durable store 的 publisher。`HISTORY_VIEW` 与 `EXPORT` 模式原样交给生命周期 store，发布幂等和冲突语义继续由 `ReportLifecycleStore` 负责，不注册自然语言、HTTP 或前端入口。

### E60：报告授权操作审计覆盖

补充应用边界测试，明确发布、幂等重发布、历史查看和导出分别向 `AccessAuthorizer` 传递 `PUBLISH`、`PUBLISH`、`HISTORY_VIEW`、`EXPORT`；这把“先鉴权再 durable I/O”从结果断言扩展到操作类型断言。

### E61：非活动重规划基准拒绝

补充运行时工厂测试，持久化 base run 处于 `CANCELLED` 或 `SUPERSEDED` 时，`open` 在构造协调器前统一返回 `REPLAN_BASE_RUN_NOT_ACTIVE`，不允许用旧 token 继续进入重规划边界。

### E62：不同候选并发冲突

补充 JDBC 并发测试：两个不同 candidate/request 同时竞争同一 base revision 时，只有一个请求成功，另一请求返回 `REPLAN_RECEIPT_CONFLICT`；最终 ledger 保持一个新 revision、一个 receipt 和两条 consumer 记录，避免把 E58 的同请求 replay 误当成任意冲突可合并。

### E63：profile 门控的可信装配契约

新增 `CampaignTrustedAdapterConfiguration`，仅在 `campaign-trusted-adapter` profile 下组合 JDBC report lifecycle store、durable report application service 和 replan runtime factory。`PlanValidator`、Clock、两类重规划 authorizer、EvidenceReader、报告 AccessAuthorizer 和报告 capability 都必须由调用方显式提供；没有默认放行实现，也没有 HTTP、chat、tool 或 scheduler 注册。配置创建自己的 writable `REQUIRED` `DataSourceTransactionManager`/`TransactionTemplate`，并让两个 durable seam 共享 primary `DataSource`。

### E64：可信重规划 transport adapter

新增纯类型化 `CampaignReplanTrustedAdapter`。transport 只提交已认证的 caller、session 和 run 标识及 typed candidate；`RunTokenResolver` 从权威账本解析当前 token，adapter 再精确校验 caller/session/runId，并交由 runtime factory 重新检查 ACTIVE、版本和 fencing token 后才执行。resolver 缺失、主体或 session 绑定不符、过期/取消 token 和 capability 拒绝均在 receipt/revision 写入前失败；没有自由文本 token、模型参数、HTTP/chat/tool/scheduler 注册。激活 profile 时 resolver 必须由调用方显式提供，缺失即 fail-fast。

### E65：耐久报告读取投影

新增 `CampaignReportReadProjection`，把已授权的 durable report read 转为固定 `campaign-report-snapshot/v1` typed snapshot。读取继续唯一经过 `CampaignReportApplicationService`，`HISTORY_VIEW`/`EXPORT` 原样传给 lifecycle store；投影不暴露 owner/capability，也不把空结果回退成旧报告或重新查询。对持久 payload 的根字段、可选 payload schema、Draft schema、reportId/revision/run/planRevision 一致性和逐目标 assessment 做严格校验；未知字段、重复 goal、解析失败统一 fail closed 为 `REPORT_PAYLOAD_INVALID`。输出列表保持不可变，并保留报告留存元数据而不泄露主体授权信息。

### E66：typed snapshot 到 legacy answer 兼容桥

新增纯 `CampaignLegacyAnswerAdapter`，把已授权的 typed snapshot 与可信的 server-owned execution status 映射为固定 `campaign-legacy-view/v1`。只有 `SUCCEEDED`、至少一个目标、所有目标真实 `ANSWERED` 且存在报告 block 时才标记 `COMPLETE`；等待、未知、失败或取消不会从自由文本升级完成。无报告的空闲与不可用状态保持区分，成功但缺报告、报告与 `EMPTY` 状态矛盾、空白限制说明均 fail closed。兼容视图保留完整 typed snapshot、blocks、goal assessments、目标汇总和不可变 limitations，不引入 owner/capability、自由文本推断、Graph、HTTP 或 chat 接线。

### E67：typed response bridge

扩展 `AgentRunResult`，保留既有 9 参数构造和旧字段序列化；新增的 sanitized `report` 仅在有值时输出。`CampaignAgentRunResultAdapter` 接受既有运行结果与 E66 trusted legacy view，以 view 的 answer/status/report 为唯一响应事实，复制并冻结 cards、pending actions、tool calls、data sources、trace events 和 warnings；只把 schema、availability、execution status、reportRef、blocks、goal assessments、goal rollup 与 limitations 带入 response，不暴露 snapshot 的 owner/capability/retention 元数据。状态矩阵和 null/矛盾输入在 adapter 与 response record 两层 fail closed。本批没有修改 Graph、checkpoint、controller、Spring 装配或 durable run→report 查询，因此 adapter 尚未由生产运行路径调用。

### E68：typed run-result projection

新增纯 `CampaignRunResultProjection`，把已授权的 `CampaignProgressView` 与可选 `CampaignReportReadProjection.Snapshot` 组合成固定 `campaign-run-result/v1`。投影只接受显式的 server-owned `ExecutionStatus` 与 `NextAction`，不从自由文本推断状态；严格校验 run/plan/revision、draft 与 reportRef、逐目标集合及终态 work state。`EXECUTED` 只有在所有目标真实 `ANSWERED` 且存在可交付 block 时才能映射为 `SUCCEEDED`；`WAITING` 可携带部分报告（durable `BLOCKED` 映射为公开等待态），运行中、失败、未知状态不得带完整报告，`CANCELLED`/`SUPERSEDED` 永不暴露过期报告。输出只保留 reportRef、blocks、goal assessments、汇总和限制说明，列表不可变，不带 payload、owner、capability、retention 或复用元数据。本批仍不写数据库、不读 Graph checkpoint、不注册 controller 或 Spring 入口。

### E69：授权 run→report 读取组合

新增 `CampaignRunReportReadProjection`，只组合已有 `CampaignProgressService` 与 `CampaignReportReadProjection` 的授权读取。调用方必须显式提供一个已解析的 `reportRef`、owner、capability 和 `HISTORY_VIEW`/`EXPORT` 模式；没有 reportRef 时不扫描历史、不猜测最新报告。读取结果必须与当前 run 的 runId、planId、plan revision、报告 draft、reportRef 和模式完全一致，否则 fail closed。执行状态与 nextAction 由 typed progress、planning gap 和报告完整证据的确定性映射生成：缺输入使用 `NEEDS_INPUT`，阻塞使用 `WAIT`/`REPLAN`，失败使用 `RETRY`，执行完成但没有可读报告保持 `UNKNOWN`；不触发 dispatch、refresh、poll、重试或写入。结果继续交给 E68 脱敏投影，因此客户端不会收到 owner、capability、manifest、payload 或 retention 字段。本批没有新增反向 run→report 持久化索引，仍需后续可信运行账本提供 reportRef。

### E70：耐久 run-result 绑定

新增 `CampaignRunResultStore` 与 `JdbcCampaignRunResultStore`，在 `campaign_run_ledger` 的精确 `(run_id, revision)` 行锁内，把 `reportRef`、`executionStatus`、`nextAction`、required inputs 和 limitations 作为一条小型、带源版本围栏的事实写入 `campaign_run_result_binding`。写入只接受当前 `RunToken` 的 caller、definition hash、row version 和 advance token；活动 run 不得写入取消或 superseded 状态，终态 run 只能写入对应终态。`SUCCEEDED` 必须有报告引用，等待态允许指向已验证的部分报告，空闲与取消／改版结果不得带过期报告。

报告引用通过构造器注入的 `ReportBindingVerifier` 校验，没有默认放行或把报告正文、owner、capability、manifest、payload 写进绑定。相同 token 与相同事实精确重放返回同一行；同一 run revision 的不同事实、重复报告引用、旧 token、主体或定义不匹配均 fail closed。读取按明确 revision 和当前 caller 过滤，不扫描历史、不回退 latest，输出列表保持不可变。迁移接受初始 run ledger 的 `row_version=0`，并保留运行账本外键；报告生命周期引用的 retain/release 接线仍由后续可信装配负责。

### E71：durable binding 到授权结果读取组合

新增 `CampaignDurableRunResultReadService`，先按 caller、runId 和显式 revision 读取 E70 binding，再把 binding 中唯一的 `reportRef` 交给 E69 的授权读取组合。没有报告引用时不要求报告 owner/capability；有引用但没有显式报告凭证时直接拒绝。读取结果必须同时满足当前进度的 run/plan/revision、durable executionStatus/nextAction/limitations 和报告 reportRef/draft 身份，任一漂移都 fail closed；返回仍是 E68 的脱敏投影，不暴露报告正文、manifest、owner、capability 或 retention。

新增 `CampaignRunReportBindingVerifier`，把 report lifecycle 的 READY、runId、planRevision、owner、capability 和 HISTORY_VIEW 检查收敛为注入式边界，并提供显式 retain/release 操作。它不缓存 payload、不默认放行，也没有注册 Graph、HTTP、chat 或 Spring 入口；retain/release 与 binding 写入的同事务生产接线仍需后续可信装配完成。

### E72：run-result 绑定的原子生命周期协调

新增 `JdbcCampaignRunResultBindingCoordinator`，把报告引用 retain、E70 durable binding 写入和 release 收敛到同一条共享数据源的可写 `PROPAGATION_REQUIRED` 事务。构造时检查结果存储、报告生命周期存储和事务管理器确实使用同一 `DataSource`，并拒绝只读或其他传播级别，避免调用方误把两个独立事务拼成“原子”操作。

绑定前先锁定并校验当前 `RunToken`，再保留已授权的固定 `ReportRef`；token fencing、状态冲突、重复报告或任何后续异常都会回滚引用计数和绑定行。释放同样要求当前 token，并核对 durable binding 中记录的 reportRef，避免过期 token或无关报告删除活动引用。该协调器仍是显式 typed 组合件，没有注册 Graph、HTTP、chat 或 Spring 入口。

### E73：request-bound run-result adapter

新增 `CampaignTrustedRunResultAdapter` 作为请求边界。它不把某个租户的 owner/capability 固定在单例协调器中，而是在每次 bind/release 调用时校验 transport 传入的 `Caller` 与 `RunToken` 一致，并用本次解析出的 report owner/capability 创建短生命周期 verifier 和 E72 coordinator。这样多租户请求可以复用同一 JDBC/事务基础设施，但不会共享报告身份。

release 也重新经过 `HISTORY_VIEW` 的报告授权检查，再进入 E72 的 token 与 binding reportRef 校验；错误主体、错误能力或过期 token 都不能改变引用计数。该 adapter 仍是显式 typed 组件，不进入默认 profile、Graph、HTTP、chat 或 scheduler。

### E74：终态 run-result 引用清理

新增 `CampaignTerminalRunResultCleanup`，将终态清理固定为显式的 `(caller, runId, revision, expectedReportRef)` 请求。它先在同一 writable `REQUIRED` 事务内锁定精确 ledger 行和 result binding，只接受 `CANCELLED`/`SUPERSEDED`；活动 run、主体不匹配、revision 不合法或 reportRef 不匹配都在任何引用变更前拒绝。绑定已经产生报告后再被取消或 superseded 的情况也能清理，因为终态维护路径只验证绑定事实，不把公开读取的运行状态矩阵误用作清理授权。

`ReportLifecycleStore.releaseReferenceIfPresent` 是仅供可信终态维护使用的原子 primitive：它锁定 report lifecycle 行，删除确定性的 `run-result-binding:<runId>:<revision>` 引用并同步递减 `reference_count`/递增 `row_version`；报告已过期、引用已不存在或 lifecycle 主行已被清理时均返回明确结果，不读取正文、不检查 owner/capability/expiry。正常 `read`/`retain`/`release` 仍保留各自的授权和可读性规则，`retain` 与清理统一按 report 行锁串行化，避免清理提交后又插入悬空引用。该组件仍是显式 typed 组合件，没有注册 Graph、HTTP、chat 或 scheduler 入口。

### E75：报告发布与 run-result 绑定原子化

新增 `CampaignRunReportPublicationCoordinator`，把 typed 报告发布和 E73 request-bound run-result binding 放进同一条共享数据源、可写 `PROPAGATION_REQUIRED` 事务。构造时严格验证报告应用服务使用同一个 `JdbcReportLifecycleStore`、结果 adapter 与 JDBC/事务模板共享同一数据源，并拒绝只读或其他传播级别；发布前校验 caller、run/plan/revision 和 binding 的 `reportRef` 与 draft 身份一致。报告授权、证据和生命周期规则仍由 `CampaignReportApplicationService`/`CampaignReportPublisher` 负责，结果 token fencing 和 owner/capability 仍由 trusted adapter 负责，协调器不引入默认放行。

同时收紧 `JdbcCampaignRunResultBindingCoordinator` 的精确重放：在 run→binding 行锁内先读取既有 binding，只有事实完全一致且当前 owner/capability 仍能通过报告只读授权才返回；冲突或撤权立即拒绝，报告 lifecycle 不会因重试重复 retain。发布协调器先取得 run/binding 锁，再发布报告并 retain，统一为 run→binding→report 锁序；返回值只含脱敏 `ReportRef` 与 binding。绑定前还用确定性的 GoalAssessor 校验完整报告只能写入 `SUCCEEDED`，部分报告不能伪装成功。这样报告发布失败、过期 token 或 binding 失败会整体回滚，不留下无引用报告或半绑定结果。该组件已在 `campaign-trusted-adapter` profile 显式装配，但尚未接入 Graph、AgentRunHarness、HTTP 或 chat 入口。

### E76：durable run-result 到兼容响应的 typed 组合

新增纯 `CampaignDurableRunResponseAdapter`，把 E68 的脱敏 `campaign-run-result/v1` projection 组合到既有 `AgentRunResult` 外壳。没有 durable binding 时返回 `Optional.empty()`，不猜测最新报告、不回退旧 answer；有 projection 时只复制既有运行结果的操作字段，并由 projection 的 typed 状态、目标评估、报告块、汇总和限制说明生成兼容 answer/report。可选 `Identity` 会再次校验 runId/planId/revision，报告引用、目标与 block 标识、汇总计数和状态矩阵均在响应边界 fail closed。

`SUCCEEDED` 只允许完整且存在可交付 block 的报告；`RUNNING`、`WAITING`、`FAILED`、`UNKNOWN` 的不完整报告映射为 `PARTIAL`，失败/未知无报告映射为 `UNAVAILABLE`；`CANCELLED`/`SUPERSEDED` 不接收过期报告，其中旧 `AgentRunResult` 没有 `SUPERSEDED` 枚举，适配器对该状态拒绝而不降级伪造。兼容桥同时收紧成功无 snapshot、终态部分报告和伪造目标汇总，响应仍不包含 owner、capability、payload、retention 或复用元数据。本批没有接 Graph、AgentRunHarness、HTTP、Spring 或客户端路由。

### E77：精确 durable read 到兼容响应服务

新增纯 `CampaignDurableRunResponseService`，把 E71 的精确 caller/run/revision 读取和 E76 的脱敏响应适配固定为一个 typed 服务边界。一次调用只执行一次 durable read；空 binding 原样返回空结果，不扫描历史、不回退旧 revision、不读取 Graph 文本。读取到 projection 后再次核对 runId/revision，身份漂移立即 fail closed，再将同一 projection 交给 E76 adapter；E71 的报告凭证、状态和计划身份校验仍是唯一事实来源。

服务只接受显式的 server-owned base `AgentRunResult` 与 `CampaignDurableRunResultReadService.Request`，不持有 store、Graph、HTTP、Spring 或缓存状态；读取异常和 adapter 异常原样透传。这样后续生产装配只需接入一个窄的 response seam，不会因为多个读取入口产生 fallback 或重复查询。

### E78：durable response 的请求身份与显式装配边界

新增 `CampaignDurableRunResponseRuntimeFactory`，只接受已经授权的 `CampaignRunResultStore` 与 `CampaignRunReportReadProjection`，每次 `create()` 都重新组合 E71 read service、E76 response adapter 和 E77 response service。factory 不保存 caller、owner、capability 或运行请求状态，也不注册 Spring、Graph、HTTP 或 chat bean，避免未来调用方绕过 exact revision read 或复用跨请求身份。

新增 `CampaignDurableRunResponseDecorator` 作为 campaign 专用请求边界：请求必须携带 server-owned base response、`Caller`/run/revision read request 和预期 `planId`；E77 在适配前核对 runId、planId、revision 三元身份。decorator 将空 binding 映射为明确的 `NO_BINDING` outcome，绑定结果映射为 `BOUND_RESPONSE`，上层必须显式决定展示或阻断，不能把空结果隐式回退为 Graph 文本。报告凭证仍只从 typed `ReportAccess` 进入，缺失时直接拒绝。

### E79：新响应协议能力门控

新增纯 `CampaignResponseCapabilityGate`，固定 `campaign-response/v2` 协议和同名客户端能力。新 Run 只有在服务端开关开启且客户端明确声明能力时才选择 durable v2，否则沿既有 legacy 路径；已经进入 v2 的 Run 若由未声明能力的旧客户端续接，返回 `CLIENT_UPGRADE_REQUIRED`，即使服务端开关已关闭也不回退重取。已存在的 legacy Run 不会仅因客户端声明 v2 而升级，未知 capability 不会被当作协议能力。

该 gate 只负责协议路径选择，不承担 owner/capability 授权，也不注册 HTTP、Graph、Spring 或 chat 入口；能力集合在请求边界复制为不可变值，未知协议、空白协议和缺失输入 fail closed。

### E80：durable response 的受保护 Spring 装配

扩展 `campaign-trusted-adapter` profile 的显式装配契约：调用方必须提供具名的只读 `CampaignRunResultStore` 与 `CampaignRunReportReadProjection`，配置据此创建单例 `CampaignDurableRunResponseRuntimeFactory` 和 `CampaignResponseCapabilityGate`，并以 prototype scope 为每个请求创建新的 `CampaignDurableRunResponseDecorator`。缺少任一具名 provider 时 profile 启动直接 fail fast；配置不提供默认 store、授权器、caller、capability 或 server 开关。

装配层不把 E77 service 或 E71 read service 注册为跨请求 singleton，也不把可写 JDBC store 直接伪装成 response provider。该批次只证明 profile 内的 typed composition 和请求级实例隔离，仍没有接 Graph、AgentRunHarness、HTTP、chat 或客户端路由。

### E81：固定报告读取与脱敏 facade

新增 `CampaignReportReadFacade`，为未来报告页面、历史和导出入口定义稳定的 typed view。请求必须明确给出 `reportId`、正整数 `revision`、owner、capability 和 `HISTORY_VIEW`/`EXPORT` mode；facade 将固定 key 交给既有 `CampaignReportReadProjection`，不接受 latest、模糊 key，也不扫描历史或重新查询数据。

返回的 `campaign-report-view/v1` 只包含 mode、reportRef、run/plan 身份、已校验的 `ReportDraft` 和逐目标 `GoalAssessment`。manifest checksum、evidence/retention/reuse 时间、owner、capability 和持久化 payload 元数据留在内部 projection，不进入 route-level view。固定 key 或 mode 发生漂移时 fail closed，空 durable read 保持空结果。

### E82：JDBC durable read 快照协调

新增 `JdbcCampaignDurableRunReadCoordinator`，把一次精确的 durable run 读取收敛到同一个共享数据源、可写 `PROPAGATION_REQUIRED` 事务。请求必须给出 caller、`runId` 和正整数 revision；协调器按既定的 run → binding → steps/receipts → report 顺序锁定当前事实。没有 binding 时在读取 progress/report 前直接返回 `NO_BINDING`，不会扫描最新 revision 或回退 Graph 文本。

绑定存在时，协调器把 binding 的 source row version/advance token 与请求指定 revision 的 progress 快照逐字段核对；run 在读后推进会得到 `RUN_RESULT_READ_CONFLICT`，任何冲突都以异常回滚而不是降级返回。协调器不会把请求 revision 改成 latest，也不会扫描历史。有 reportRef 时必须显式提供 owner/capability/mode，报告只返回 identity（固定 key、run、plan revision、row version）；其中 plan id 仍是 binding-side identity，报告 payload 的独立 plan 校验由后续 E69 projection 负责。payload、owner、capability 和生命周期字段仍留在既有授权 projection 内。该组件是 JDBC/typed 内部边界，尚未接 E71/E80、Graph、AgentRunHarness、HTTP 或 chat 路由，也不以 H2 结果替代真实 MySQL 隔离验证。

### E83：事务内 JDBC durable response bridge

在 E82 的事实读取边界上增加 typed projector seam：`readProjected` 在同一个 writable `REQUIRED` 事务中完成 binding、精确 progress、报告 lifecycle 行锁与授权，然后在事务关闭前依次执行报告脱敏投影和响应投影。报告 projector 只能把已授权的 lifecycle 行转换为 `CampaignReportReadProjection.Snapshot`；`Published` 的 owner、capability、payload、checksum、retention 等内部字段不会沿响应边界传播。

新增 `CampaignJdbcDurableRunResponseBridge`，一次调用只执行一个 E82 coordinator read，使用预加载 progress/report 事实生成 E68 projection，逐项核对 binding 的 run/plan/revision、execution status、next action、limitations 和 reportRef，再交给 E76 adapter。无 binding 保持显式 `NO_BINDING`；不在事务结束后再次调用 E71，也不回退 Graph、latest revision 或自由文本。`CampaignProgressService` 与 `CampaignRunReportReadProjection` 增加只消费已捕获快照的投影入口，继续保留 artifact/scope 和报告身份校验。

本批仍是 JDBC/typed 内部组件，未接 Spring provider、Graph、AgentRunHarness、HTTP 或 chat 路由；H2 只验证事务组合和脱敏合同，不替代真实 MySQL 的锁隔离验收。

### E84：受保护的 JDBC durable response provider 工厂

新增 `CampaignJdbcDurableRunResponseBridgeFactory`，把 E83 bridge 放进请求无关的基础设施工厂。工厂只保存 JDBC、Clock、同一可写 `REQUIRED` `TransactionTemplate` 和无请求身份的 typed projectors；每次 `read` 才根据本次 caller、精确 run/revision 及可选 report owner/capability/mode 创建 verifier、binding store、E82 coordinator 和 E83 bridge。没有 report access 时使用拒绝默认值，不能把报告身份或 allow-all 授权保存到 singleton。

新增 `campaign-trusted-adapter & campaign-jdbc-durable-response` 的 conjunctive Spring profile。所有 durable progress reader、lifecycle store、progress/report/result projector 都必须由调用方以具名 provider 提供；配置不创建默认 authorizer、caller、run、报告凭证或 transport。工厂构造时还验证 binding/progress/step/run/artifact/report 参与者共享同一 DataSource 和同一 writable REQUIRED transaction，避免预加载快照后由错误数据源再次读取 artifact。E80 的 legacy generic response factory 保持不变。

本批只完成 provider/factory 组合边界，仍未注册 Graph、AgentRunHarness、HTTP、chat、scheduler 或客户端路由；H2/Spring context 只证明 profile 门控、命名依赖、事务组合和请求级身份隔离，不替代真实 MySQL 锁隔离及业务入口验收。

### E85：能力门控与 durable 响应路由适配器

新增 transport-neutral `CampaignResponseRouteAdapter`，把 E79 能力门与 E84 JDBC durable response factory 连接成一个显式的单次选择边界。路由先执行 capability gate：`LEGACY_PATH` 只返回旧路径选择，`CLIENT_UPGRADE_REQUIRED` 只返回客户端升级状态；二者都不会调用 durable reader。只有 `DURABLE_V2` 才接受已经由服务端解析并授权的 E84 request（固定 caller、runId、revision、planId 及可选报告凭证），并且只调用一次 factory。

E84 的 `NO_BINDING` 被保留为独立 typed 状态，不能携带 Graph base response；绑定成功才返回脱敏的 `AgentRunResult`。durable reader 的异常原样向上传播，不重读 latest/history，也不切回 Graph、E71 legacy decorator 或自由文本。适配器不解析 session、trace、message，不保存请求身份，也没有修改通用 `AgentRunRequest`、`DefaultAgentRunHarness`、Graph executor 或 `AgentChatController`。

`campaign-trusted-adapter & campaign-jdbc-durable-response` profile 现在显式提供该适配器，但仍不注册 HTTP、chat、Graph 或 scheduler 入口；后续 transport 必须先解析 server-owned handle，再把 typed request 交给此边界。

### E86：精确 revision 的 server-owned response handle

新增脱敏的 `CampaignRunHandle` 与 `CampaignRunHandleResolver`。handle 只包含 caller、session、run、plan、revision 和持久化状态，不携带 definition JSON、row version 或 advance token；这些字段属于执行凭证或 durable payload，不能进入只读响应路径。`JdbcCampaignRunHandleResolver` 通过新增的 `readRunAtRevision` 精确锁定 `(caller, runId, revision)`，核对 session、plan 和所有身份字段，永远不调用按 run 取最高 revision 的通用读取，也保留 `ACTIVE`、`CANCELLED`、`SUPERSEDED` 事实供下游 projection 处理。

`CampaignDurableResponseTransportAdapter` 先执行 E79 capability gate，只有 `DURABLE_V2` 才解析 exact handle；legacy 和客户端升级路径不会触碰 resolver。resolver 缺失、身份不一致或异常均 fail closed，不回退 Graph/base response。验证通过后才把脱敏身份和请求级报告凭证交给 E84 route/factory。配置仍受 `campaign-trusted-adapter & campaign-jdbc-durable-response` conjunctive profile 保护，未注册 HTTP、chat、Graph、scheduler 或客户端入口。

本批只建立 transport-neutral 的 server-owned identity seam；没有把尚未具备 durable `runId/revision/planId` 和协议字段的通用 Agent/Graph 请求强行接入，也没有把 `RunToken` 当作响应凭证。

### E87：统一 transport-neutral response envelope

新增 `CampaignResponseEnvelopeAdapter`，把 E86 transport selector 的四种结果收敛为稳定的 typed/wire 双层合同：`LEGACY_PATH` 携带原始 Graph `AgentRunResult`，`DURABLE_RESPONSE` 只携带 durable 脱敏响应，`CLIENT_UPGRADE_REQUIRED` 与 `NO_BINDING` 均为空响应并保留独立 wire code。结果构造器拒绝状态、wire code 和 response 的组合漂移；durable reader 的异常直接传播，绝不转成 legacy fallback，也不在 adapter 之间共享请求状态。

本批只负责输出封装，不伪造协议元数据、caller、run handle 或报告凭证。E79 gate 的原始 metadata 输入、E86 request reference 与 E84 `ReportAccess` 仍需要后续由 intake/principal 权威 provider 签发；在该 provider 接入前，envelope 仍是 trusted transport-neutral seam，不注册 Graph、HTTP 或 chat 入口。

### E88：权威响应协议元数据与 transport seam

新增 `CampaignResponseProtocolMetadata` 与 `CampaignResponseProtocolMetadataResolver`，把 run kind、服务端协议版本、启用状态和（已有运行所需的）精确身份改为由 trusted metadata provider 解析。调用方只能提交 caller、session、可选的精确 run reference 和客户端能力集合，不能再把 `runKind`、`serverRunProtocol` 或 `serverEnabled` 当作已有运行的证据。旧的 `CampaignResponseCapabilityGate.Request` API 保留为兼容入口并标记 deprecated；新的 authority overload 只接受 resolver 返回的 metadata。

`CampaignDurableResponseTransportAdapter.AuthorityRequest` 先读取权威 metadata，再执行 E79 gate；legacy 与 `CLIENT_UPGRADE_REQUIRED` 在解析 durable handle 前直接返回，metadata 缺失、已有运行缺少 identity、精确 handle 不匹配或 resolver 异常均 fail closed，绝不回退 legacy 或 latest。durable 路径仍要求 exact revision handle，并把 metadata identity 与 handle 的 caller/session/run/plan/revision 逐项核对。该批只建立 transport-neutral authority seam，未把 resolver 注册到 Graph、AgentRunHarness、HTTP、chat 或 Spring 生产入口；E84 的 raw `ReportAccess` 仍留给下一批 principal-bound grant 适配。

### E89：principal-bound report access grant

新增 `CampaignReportAccessGrant` 与 `CampaignReportAccessGrantResolver`，把报告读取授权封装成一次请求、一个 exact run handle、一个明确 mode 的短生命周期 grant。resolver 要求 caller 与 handle caller 完全一致，由 trusted authorizer 返回服务端解析的 owner；capability 固定为 `campaign/report/v1`，不会从客户端能力、请求字段或 report 文本生成。`HISTORY_VIEW` 与 `EXPORT` 仍由 authorizer 分开决定，授权失败或 `SUPERSEDED` run 直接拒绝。

新增 `GrantAuthorityRequest`，authority transport/envelope 只接受 opaque grant，不再接收 raw `ReportAccess`。transport 先读取权威协议 metadata、执行 gate、解析 exact handle，再逐项核对 grant 的 caller/session/run/plan/revision；缺 grant、grant 错配、报告授权失败或 durable reader 异常均 fail closed，不回退 legacy。raw `Request` 与无报告的 `AuthorityRequest` 仅保留内部兼容/无报告路径；grant 的 owner/capability 只有同包 adapter 在构造 E84 request 时可见，不进入 wire envelope。该批仍未接 Graph、HTTP、chat 或生产 Spring provider。

### E90：受保护 authority provider 的显式装配

`CampaignJdbcDurableResponseConfiguration` 继续使用 `campaign-trusted-adapter & campaign-jdbc-durable-response` 联合 profile，并将 authority transport 改为四参数装配：除 route 与 exact handle resolver 外，必须由宿主显式提供具名的 `CampaignResponseProtocolMetadataResolver` 和 `CampaignReportAccessGrantResolver`。配置不会创建默认 metadata、principal、owner、capability 或 allow-all resolver；缺少任一 provider 时 Spring context 直接 fail fast，避免受保护 durable response 在未完成身份接线时悄然启用。

`CampaignDurableResponseTransportAdapter` 在 grant authority 路径先校验 opaque grant 与 resolved handle 的绑定，再通过可信 grant resolver 重新签发请求级 grant，只有重新签发的服务端 owner/mode 才能转换为内部 E84 `ReportAccess`。旧的二／三参数构造器保留给兼容的无报告/旧 typed caller，但受保护 Spring 组合只使用四参数构造器。该批仍不注册 Graph、HTTP、chat 或客户端入口。

### E92：REQUESTED 释放意图的有界恢复读取

`CampaignStatisticsReleaseStore` 新增 `pendingRequested(caller, limit)` 只读恢复索引。JDBC 实现按当前租户、主体和授权版本过滤 `REQUESTED` 且生产 Run 仍为 `ACTIVE` 的绑定，使用 `expires_at、producer_run_id、revision、child_id` 的稳定顺序和 256 条上限；它不 claim、不推进 Run、不触发远端调用，也不返回请求正文、Artifact 正文、报告凭证或 RunToken。返回的最小 `PendingIntent` 只包含精确 run/revision/child/job、binding version、原期限和状态，调用方必须再次解析 exact token 并重新执行释放门控。

映射器交叉核对 release、run、child、Artifact 元数据和已发布 receipt 的身份、主体、版本、hash、wire/spec 与期限；任何缺行、状态漂移或篡改都以 `RELEASE_PENDING_BINDING_CORRUPTED` fail closed。重复读取不写入任何 release/确认字段，已确认、非活动、外部主体和超出 limit 的记录不会进入结果。该批仍不注册 scheduler、Graph、HTTP 或生产恢复入口。

### E93：容量退避到期候选的有界发现

新增 `CampaignSubmissionDeferralReader` 及 JDBC 实现，按当前 caller、ACTIVE run、ASYNC/PREPARED child、`QUERY_CAPACITY_EXHAUSTED`、FRESH attempt、无 job/artifact、callback 已退出和 `retry_not_before <= now` 枚举最多 256 个候选，排序固定为退避时间、run、revision、child。`DueCandidate` 只携带 run/revision/step/child、capacityKind、拒绝次数、退避期限和源 row version，不包含 RunToken、advance token、请求体、Artifact 或远端 job。

查询通过 run→child→action→step→deferral 交叉校验 owner、版本、requestId/wireHash、attempt fence、容量枚举、次数和期限；任何篡改统一 `CAPACITY_PENDING_CORRUPTED` fail closed。读取不 claim、不 advance、不刷新 step、不提交、不触网；后续恢复者必须按精确 `(runId, revision)` 重新解析 token，并在锁内重复 `refreshCapacityDeferred`/`beginDispatch` 门控。该批不注册 scheduler、Graph、HTTP 或 Spring 入口。

### E94：受保护 response envelope 的 Spring 装配

在 `campaign-trusted-adapter & campaign-jdbc-durable-response` 联合 profile 下新增单一 `CampaignResponseEnvelopeAdapter` bean。它只接收已经通过 E90 显式 protocol/grant provider 校验的 `CampaignDurableResponseTransportAdapter`，不创建默认授权、principal 或 metadata provider；profile 未启用时不注册 envelope，任一 authority provider 缺失仍由 transport 装配直接 fail fast。该批只补齐 typed composition，不注册 Graph、HTTP、chat、scheduler 或客户端入口。

### E95：权威重规划 RunToken resolver

新增 `JdbcCampaignReplanRunTokenResolver`，把受信重规划请求的 token 解析固定在 owner 绑定的 JDBC run ledger 上。每次调用都按 owner 与 runId 读取当前最新 revision，逐项校验 caller、runId、session、revision、version 和 advance token；只有 `ACTIVE` 返回 token，缺失、会话不符、`CANCELLED` 或 `SUPERSEDED` 返回空，owner 不符由账本授权边界拒绝。resolver 不缓存、不生成 token、不接受调用方携带 token，`CampaignReplanRuntimeFactory` 的二次持久化状态/fencing 校验保持不变。

受保护配置只要求具名 `campaignReplanRunTokenResolver` provider，不注册默认或 allow-all 实现；测试覆盖 token advance/cancel 后重新读取、旧 revision 不回退、主体/会话隔离和非法输入。该批仍不接 Graph、HTTP、chat、scheduler 或生产运行入口。

### E96：版本化 Plan runtime registry 与显式 saver 边界

新增不可变的 `CampaignPlanRuntimeRegistry` 与 `CampaignPlanRuntimeFactory`。registry 只保存服务端注册的 capability catalog、artifact contract、FIXED/REACT executor、candidate store 和版本元数据，并以精确 `SaverKey` 描述允许的 checkpoint 实现；重复 executor、catalog 版本漂移、REACT 缺少 candidate store 或缺失注册均 fail closed。它不会查找 Spring bean、创建 `MysqlSaver` 或提供默认 allow-all 实现。

`open` 只按 caller、session、runId 读取并核对 ACTIVE 持久化 run、完整 definition、RunToken、plan/revision、冻结 runner/topology 版本、注册能力和授权，不初始化 step、不创建 driver、不编译 Graph、不 advance。只有显式 `PreparedRuntime.compile(SaverBinding)` 才交出匹配 key 的 `BaseCheckpointSaver` 并构造现有 `PersistentPlanDriver`/`NativePlanGraph`；该批不接 Graph/HTTP/Spring 生产入口、Recovery 或真实 MySQL saver。

### E97：可信 response authority 的请求级组合

新增 `CampaignTrustedResponseAuthorityProvider`，只组合已由可信入口解析的 caller、protocol metadata、exact `CampaignRunHandle` 和 opaque `CampaignReportAccessGrant`。它逐项校验 tenant/subject/authVersion、session/run/plan/revision、metadata identity 与 report mode，输出不可变 request-local `Bound`；原始 grant、owner、capability 和 report payload 不通过公共 getter 暴露。该 adapter 不解析 principal、不读库、不创建默认授权，也不接 Spring、Graph、HTTP 或网络。

### E98：正式交付状态矩阵回归

新增 `GoalAssessorTest`，把报告发布前的目标判定边界固定为后端合同：单 Tool 缺少分析只能 `PARTIAL`，部分证据不能跨目标复用，`NEEDS_INPUT`/`UNAVAILABLE`/`UNSUPPORTED` 不得变成 `ANSWERED`，没有 requirement 的目标保持 `PENDING`，plan/assessment/draft 的 run、plan、revision 漂移必须 fail closed。该批只补确定性 assessor 回归，不接报告路由、Graph、HTTP 或客户端。

### E99：重规划候选门禁

新增 `CampaignReplanCandidateGate`，先通过现有权威 `RunTokenResolver` 精确解析 owner/session/run 的当前 token，再按 exact token + step 读取 exploration assessment；只有 `REPLAN_REQUESTED` 才形成脱敏 `PendingReplan`。输出只保留 server-owned handle、candidate hash 和不可变 reason codes，原始 `RunToken` 仅用于本次读取，不解析自由文本、不构造 `PlanSpec`/`ReplanRequest`，不写 receipt/revision，也不触发 Graph、coordinator 或 tool。

### E100：历史 producer 释放候选读取

新增 owner-scoped 的 `CampaignStatisticsHistoricalReleaseRecoveryReader`。JDBC 实现通过 release intent、物理 job binding、run/child、artifact/payload/receipt 的身份链，只读取 `SUPERSEDED`、`REQUESTED`、未过期、READY、无 callback、local-only 且无 active consumer 的候选；严格区分 release binding ID 与物理 job binding ID，并校验 action/plan/request/artifact/hash/spec/version/expiry。结果不包含 token、请求体或 artifact payload，仍只是 advisory fact，不是授权或执行许可。

### E101：历史 producer 释放二次 permit gate

新增 `CampaignStatisticsHistoricalReleaseRecoveryGate`，将 E100 的 advisory candidate 作为输入，在一个显式 REQUIRED JDBC 事务内按固定顺序重新锁定 source run、physical binding、child、active consumers 和 release intent，再核对状态、版本、期限及 action/plan/request/artifact/hash/spec 身份链。过期、callback、active consumer、binding/release 漂移或损坏事实均不产生 permit；输出只含 release/physical binding identity 与版本事实，不含 RunToken、advance token、请求体或 artifact payload，也不 claim、不写状态、不触网。

### E102：重规划 typed planner handoff

新增无状态 `CampaignReplanPlanningHandoff`，把 E99 的脱敏 `PendingReplan` 与可信入口解析的 baseline `PlanSpec`/`PlanningAssessment` 绑定，核对 owner/session/run/plan/revision/ACTIVE、step 归属、固定 reason allowlist、候选声明和证据/文本边界，再委托既有 `ReplanRequest.create` 处理 known gap、证据新鲜度、唯一性和 trigger。输出只有 immutable typed planning request、assessment 和受限 metadata，不持有 RunToken/definitionJson，不写 receipt/revision，不触发 Graph、coordinator、tool 或网络。

### E103：历史恢复动作分类

新增 `CampaignStatisticsHistoricalReleaseRecoveryAction`，把 E101 已通过二次事实锁定的历史 release permit 映射为固定、不可变的动作分类和最小身份。分类逐操作复制 release/physical binding/child/job 事实，拒绝过期、非法 clock 或不完整 trusted 输入；它只描述允许的历史动作，不 claim、不写 release 状态，也不触发远端操作。

### E104：重规划候选准入与身份快照

新增 `CampaignReplanCandidateAdmission`，在 E102 handoff 后校验 canonical candidate hash、owner/session/run/plan/revision/status、baseline 和 reason codes，输出不含 RunToken、definitionJson 或执行状态的不可变 admission snapshot。等价 plan、身份漂移、hash/reason 不符均 fail closed；该快照仍是 planner handoff 后的纯 typed 准入事实，不创建 revision、receipt 或执行 coordinator。

### E105：投放报告模块投影

新增 `CampaignReportModuleSelector`，把已授权的多目标报告事实投影为稳定的 `campaign-report-modules/v1` 模块集合。模块按 goalId 稳定排序，只保留 evidence refs、block identity/kind/title 和完成标记；不携带 owner、token、capability、payload 或隐藏执行字段。只有存在证据且至少一个可渲染结果 block（METRIC/CHART/TABLE/RESULT_LINK）时才允许目标标记为 ANSWERED；等待、缺证据、未知 block 归类为 PARTIAL/UNAVAILABLE，并拒绝跨目标、未知或歧义 block 归属。

### E106：准入候选到执行请求的可信绑定

新增 `CampaignReplanAdmissionBinder`，在显式执行边界把 E104 admission 与既有 `CampaignReplanApplicationService.Request` 组合。绑定前精确校验 owner/session/run/plan/base revision、RunToken 版本、固定 `CAMPAIGN_REPLAN` capability、accepted assessment 和 canonical candidate hash；legacy handoff、身份漂移、过期或低版本 token、能力错误和 hash 不一致均 fail closed。输出只含可信 RunToken、typed handoff、admitted candidate 与 candidate assessment，不执行持久化、Graph、HTTP、模型或工具调用。

### E107：准入候选到可信重规划执行

新增 `CampaignReplanAdmissionExecutor`，执行请求只接受 E102 handoff 与 E104 `CandidateAdmission`，不接受裸 `PlanSpec` candidate。流程固定为 transport identity 校验、权威 `RunTokenResolver`、E106 binder、持久化 runtime open，再进入既有 application service；legacy handoff、身份漂移、缺 token 和能力错误均在 coordinator 前 fail closed。该 seam 仍不注册 HTTP、chat、tool、Graph 或 Spring 入口。

### E108：报告模块进入通用响应

扩展 durable run-result projection，按报告 section 推导唯一或歧义的 block→goal 归属，并在响应边界调用 E105 selector。`AgentRunResult.Report` 增加可选 `campaign-report-modules/v1` 字段，旧 9 参数构造和无模块 JSON 保持兼容；模块只含 evidence refs、block identity、状态和限制，不携带 payload、owner、capability 或 retention 元数据。歧义归属仍 fail closed，不把文本或模块投影升级为完成事实。

### E109：完整结果入口纳入报告模块

扩展 E105/E108 的模块投影输入，接收已通过报告草稿校验的 ReportDraft.ResultEntry。每个完整结果入口以稳定 RESULT_LINK block 输出，并绑定自身 artifact 引用；只有 artifact 引用存在且 goal 归属明确时才可作为目标的可交付结果，重复 entryId、未知 goal 或缺失引用均 fail closed。旧构造函数和无完整结果入口的响应保持兼容；该切片仍不接 HTTP、Graph、Spring 或客户端路由。

### E110：统计复合 child 执行边界

新增 `CampaignStatisticsCompositeCall`，为多对象、多期间统计建立稳定 child/request identity，并统一承载同步 READY、异步 WAITING 和失败结果。它复用既有 `CampaignCallExecution`、`CampaignChildExecution` 与固定统计任务执行器，先校验 call/child 身份再交给既有执行合同；该 seam 不改变公开工具协议，也不接生产 Graph、HTTP 或数据库。

### E111：因果报告证据门

新增 `CausalEvidenceGuard` 并接入 `GoalAssessor` 的 CAUSAL_EVIDENCE 终评。因果目标必须同时具备证据引用、明确的非相关性因果方法、完整 coverage 和限制说明；联合分布或相关性分析不能被标记为因果完成。该切片只收紧 typed 报告终评，不替代真实模型验证。

### E112：查询计划稳定身份与续接绑定

新增不可变 `CampaignStatisticsQueryPlan`，统一规范多对象、多期间组合、稳定 query key 和不受展示标签影响的 canonical `planId`。`compare_statistics` 将 planId 写入结果元数据与 continuation，并对带 planId 的任务引用执行同计划校验；旧 continuation 引用仍兼容，输入顺序继续决定执行顺序。该切片只稳定现有统计工具的续接身份，尚未把 compare/rank/dimension 接入持久化 CALL、Graph、HTTP 生产入口。

### E113：维度下钻接入复合 child 执行

将真实维度下钻的双期间子查询接入 E110 `CampaignStatisticsCompositeCall`。每个查询复用冻结 child、action、request 和 wire identity，统一处理 READY、WAITING、查询容量拒绝与提交未确定；仅当子查询的 artifact 身份都通过校验后才发布维度页，保留原有授权复核、分页产物和恢复语义。compare/rank 仍未接入持久化 CALL/Graph 生产入口。

### E114：比较结果纯计算边界

将对象/期间比较的差值、完整性、指标版本、期间重叠和零基期规则抽到 `CampaignStatisticsComparisonCalculator`。`compare_statistics` 保留授权取数、快照续接和结果封装，纯计算只消费已校验的整段窗口数据，为后续 durable CALL 或报告聚合复用同一规则提供稳定边界；公开工具协议不变。


## 定向验证

命令：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=ReplanCoordinatorTest,JdbcReplanReceiptStoreTest,JdbcReportLifecycleStoreTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true test
```

E50/E51 命令结果：8 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。

E53 命令：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=CampaignReportPublisherTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true test
```

结果：2 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。

E52 命令：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=JdbcReplanRevisionApplierTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true test
```

结果：2 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖回调失败全量回滚、固定执行顺序和重复键冲突不产生部分发布。

E54 命令：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=CampaignReplanApplicationServiceTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true test
```

结果：3 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。

E55 命令：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=NativeGraphPrecompilerTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true test
```

结果：2 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。

E56 命令：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=JdbcCampaignRevisionApplierTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true test
```

结果：4 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。

覆盖断言包括：预编译失败不调用 revision applier；accepted candidate 只发布一次且旧 revision 收据可幂等重放；收据正文冲突拒绝、owner 隔离；报告按 revision 幂等、manifest 可回读、HISTORY/EXPORT 过期区别、引用释放和留存期后的清理 CAS。

E57/E58/E59 合并定向命令：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=JdbcCampaignRevisionApplierTest,CampaignReplanRuntimeFactoryTest,CampaignReportApplicationServiceTest,NativeGraphPrecompilerTest,CampaignReplanApplicationServiceTest,ReplanCoordinatorTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true test
```

结果：19 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖可信运行时 owner/token/frozen 输入校验、并发相同请求精确回放、typed durable 报告授权与历史/导出模式传递，并回归本批次此前的预编译、协调和原子发布边界。

E60/E61/E62 增量验证仍使用同一条定向命令；在补充授权操作断言及两个并发/状态测试后结果为：21 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。新增断言覆盖授权操作类型、CANCELLED/SUPERSEDED 基准拒绝和不同候选并发冲突，未改变生产接线。

E63 定向命令：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=CampaignTrustedAdapterConfigurationTest,JdbcCampaignRevisionApplierTest,CampaignReplanRuntimeFactoryTest,CampaignReportApplicationServiceTest,NativeGraphPrecompilerTest,CampaignReplanApplicationServiceTest,ReplanCoordinatorTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true test
```

结果：24 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖默认 profile 无 trusted bean、激活 profile 缺少敏感 provider 时 fail-fast、显式 H2 provider 的 factory/report 组合与共享数据源事务；并回归 E60–E62 及此前重规划/报告边界。

E64 增量验证：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=CampaignReplanTrustedAdapterTest,CampaignReplanRuntimeFactoryTest,CampaignTrustedAdapterConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true test
```

结果：13 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖 resolver 空结果/绑定错误、过期与取消 token、授权拒绝零 receipt/revision 写入，以及 profile 缺 resolver 时 fail-fast、显式 resolver 时创建 adapter 的组合回归。

E65 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=CampaignReportReadProjectionTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true test
```

结果：5 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖 HISTORY_VIEW/EXPORT 透传、稳定 snapshot schema、legacy payload 兼容、未知字段/身份错配/坏 goal fail closed、空 durable read 不回退和不可变输出。

E66 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=CampaignLegacyAnswerAdapterTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true test
```

结果：5 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖 complete 与 partial/unknown 状态、无报告时 EMPTY/UNAVAILABLE 区分、完整 typed 内容和 blocks 保留且不可变，以及状态矛盾和不可信 limitation 的 fail-closed 校验。

E67 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=CampaignAgentRunResultAdapterTest,AgentChatControllerTest,DefaultAgentRunHarnessTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true test
```

结果：12 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖旧 9 参数构造和旧 JSON（report 缺省时不输出）、trusted view answer 覆盖旧自由文本、complete/partial/waiting/failed 映射、列表不可变、状态矩阵矛盾拒绝，以及 controller/harness 既有响应回归。

E68 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=CampaignRunResultProjectionTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true -Dmaven.compiler.useIncrementalCompilation=false test
```

结果：5 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖完整结果脱敏、等待／运行中部分结果、run/plan/revision 与 reportRef/draft/goal 身份漂移、CANCELLED/SUPERSEDED 过期报告、成功缺报告或不完整、完整报告伪装等待、状态与 work state 矛盾、动作合同及不可变集合。测试只使用 H2 无关的纯 DTO/投影，未启动 Docker、应用、真实数据库或模型。

E69 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=CampaignRunReportReadProjectionTest,CampaignRunResultProjectionTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true -Dmaven.compiler.useIncrementalCompilation=false test
```

结果：11 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖固定 report key 的 HISTORY/EXPORT 读取、完整与部分报告、无 reportRef 时不扫描历史、请求报告缺失、planning gap 与失败动作映射、模式/引用/运行身份错配、脱敏输出和不可变集合。读取使用受控 fake reader，不启动 Docker、应用、真实数据库或模型。

E70 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=JdbcCampaignRunResultStoreTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true -Dmaven.compiler.useIncrementalCompilation=false test
```

结果：5 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖首次绑定与精确重放、不同事实冲突、报告授权、旧 token fencing、主体隔离、活动／取消状态矩阵、等待态部分报告、精确 revision 读取，以及两个 JDBC writer 并发相同绑定时收敛为一行。测试仅使用 H2 和本地 Spring JDBC 事务，没有启动 Docker、应用、真实 MySQL 或模型。

E71 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=CampaignDurableRunResultReadServiceTest,CampaignRunReportBindingVerifierTest,CampaignRunReportReadProjectionTest,CampaignRunResultProjectionTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true -Dmaven.compiler.useIncrementalCompilation=false test
```

结果：17 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖精确 revision 无 latest fallback、无报告引用时无需报告凭证、固定 reportRef 的部分报告读取、缺凭证拒绝、durable 状态与当前进度冲突、脱敏不可变输出，以及 report lifecycle 的 owner/capability、READY、run/revision 绑定和 retain/release 透传。测试使用受控 reader/fake lifecycle，不启动 Docker、应用、真实 MySQL 或模型。

E72 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=JdbcCampaignRunResultBindingCoordinatorTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true -Dmaven.compiler.useIncrementalCompilation=false test
```

结果：4 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖同事务 retain+binding+release、过期 token 绑定回滚引用、过期 token 不能释放引用、release 必须匹配 durable reportRef。测试使用 H2 和本地 Spring JDBC 事务，没有启动 Docker、应用、真实 MySQL 或模型。

E73 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=CampaignTrustedRunResultAdapterTest,CampaignRunReportBindingVerifierTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true -Dmaven.compiler.useIncrementalCompilation=false test
```

结果：6 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖每次请求独立 owner/capability、caller 与 token 主体绑定、错误凭证不读写引用、过期 token 回滚 retain，以及正确凭证 release。测试使用 H2 和本地 Spring JDBC 事务，没有启动 Docker、应用、真实 MySQL 或模型。

E74 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=CampaignTerminalRunResultCleanupTest,JdbcReportLifecycleStoreTest,JdbcCampaignRunResultBindingCoordinatorTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true -Dmaven.compiler.useIncrementalCompilation=false test
```

结果：14 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖取消后释放已过期报告引用、lifecycle 主行已不存在、重复清理幂等、活动 run 拒绝、主体和 reportRef 隔离、superseded revision 无报告清理，以及 report retain/release/cleanup 的同一行锁语义。测试仅使用 H2 和本地 Spring JDBC 事务，没有启动 Docker、应用、真实 MySQL 或模型。

E75 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=CampaignRunReportPublicationCoordinatorTest,JdbcCampaignRunResultBindingCoordinatorTest,JdbcCampaignRunResultStoreTest,CampaignTrustedRunResultAdapterTest,CampaignTrustedAdapterConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true -Dmaven.compiler.useIncrementalCompilation=false test
```

结果：24 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。随后按 E70–E75 受影响组件执行联合定向回归，结果为 51 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖发布与绑定同事务提交、过期 token 全量回滚、授权拒绝、reportRef/运行身份错配、精确重放不重复 retain、撤权后重放拒绝、绑定冲突回滚、完整/部分报告状态矩阵、脱敏返回值、统一锁序，以及 trusted profile 的显式 bean 装配。测试仅使用 H2、本地 Spring JDBC 事务和 Spring context，没有启动 Docker、应用、真实 MySQL、模型或浏览器。

E76 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=CampaignDurableRunResponseAdapterTest,CampaignAgentRunResultAdapterTest,CampaignLegacyAnswerAdapterTest,CampaignRunResultProjectionTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true -Dmaven.compiler.useIncrementalCompilation=false test
```

结果：20 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖无 binding 空结果、完整／等待／运行中／失败／未知状态映射、成功缺报告、失败或未知部分证据、取消／superseded 过期报告拒绝、run identity、目标/block 唯一性、rollup 一致性、旧响应操作字段保留、列表不可变和 owner/capability/payload/retention 脱敏。测试只使用纯 typed projection 和现有 Maven 后端测试，不启动 Docker、应用、真实 MySQL、模型或浏览器。

E77 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=CampaignDurableRunResponseServiceTest,CampaignDurableRunResponseAdapterTest,CampaignAgentRunResultAdapterTest,CampaignLegacyAnswerAdapterTest,CampaignRunResultProjectionTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true -Dmaven.compiler.useIncrementalCompilation=false test
```

结果：24 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖精确 revision 读取一次、空 binding、不回退、身份漂移拒绝、报告读取异常透传，以及 WAITING 部分响应、SUCCEEDED 完整响应、操作字段保留和敏感字段脱敏。测试只使用纯 typed reader、脚本化进度／报告投影和现有 Maven 后端测试，不启动 Docker、应用、真实 MySQL、模型或浏览器。

E78 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=CampaignDurableRunResponseDecoratorTest,CampaignDurableRunResponseRuntimeFactoryTest,CampaignDurableRunResponseServiceTest,CampaignDurableRunResponseAdapterTest,CampaignAgentRunResultAdapterTest,CampaignLegacyAnswerAdapterTest,CampaignRunResultProjectionTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true -Dmaven.compiler.useIncrementalCompilation=false test
```

结果：32 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖 factory 依赖与无跨请求状态、精确读取组合、`BOUND_RESPONSE`/`NO_BINDING` 显式结果、run/plan/revision 身份漂移、报告凭证缺失、等待/完成响应和敏感字段脱敏。测试只使用纯 typed reader、脚本化进度／报告投影和现有 Maven 后端测试，不启动 Docker、应用、真实 MySQL、模型或浏览器。

E79 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=CampaignResponseCapabilityGateTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true -Dmaven.compiler.useIncrementalCompilation=false test
```

结果：5 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖新 Run 的开关/能力矩阵、v2 Run 的旧客户端升级阻断、legacy Run 不自动升级、未知能力与大小写、不可变集合以及非法协议输入。测试只使用纯 Java typed gate，不启动 Docker、应用、真实 MySQL、模型或浏览器。

E80 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=CampaignTrustedAdapterConfigurationTest,CampaignReplanTrustedAdapterTest,CampaignDurableRunResponseRuntimeFactoryTest,CampaignDurableRunResponseDecoratorTest,CampaignResponseCapabilityGateTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true -Dmaven.compiler.useIncrementalCompilation=false test
```

结果：23 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖默认 profile 不创建 durable response bean、缺少具名 durable reader 时 fail fast、完整 provider 下 factory/gate 创建成功、decorator prototype 请求隔离、空 binding 的明确 `NO_BINDING`、重规划 profile 组合回归及 E78/E79 typed 合同。测试使用 H2、Spring context 和纯 Java fake reader，不启动 Docker、应用、真实 MySQL、模型或浏览器。

E81 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=CampaignReportReadFacadeTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true -Dmaven.compiler.useIncrementalCompilation=false test
```

结果：5 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖固定 reportId/revision 和 mode 透传、空读不回退、key/mode 漂移拒绝、route view 脱敏及 revision/key 必填校验。测试只使用纯 typed reader 和 Java 对象，不启动 Docker、应用、真实 MySQL、模型或浏览器。

E82 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=JdbcCampaignDurableRunReadCoordinatorTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true -Dmaven.compiler.useIncrementalCompilation=false test
```

结果：4 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖无 binding 时短路且不读取 progress/report、精确 progress facts 与推进围栏冲突、报告凭证与 identity-only 返回，以及共享 writable REQUIRED 事务组合校验。测试只使用 H2 和本地 Spring JDBC 事务，没有启动 Docker、应用、真实 MySQL、模型或浏览器。

随后将同一变更直接影响的 `JdbcCampaignProgressSnapshotTest`、`JdbcCampaignResultProgressReaderTest` 和 `JdbcCampaignRunResultStoreTest` 加入 selector，联合结果为 15 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`，确认公共 latest snapshot、receipt 读取和 binding writer 没有回归。

E83 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=CampaignJdbcDurableRunResponseBridgeTest,JdbcCampaignDurableRunReadCoordinatorTest,CampaignRunReportReadProjectionTest,CampaignReportReadProjectionTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true -Dmaven.compiler.useIncrementalCompilation=false test
```

结果：20 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖无 binding projector 短路、等待和成功响应、报告访问拒绝、binding 与派生状态冲突、报告 projector/response projector 均在 coordinator 事务内执行，以及 owner/capability/payload/checksum/retention 不进入响应 JSON。测试只使用 H2 和本地 Spring JDBC 事务，没有启动 Docker、应用、真实 MySQL、模型或浏览器。

随后重跑直接受影响的 `CampaignProgressServiceTest` 与 `CampaignResultReceptionProgressTest`，结果为 7 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`，确认新增的预加载 progress 投影入口没有改变既有授权、过期和接收进度语义。

E84 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am -Dtest=CampaignJdbcDurableRunResponseBridgeTest,CampaignJdbcDurableResponseConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true -Dmaven.compiler.useIncrementalCompilation=false test
```

结果：10 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖 E83 bridge 的无绑定／等待／成功／凭证拒绝和工厂请求级装配（7 项），以及 conjunctive profile 关闭、缺少具名 provider fail-fast、完整显式 provider 装配和每个 context 独立 H2 数据库（3 项）。生产 factory 还拒绝 progress service 的 step/run/artifact 读取未绑定到同一 JDBC transaction 的组合。测试没有启动 Docker、应用、真实 MySQL、模型或浏览器。

E85 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=CampaignResponseRouteAdapterTest,CampaignJdbcDurableResponseConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：10 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖 legacy path 不调用 durable reader、已有 v2 run 缺客户端能力时不回退、v2 缺 server-owned request 直接拒绝、`NO_BINDING` 不携带 Graph response、绑定响应单次返回、读取异常不重试以及 profile 关闭/缺 provider/完整 provider 装配。测试只使用纯 typed reader、H2 和本地 Spring context，没有启动 Docker、应用、真实 MySQL、模型或浏览器。

E86 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=CampaignResponseRouteAdapterTest,CampaignDurableResponseTransportAdapterTest,JdbcCampaignRunHandleResolverTest,CampaignJdbcDurableResponseConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：22 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖 exact revision 不回退 latest、session/plan/caller 身份校验、ACTIVE/CANCELLED/SUPERSEDED 状态事实、legacy/upgrade 不解析 handle、缺失或错配 handle fail closed、报告凭证必须随 run reference 提供，以及 profile 下 resolver/transport 装配。测试只使用 H2、本地 Spring context 和 typed fake reader，没有启动 Docker、应用、真实 MySQL、模型或浏览器。

E87 定向验证（与 E85/E86 受影响组件联合）：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=CampaignResponseEnvelopeAdapterTest,CampaignResponseRouteAdapterTest,CampaignDurableResponseTransportAdapterTest,JdbcCampaignRunHandleResolverTest,CampaignJdbcDurableResponseConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：27 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。新增 envelope 用例覆盖 legacy base 保留、客户端升级、无 binding、durable response、稳定 wire code、请求隔离和异常不回退；联合验证继续覆盖 E85/E86 的 gate、exact handle、profile 与 JDBC 边界。测试只使用 H2、本地 Spring context 和 typed fake reader，没有启动 Docker、应用、真实 MySQL、模型或浏览器。

E88 定向验证（与 E85/E86/E87 受影响组件联合）：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=CampaignResponseCapabilityGateTest,CampaignResponseRouteAdapterTest,CampaignDurableResponseTransportAdapterTest,CampaignResponseEnvelopeAdapterTest,JdbcCampaignRunHandleResolverTest,CampaignJdbcDurableResponseConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：36 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖权威 metadata 缺失、已有运行 identity 缺失、legacy/upgrade 在 handle resolver 前短路、metadata 与 exact handle 绑定、协议身份漂移拒绝，以及 E85/E86/E87 的 envelope、路由、JDBC handle 与 profile 边界。测试只使用 H2、本地 Spring context 和 typed fake reader，没有启动 Docker、应用、真实 MySQL、模型或浏览器。

E89 定向验证（与 E85/E86/E87/E88 受影响组件联合）：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=CampaignReportAccessGrantTest,CampaignReportAccessGrantTransportTest,CampaignResponseCapabilityGateTest,CampaignResponseRouteAdapterTest,CampaignDurableResponseTransportAdapterTest,CampaignResponseEnvelopeAdapterTest,JdbcCampaignRunHandleResolverTest,CampaignJdbcDurableResponseConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：42 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖 owner 映射与固定 capability、caller/handle/mode 授权、EXPORT 拒绝、SUPERSEDED 拒绝、缺 grant、grant 与 exact handle 错配、grant 转换为内部 E84 report access，以及 E85–E88 的 gate、envelope、JDBC handle 和 profile 边界。测试只使用 H2、本地 Spring context 和 typed fake reader，没有启动 Docker、应用、真实 MySQL、模型或浏览器。

E90 定向验证（与 E85/E86/E87/E88/E89 受影响组件联合）：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=CampaignReportAccessGrantTest,CampaignReportAccessGrantTransportTest,CampaignResponseCapabilityGateTest,CampaignResponseRouteAdapterTest,CampaignDurableResponseTransportAdapterTest,CampaignResponseEnvelopeAdapterTest,JdbcCampaignRunHandleResolverTest,CampaignJdbcDurableResponseConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：44 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。新增覆盖 authority protocol/grant provider 任一缺失时的 profile fail-fast，并验证 grant authority 成功路径在四参数 transport 中重新经可信 resolver 签发后才构造内部报告凭证；联合回归继续覆盖 E85–E89 的 gate、envelope、exact handle、opaque grant 和脱敏边界。测试只使用 H2、本地 Spring context 和 typed fake reader，没有启动 Docker、应用、真实 MySQL、模型或浏览器。

### E91：exact response handle 身份边界回归

补充 `JdbcCampaignRunHandleResolverTest` 的身份矩阵：subject 或 authVersion 与 ledger 不一致时拒绝 exact revision；空 caller、空 subject 在构造 reference 时 fail fast；已有 `SUPERSEDED` revision 仍可作为只读事实解析，但不携带任何写凭据。生产代码保持 E86 的 package-private exact read、无 latest fallback 和脱敏 handle 设计，本批只补齐此前审计发现的边界覆盖。

E91 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=JdbcCampaignRunHandleResolverTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：7 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。仅使用 H2/JDBC typed resolver，没有启动 Docker、应用、真实 MySQL、模型或浏览器。

E92 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=JdbcCampaignStatisticsReleaseStoreTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：5 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖稳定顺序、limit、重复读取零写入、主体/状态过滤和篡改 fail closed。仅使用 H2/JDBC typed store，没有启动 Docker、应用、真实 MySQL、模型或浏览器。

E93 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=JdbcCampaignSubmissionDeferralReaderTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：5 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖到期/未来候选、主体与授权版本隔离、活动/终态 revision、READY/job/callback/未知状态排除、稳定 limit、篡改 fail closed 和重复读取无写入。仅使用 H2/JDBC typed reader，没有启动 Docker、应用、真实 MySQL、模型或浏览器。

E94 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=CampaignJdbcDurableResponseConfigurationTest,CampaignResponseEnvelopeAdapterTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：10 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖联合 profile 下的显式 envelope 装配、profile 关闭、protocol/grant provider 缺失 fail-fast，以及 envelope 的 legacy、upgrade、no-binding、durable 互斥输出。仅使用 H2、Spring context 和 typed fake，没有启动 Docker、应用、真实 MySQL、模型或浏览器。

E95 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=JdbcCampaignReplanRunTokenResolverTest,CampaignTrustedAdapterConfigurationTest,CampaignReplanTrustedAdapterTest,CampaignReplanRuntimeFactoryTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：20 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖 owner/session/run 精确绑定、ACTIVE 与 terminal 状态、advance 后无缓存重读、旧 revision 排除、具名 resolver profile 装配和现有 runtime factory 二次 token 校验。仅使用 H2/JDBC、Spring context 和 typed fake，没有启动 Docker、应用、真实 MySQL、模型或浏览器。

E96 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=CampaignPlanRuntimeFactoryTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：4 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖 open 的 owner/session/token/status/frozen version 校验、显式 MemorySaver 编译、saver key 错配、重复注册和无共享 open 状态。仅使用 typed fake、MemorySaver 和现有 Graph 编译，不启动 Docker、应用、真实 MySQL、模型或浏览器。

E97 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=CampaignTrustedResponseAuthorityProviderTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：3 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖 caller/handle/metadata/mode 漂移、已有运行身份缺失和 opaque grant 组合；仅使用 typed fake，没有启动 Docker、应用、真实 MySQL、模型或浏览器。

E98 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=GoalAssessorTest,CampaignReportPublisherTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：7 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。仅使用纯 Java report assessor/publisher 和既有 typed fixtures，没有启动 Docker、应用、真实 MySQL、模型或浏览器。

E99 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=CampaignReplanCandidateGateTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：8 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。仅使用 fake resolver/store，未写入 receipt/revision，也没有启动 Docker、应用、真实 MySQL、模型或浏览器。

E100 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=JdbcCampaignStatisticsHistoricalReleaseRecoveryReaderTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：4 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。仅使用 H2/JDBC 只读 fixture，未注册 Spring、scheduler 或 recovery coordinator，也没有启动 Docker、应用、真实 MySQL、模型或浏览器。

E101 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=JdbcCampaignStatisticsHistoricalReleaseRecoveryGateTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：3 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。仅使用 H2/JDBC 事实锁定与只读 permit，未执行 release 写入、远端调用、Docker、应用、真实 MySQL、模型或浏览器。

E102 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=CampaignReplanPlanningHandoffTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：5 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。仅使用纯 Java typed fixtures，未创建 RunToken、receipt/revision、Graph、tool、网络或 Docker 运行。

E103 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=CampaignStatisticsHistoricalReleaseRecoveryActionTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：3 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。仅使用纯 Java trusted fixtures，未启动 Docker、应用、真实 MySQL、模型、coordinator、Graph、HTTP 或 Spring。

E104 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=CampaignReplanCandidateAdmissionTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：8 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。仅使用纯 Java typed fixtures，未启动 Docker、应用、真实 MySQL、模型、coordinator、Graph、HTTP 或 Spring。

E102–E104 合计定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=CampaignReplanPlanningHandoffTest,CampaignStatisticsHistoricalReleaseRecoveryActionTest,CampaignReplanCandidateAdmissionTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：16 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。该合计只覆盖三组纯 Java 定向合同；未启动 Docker、应用、真实 MySQL、模型、coordinator、Graph、HTTP 或 Spring。

E105 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=CampaignReportModuleSelectorTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：5 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖稳定 goal 排序、证据/可渲染 block 的完成门、等待/缺证据降级、跨目标或未知 block 拒绝及不可变输出；未启动 Docker、应用、真实 MySQL、模型、Graph 或 HTTP。

E106 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=CampaignReplanAdmissionBinderTest,CampaignReplanCandidateAdmissionTest,CampaignReplanPlanningHandoffTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：16 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖 exact token/owner/session/run/plan/revision/capability/hash 绑定、legacy handoff 和错误 token fail closed；未启动 Docker、应用、真实 MySQL、模型、coordinator、Graph、HTTP 或 Spring。

E107 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=CampaignReplanAdmissionExecutionTest,CampaignReplanAdmissionBinderTest,CampaignReplanCandidateAdmissionTest,CampaignReplanPlanningHandoffTest,CampaignReplanApplicationServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：22 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖 handoff/admission 到 typed application request 的执行顺序、身份漂移/legacy/缺 token 的零 runtime 调用，以及既有 application 的 accepted/idempotent 合同；未启动 Docker、应用、真实 MySQL、模型、coordinator、Graph、HTTP 或 Spring。

E108 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=CampaignDurableRunResponseAdapterTest,CampaignRunResultProjectionTest,CampaignAgentRunResultAdapterTest,CampaignDurableRunResponseServiceTest,CampaignDurableRunResponseRuntimeFactoryTest,CampaignJdbcDurableRunResponseBridgeTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：31 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖 section 归属到 `campaign-report-modules/v1` 的脱敏投影、旧响应构造兼容、durable response service/runtime/bridge 回归；未启动 Docker、应用、真实 MySQL、模型或浏览器。

E109 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=CampaignReportModuleSelectorTest,CampaignDurableRunResponseAdapterTest,CampaignRunResultProjectionTest,CampaignAgentRunResultAdapterTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：22 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖完整结果入口到 `RESULT_LINK` 的脱敏投影、旧构造兼容和无入口回归；未启动 Docker、应用、真实 MySQL、模型、Graph、HTTP 或 Spring。

E110 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=CampaignStatisticsCompositeCallTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：4 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。另行复用 `CampaignCallExecutionTest` 与 `StatisticsJobFixedExecutorTest` 共 11 tests 通过；未启动 Docker、应用、前端、真实 MySQL 或模型。

E111 定向验证：

```text
mvn.cmd -o -pl services/agent-service -am "-Dtest=GoalAssessorTest,CampaignReportModuleSelectorTest,CampaignReportPublisherTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：15 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖联合分布/相关性不能满足因果目标、方法/覆盖/限制门和既有报告投影回归；未启动 Docker、应用、前端、真实 MySQL 或模型。

E112 定向验证：

```text
mvn.cmd -o -q -pl services/agent-service -am "-Dtest=CampaignStatisticsToolsTest,CampaignStatisticsQueryPlanTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：`CampaignStatisticsQueryPlanTest` 4 tests、`CampaignStatisticsToolsTest` 34 tests，合计 38 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖稳定 planId、对象/期间组合、旧 query key 兼容和带计划续接校验；未启动 Docker、应用、前端、真实 MySQL 或模型。

E113 定向验证：

```text
mvn.cmd -o -q -pl services/agent-service -am "-Dtest=CampaignStatisticsCompositeCallTest,DimensionChangeSkillTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：`CampaignStatisticsCompositeCallTest` 4 tests、`DimensionChangeSkillTest` 2 tests 通过；同一 reactor 复用 `CampaignCallExecutionTest` 1、`CampaignPlanRuntimeFactoryTest` 4、`StatisticsJobFixedExecutorTest` 6，均无 failures/errors；未启动 Docker、应用、前端、真实 MySQL 或模型。

E114 定向验证：

```text
mvn.cmd -o -q -pl services/agent-service -am "-Dtest=CampaignStatisticsToolsTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" "-Dmaven.compiler.useIncrementalCompilation=false" test
```

结果：`CampaignStatisticsToolsTest` 34 tests，0 failures，0 errors，0 skipped；`BUILD SUCCESS`。覆盖拆分后的比较计算、质量门、零基期和多对象/多期间差值规则；未启动 Docker、应用、前端、真实 MySQL 或模型。
## 未覆盖边界

- `RevisionApplier` 是可信装配接口，本批次没有把它接入生产 `JdbcCampaignRunStore.revise` 和 `JdbcCampaignStatisticsConsumerStore.adopt`，因此不能宣称运行中 replan 已开放。
- E52 只提供共享事务的原子应用适配器；实际 store 回调、候选 hash/scope/owner 校验和生产 Spring 装配仍待完成，不能把 H2 回调测试当作真实重规划入口。
- E54/E55 仍是显式 typed 组合件，尚无 token resolver、业务 profile、Spring bean 或受配置保护的 HTTP 入口；E55 的 `MemorySaver` 只用于编译前置，不是生产 checkpoint。
- E56–E58 已接真实 H2 JDBC ledger、跨 revision consumer 接管和并发相同请求的精确 replay；仍没有真实 MySQL 方言、分布式 fencing、远端 cancel 或生产入口证据。
- E57 的运行时工厂与 E59 的报告应用服务仍是显式 typed 组合件；尚无 token resolver、业务 profile、Spring bean、HTTP/chat 接线或客户端历史/导出验收。
- E63 只提供受 profile 保护的组合契约；默认生产 profile 不启用，当前仍没有可信 owner/capability resolver、PlanValidator/Catalog、EvidenceReader 或真实授权 provider，因此不能宣称业务入口已开放。context 测试使用 H2 迁移替身，不替代真实 MySQL 或跨服务验收。
- E64 只提供不接 transport 的 typed adapter；resolver、owner/capability 解析仍由未来可信入口提供，尚无 HTTP/chat/tool 接线、远端取消或真实 MySQL/多实例 fencing 验收。
- E65 只提供后端 typed read projection；E66 增加纯 legacy answer 兼容桥；E67 只扩展 response 类型和纯 adapter；E68 增加纯 run-result projection；E69 增加授权的 run/report 读取组合；E70 增加受 token fencing 保护的 durable run-result 绑定；E71 增加按 revision 的授权读取组合和 report verifier；E72 增加共享事务的 retain/bind/release 协调器；E73 增加按请求解析身份的 adapter；E74 增加终态引用清理和 report 行锁协议；E75 增加报告发布与 run-result 绑定的共享事务及精确重放保护；E76 增加 durable projection 到兼容响应的纯 typed 组合和状态/脱敏校验；E77 增加一次精确 durable read 到兼容响应的 typed 服务边界；E78 增加无状态 runtime factory、三元身份核验和显式无绑定 outcome；E79 增加新响应协议的客户端能力门控；E80 增加受 `campaign-trusted-adapter` profile 保护的具名 provider 装配、prototype decorator 和能力 gate；E81 增加固定 report key 的 route-level 脱敏 facade；E82 增加同一 writable REQUIRED 事务内的 JDBC durable read 快照协调和 row-version 围栏；E83 增加事务内 typed projector 与 JDBC durable response bridge，禁止快照结束后的 E71 二次读取；E84 增加 conjunctive profile 下的 JDBC durable response factory、请求级报告凭证绑定及 step/run/artifact 同事务组合守卫；E85 增加先能力门控、后 durable factory 的 transport-neutral response route，区分 legacy、客户端升级、无绑定和绑定响应，禁止 durable 失败回退；E86 增加 exact revision 的 server-owned 脱敏 response handle resolver，并让 transport adapter 在 durable 路径上先解析 handle、禁止 latest/RunToken 误绑定和 fallback；E87 增加稳定的 transport-neutral response envelope，保留 legacy base 与 durable response 的互斥语义并固定 upgrade/no-binding wire code；E88 增加由 trusted metadata resolver 提供的协议/运行身份 authority seam，禁止调用方伪造已有运行的协议字段并在 exact handle 前 fail closed；E89 增加 principal-bound report access grant，固定 capability、绑定 exact handle 与 mode，并禁止 authority transport 接收 raw report credential；E90 将 metadata/grant resolver 作为受保护 Spring 组合的显式必需 provider，缺失即 fail fast，且 grant authority 必须重新由可信 resolver 签发后才可构造内部报告凭证；E91 补齐 exact handle 的 subject/authVersion/null caller 边界回归；E92 增加有界的 REQUESTED 释放意图恢复读取；E93 增加容量退避到期候选的有界发现；E94 补齐受保护 response envelope 的 Spring typed 组合；E95 增加 owner/session 绑定的 JDBC replan token resolver；E96 增加版本化 Plan runtime registry、显式 saver binding 与延迟 compile 工厂；E97 增加可信 response authority 的请求级身份组合；E98 增加 GoalAssessor 的正式交付状态矩阵回归；E99 增加 owner/session/run/step 绑定的只读重规划候选门禁；E100 增加历史 SUPERSEDED producer 释放候选的 owner-scoped JDBC 读取和严格身份链校验；E101 增加历史 producer release 的二次事实锁定 permit gate；E102 增加 PendingReplan 与 server baseline 绑定的 typed planner handoff；E103 增加历史恢复动作分类；E104 增加重规划候选准入与身份快照；E105 增加稳定的投放报告模块投影；E106 增加准入候选到既有执行请求的可信 typed 绑定；E107 增加 admitted candidate 到可信重规划 runtime 的窄执行边界；E108 将模块投影作为可选脱敏响应字段。上述组件尚未由 Graph/AgentRunHarness/HTTP 生产路径调用，客户端历史/导出入口、生产 principal provider、scheduler/recovery coordinator、trusted planner/执行接线和真实 MySQL payload 演进仍待验收。
- `JdbcReportLifecycleStore` 已通过 E75 的 trusted profile 由报告发布与 run-result 组合器显式复用，但尚未由 Graph/AgentRunHarness 或 Admin/Agent HTTP/chat 路由自动调用；真实 MySQL 方言、跨服务 HTTP 和客户端历史/导出仍待验。
- E53 只提供显式 durable publisher API，尚未注册到现有自然语言 chat 或 HTTP 路由；调用方仍需在可信运行装配中提供生命周期 store。
- E103–E114 仍是 transport-neutral typed 组件：E103 分类历史恢复动作，E104 形成重规划 admission，E105 投影报告模块，E106 绑定执行请求，E107 只允许 admitted candidate 进入可信 runtime，E108 把模块投影带入通用响应，E109 将完整结果入口投影为 RESULT_LINK，E110 提供统计复合 child 边界，E111 增加因果报告证据门，E112 稳定统计查询计划与 continuation 身份，E113 将维度下钻双期间 child 接入复合边界，E114 抽出比较纯计算边界；均不接生产 Graph、HTTP、Spring 或真实 MySQL，不宣称新入口已开放。
- 本批次没有改变旧 Graph、模型循环、Docker 资源或前端行为。
