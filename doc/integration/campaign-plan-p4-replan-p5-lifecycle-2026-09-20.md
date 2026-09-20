# P4/P5：重规划编排、原子发布与报告生命周期持久化（E50/E51/E52/E53/E54/E55/E56/E57/E58/E59）

记录日期：2026-09-20。此批次只做后端合同、JDBC 持久化和 H2 定向验证；没有启动 Docker、应用、真实 MySQL、真实模型或浏览器。

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

## 未覆盖边界

- `RevisionApplier` 是可信装配接口，本批次没有把它接入生产 `JdbcCampaignRunStore.revise` 和 `JdbcCampaignStatisticsConsumerStore.adopt`，因此不能宣称运行中 replan 已开放。
- E52 只提供共享事务的原子应用适配器；实际 store 回调、候选 hash/scope/owner 校验和生产 Spring 装配仍待完成，不能把 H2 回调测试当作真实重规划入口。
- E54/E55 仍是显式 typed 组合件，尚无 token resolver、业务 profile、Spring bean 或受配置保护的 HTTP 入口；E55 的 `MemorySaver` 只用于编译前置，不是生产 checkpoint。
- E56–E58 已接真实 H2 JDBC ledger、跨 revision consumer 接管和并发相同请求的精确 replay；仍没有真实 MySQL 方言、分布式 fencing、远端 cancel 或生产入口证据。
- E57 的运行时工厂与 E59 的报告应用服务仍是显式 typed 组合件；尚无 token resolver、业务 profile、Spring bean、HTTP/chat 接线或客户端历史/导出验收。
- `JdbcReportLifecycleStore` 尚未由 `CampaignReportPublisher` 或 Admin/Agent 路由自动调用；真实 MySQL 方言、跨服务 HTTP 和客户端历史/导出仍待验。
- E53 只提供显式 durable publisher API，尚未注册到现有自然语言 chat 或 HTTP 路由；调用方仍需在可信运行装配中提供生命周期 store。
- 本批次没有改变旧 Graph、模型循环、Docker 资源或前端行为。
