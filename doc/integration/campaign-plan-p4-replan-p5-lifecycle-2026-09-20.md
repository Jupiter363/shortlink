# P4/P5：重规划编排、原子发布与报告生命周期持久化（E50/E51/E52/E53/E54/E55/E56/E57/E58/E59/E60/E61/E62/E63/E64/E65/E66/E67/E68/E69/E70/E71/E72/E73/E74/E75/E76/E77）

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

## 未覆盖边界

- `RevisionApplier` 是可信装配接口，本批次没有把它接入生产 `JdbcCampaignRunStore.revise` 和 `JdbcCampaignStatisticsConsumerStore.adopt`，因此不能宣称运行中 replan 已开放。
- E52 只提供共享事务的原子应用适配器；实际 store 回调、候选 hash/scope/owner 校验和生产 Spring 装配仍待完成，不能把 H2 回调测试当作真实重规划入口。
- E54/E55 仍是显式 typed 组合件，尚无 token resolver、业务 profile、Spring bean 或受配置保护的 HTTP 入口；E55 的 `MemorySaver` 只用于编译前置，不是生产 checkpoint。
- E56–E58 已接真实 H2 JDBC ledger、跨 revision consumer 接管和并发相同请求的精确 replay；仍没有真实 MySQL 方言、分布式 fencing、远端 cancel 或生产入口证据。
- E57 的运行时工厂与 E59 的报告应用服务仍是显式 typed 组合件；尚无 token resolver、业务 profile、Spring bean、HTTP/chat 接线或客户端历史/导出验收。
- E63 只提供受 profile 保护的组合契约；默认生产 profile 不启用，当前仍没有可信 owner/capability resolver、PlanValidator/Catalog、EvidenceReader 或真实授权 provider，因此不能宣称业务入口已开放。context 测试使用 H2 迁移替身，不替代真实 MySQL 或跨服务验收。
- E64 只提供不接 transport 的 typed adapter；resolver、owner/capability 解析仍由未来可信入口提供，尚无 HTTP/chat/tool 接线、远端取消或真实 MySQL/多实例 fencing 验收。
- E65 只提供后端 typed read projection；E66 增加纯 legacy answer 兼容桥；E67 只扩展 response 类型和纯 adapter；E68 增加纯 run-result projection；E69 增加授权的 run/report 读取组合；E70 增加受 token fencing 保护的 durable run-result 绑定；E71 增加按 revision 的授权读取组合和 report verifier；E72 增加共享事务的 retain/bind/release 协调器；E73 增加按请求解析身份的 adapter；E74 增加终态引用清理和 report 行锁协议；E75 增加报告发布与 run-result 绑定的共享事务及精确重放保护；E76 增加 durable projection 到兼容响应的纯 typed 组合和状态/脱敏校验；E77 增加一次精确 durable read 到兼容响应的 typed 服务边界。上述组件尚未由 Graph/AgentRunHarness/HTTP 生产路径调用，客户端历史/导出入口和真实 MySQL payload 演进仍待验收。
- `JdbcReportLifecycleStore` 已通过 E75 的 trusted profile 由报告发布与 run-result 组合器显式复用，但尚未由 Graph/AgentRunHarness 或 Admin/Agent HTTP/chat 路由自动调用；真实 MySQL 方言、跨服务 HTTP 和客户端历史/导出仍待验。
- E53 只提供显式 durable publisher API，尚未注册到现有自然语言 chat 或 HTTP 路由；调用方仍需在可信运行装配中提供生命周期 store。
- 本批次没有改变旧 Graph、模型循环、Docker 资源或前端行为。
