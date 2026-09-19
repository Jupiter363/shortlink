# 投放分析 Agent 全计划完成矩阵

记录日期：2026-09-20。实现基线：主线程提供的 main `0d9e788`，PR #71–#73 已合并。本次只读核对计划、源码、测试源码及既有验证记录；**没有重新运行测试、启动应用、Docker、浏览器或真实模型，没有执行 Git 操作**。

后续实现更新：E11 记录冻结范围首批实现及 40 项定向后端测试；相关行已更新为部分已验。上述“只读”指完成矩阵初始审计，不包含后续实现批次。

目标保持 [Issue #62](https://github.com/Jupiter363/shortlink/issues/62) 的全部 P0–P5，以及总计划中的客户端与完整图文输出范围。主线程已实时核验 Issue 正文与本地已知正文快照一致。本清单不纳入其他项目或旧 Issue #27 的任务，不以某个分批 PR 或若干组件测试通过代替整个目标完成。

**基线结论：P0 原生机制组件门槛已有验收；P1 多个耐久运行、恢复、分页接收及固定统计执行组件已有后端验收，但完整接线和用户入口未交付；P2–P5 与客户端正式交付仍待完成。新入口应继续关闭。** 本文的“待完成”是任务，不是删除或降低原验收条件。

## 1. 阅读规则与证据范围

表中来源缩写：I＝[实施计划][I]，C＝[合同附件][C]，G＝[Graph 适配][G]，R＝[风险处理协议][R]，R1/R2＝[第一轮][R1]／[第二轮风险审查][R2]。V01–V35 按 I §6 原顺序编号；G01–G19 按 G §9 原顺序编号，均不可用一个汇总“通过”替代。

| 当前状态 | 本文含义 |
| --- | --- |
| 组件已验 | 有对应既有后端运行报告，限定于报告中的实现、替身和故障窗口；不表示生产入口或整个风险关闭。 |
| 部分已验 | 若干子合同已有实现和证据，表列剩余项仍须实现／集成验收。 |
| 待实现／验收 | 只有计划、接口、示例或相关基础能力，没有找到该完整要求的验收证据。 |
| 保持边界 | 当前必须持续满足的限制；后续变更需按受影响范围验证，不能一次通过永久豁免。 |
| 环境待验 | 真实 MySQL、真实统计链路、线上模型质量或客户端／浏览器没有本次允许范围内的实测证明。 |

风险另保留 `designStatus / implementationStatus / verificationStatus` 三个维度：R01–R19 的设计均为 SPECIFIED；实现和运行证据按第 6 节逐项记录。历史设计附件中的 NOT_STARTED 是当时快照，不能否认后续实现；后续单组件通过也不能把整项风险标为 CLOSED。

### 1.1 已有后端验证索引

以下数字是各报告自己的验收范围，**不同批次包含受影响重跑，不能相加成为总测试量，也不是本次新跑结果**。历史 P0 首报中的未完成项，应结合 E01 补充验收判断。

| 证据 | 已记录结果与代表性代码／测试 | 边界 |
| --- | --- | --- |
| [E00：P0 首批][E00] | 原生 ReactAgent、投影、Plan 校验、容量和 HTTP 边界；全 Agent 记录 795 项、794 通过、1 既有跳过。`PlanValidatorTest`、`NativeExplorationAdapterTest`、`NativeExplorationCheckpointTest`、`ProcessCapacityExecutorTest`。 | 该首报不单独证明整个 P0；内存账本、脚本模型，不是耐久探索。 |
| [E01：P0 补充][E01] | 61 项定向验收：模型传输 6、版本化 Skill 14、规范恢复 9、既有原生机制 32。`NativeExplorationResumeTest`、`RunPinnedSkillsTest`、`DeepSeekTransportBoundaryTest`。 | P0 组件门槛完成；真实模型、耐久 Action 映射与生产挂载未由此完成。 |
| [E02：独立恢复协议][E02] | Agent 19、Admin 29、Analytics 38，共 86 项；旧端点／gate 与清理竞争恢复零 INSERT，原首次 submit 正常。 | 控制器／客户端替身、H2；当前分组重新解析仍不是 FROZEN_SET。 |
| [E03：Run／Child／Artifact 账本][E03] | 24 项：Run 8、Artifact 4、原生 PlanGraph 6、提交恢复 6；冻结身份、原子 READY、CAS、原生扫描。 | 未注册生产入口；旧报告缺失的 Step／进程恢复已由后续批次补一部分。 |
| [E04：Step 与绑定][E04] | 23 项：Step 7、绑定 7、Artifact 4、Driver 5；具名输出、授权、schema、真实逐次 I/O 门控、独立步骤推进。 | FIXED 适配；不代表持久化 REACT、业务全部 Tool／Skill 已接入。 |
| [E05：授权进度][E05] | 7 项：一致快照 3、进度服务 4；执行与交付分离、撤权隐藏单项、独立结果保留。 | 内部视图与服务；没有用户 API／客户端验收。 |
| [E06：进程死亡与恢复][E06] | 18 项：进程证明 3、RecoveryStore 5、提交恢复 6、真实子 JVM＋H2 文件恢复 1、协调器 3。 | 真实子 JVM 不等于应用服务／MySQL；同一可信 OS 进程域，非分布式租约。 |
| [E07：任务结果读取][E07] | 15 项：Agent 5、Admin 4、受影响既有 Admin 6；status/page 独立合同、可信身份、机器错误码。 | loopback HTTP fixture；未启动跨服务统计应用。 |
| [E08：分页耐久接收][E08] | 18 项：Store 5、Protocol 4、Receiver 5、原生 Graph 1、协调器 3；501 边界、5501 行跨 10 页、原子发布及故障恢复。 | 完整收集一个 CURRENT_QUERY；不是固定候选全集、报告留存或遥测 COMPLETE。 |
| [E09：结果接收进度][E09] | 10 项：Reader 3、接收进度 3、既有进度 4；同事务轻量快照、逐结果页／行数、零行、权限。 | 不把已登记 child 当全部业务工作量；前端进度条未实现验收。 |
| [E10：固定统计执行器][E10] | 9 项：FrozenQuery 3、FixedExecutor 3、首次提交 HTTP 合同 3；四类 query、冻结首次请求、丢 ACK 只恢复原身份、READY 零重取。 | `statistics_query_job@1` 固定异步适配；CURRENT_GROUP／CURRENT_QUERY，未交付同步复合 Tool、P2 或生产接线。 |
| [E11：冻结成员与范围证明][E11] | 40 项定向测试最终通过；指定成员授权、501 成员分片、两期同 scope、专用查询／恢复、proof 拒错及分页发布。 | 纯冻结器与跨层合同；耐久枚举、父集合覆盖对账、生产装配和结果释放仍待完成。 |
| [E12：远端结果释放与配额分账][E12] | 42 项定向测试首次通过；第9任务、原身份/TTL、恢复零INSERT、页/epoch/清理竞争、容量白名单及跨服务状态。 | 远端协议已验；本地消费者与releaseIntent、自动释放及待提交子项退避尚未接入。 |
| [E13：本地结果证明与释放恢复][E13] | 12 项定向测试首次通过；全页证明、单 producer 绑定、REQUESTED/CONFIRMED、READY 独立释放 attempt、丢 ACK 和取消晚到事实、原生 Graph 下游本地读取。 | 可选单生产者协调已验；持久容量退避、第9子项自动续接、多消费者 adopt/GC 与生产装配仍待完成。 |
| [E14：可信退避与九任务续接][E14] | 7 项定向测试首次通过；未受理证明幂等／跨重开退避、混合 pending/READY、中断恢复、严格错误分类；九任务 submit10/job9/page9/release9/recover0，前8项原证据不变。 | 固定查询组件链已验；远端为替身，生产装配、多消费者采用及真实MySQL未由此验收。 |
| [E15：权威成员分页合同][E15] | 7 项定向测试通过；闭字段/整数/游标/主体、501与空组、跨层错误码；真实Command业务创建的成员与revision提交/回滚一致。 | 可复用权威页边界；不是耐久collector或历史快照，真实MySQL RR并发窗口待验。 |
| [E16：耐久范围收集与分片][E16] | 6 项定向测试通过；原页续接、未知首屏停止、固定版本后续页重读、变版本整代失效、终页原子发布、501两期同片及空组。 | 完整成员凭证；实际统计结果的父集合覆盖、生产授权装配与MySQL并发仍另验。 |

源码核对入口：[`planning`][CODE-PLAN]、[`runtime`][CODE-RUNTIME]、[`skills`][CODE-SKILLS]及[对应测试][TEST-CAMPAIGN]。`ExplorationLedger` 的 Javadoc 明确为 P0 可信边界、无 Spring 实现注册；[`PersistentPlanDriver`][CODE-DRIVER]、[`StatisticsJobFixedExecutor`][CODE-FIXED]与[`CampaignProgressService`][CODE-PROGRESS]目前是可组合组件。以上存在性只能辅助定位，不能替代报告的运行证据。

## 2. 全阶段交付矩阵

### 2.1 不得丢失的总边界与兼容门槛

| 要求 | 来源 | 证据 | 当前状态 | 剩余动作 |
| --- | --- | --- | --- | --- |
| B01：Conversation→Run→不可变 PlanSpec；session 不授权，新分析不因同文案永久复用快照；完成后的新请求新 Run，补输入／改原目标新 inputSet＋revision，纯继续保持原身份。 | C §1–2、§7；Issue #62 | E03/E04 有冻结运行定义和身份；旧图另有续问解析。 | 部分已验 | 接用户请求幂等／新分析／继续／澄清分类；同客户端幂等键不同正文拒绝，不能只替换页面标签。 |
| B02：外层管目标、依赖与完成，FIXED／REACT 与 TOOL／SKILL 两维；缺 Skill 继续评估合法 Tool 组合，不能按意图数选模式或伪造执行器。 | I §1；C §2.1、§3 | E00 静态 Plan／能力合同。 | 部分已验 | P4 实际规划与覆盖策略；P3 合法工具探索不依赖专属 Skill。 |
| B03：一 PlanStep 一 Node，基础设施节点可无 goalIds；首期拓扑串行但独立步骤可跨等待推进；不永久追加 session 图。 | C §1、§3；G §2–3 | E03/E04：原生扫描、稳定身份、40 步计划。 | 部分已验 | 将 REACT 薄适配接同一 Driver；运行中只新建版本，不修改已编译图。 |
| B04：原生 Graph／ReactAgent／Hooks／SkillRegistry／saver 优先；复用现有 ControlGraph，不造通用调度平台、第二模型循环、Skill DSL 或新微服务。 | I §1–3；C §6；R §6 | E00/E01 实际原生机制；asNode 状态泄漏实测后采用显式投影。 | 保持边界 | 后续只补业务适配；框架升级若必要单独提供模型／saver／风险 Agent 回归。 |
| B05：完整数据、报告、审计不放 Graph；状态仅引用／短状态，白名单投影和替换，不保存完整隐藏思维；未知字段、失效引用、版本不兼容不得静默变空。 | C §1、§5；G §5、§8 | E00/E03/E08 的小 checkpoint、分页独立载荷。 | 部分已验 | 覆盖 P3 行动与 P5 报告；受控 Artifact Reader 按用途投影、缓存有限且只缓存不可变模板。 |
| B06：模型不能覆盖主体、白名单、幂等、scope、期间、质量或完成条件；每次绑定和每个真实 I/O 都重新检查当前权限。 | I §4.1；C §3；R §1.5 | E04/E08/E10 当前 fixed 路径。 | 部分已验 | 所有 Tool／Skill／REACT 接统一可信入口；冻结集合授权见 P2。 |
| B07：Step 成功、HTTP/tool.success、resultComplete、quality、Goal ANSWERED 相互独立；缺失保留 UNKNOWN，零值必须有合同证据。 | C §4；I §5；R §5 | E04/E05/E08/E09 分离 Step／进度／页完成。 | 部分已验 | P5 权威逐目标判定；所有响应／旧 answer 使用同一真实状态。 |
| B08：等待不忙轮询；未提交容量阻断无 job，未决超时不当普通失败；高可配置容量、可恢复分批，不削减目标／维度／卡片或伪造完整。 | I §4.3；R §1.5–1.6、§4.4 | E00 容量组件；E03/E08 耐久引用。 | 部分已验 | 集成进程准入、远端容量分类、累计预算与退避；不靠提高常量替代协议。 |
| B09：新路径启用前选定，提交后失败不能自动切旧路径重做；旧客户端新 Run 留旧路径，新 Run 的旧客户端续接明确升级要求。 | I §2、§7；R §5.2 | 验证报告均说明新入口未注册。 | 待实现／验收 | 配置开关＋`campaign-response/v2` 能力检查；能力声明不代替授权；CLIENT_UPGRADE_REQUIRED 不重取。 |
| B10：旧 Plan 默认 FIXED；schema、runner、executor、policy、输入输出、Artifact 版本固定，显式适配不猜字段；增量迁移不覆写旧 checkpoint／历史。 | C §7；I §7 | E00/E03/E04/E10 合同与新增 SQL。 | 部分已验 | 生产装配 PR 明确兼容范围和迁移；新版本启动校验，真实 MySQL 另列待验。 |
| B11：单实例推进资格覆盖 assess→组装→发布；本地锁不是分布式保证，网络取消不可承诺原子撤销；实际 callback 退出独立于 Future。 | I §4.4；R §1.5、§3；G §6 | E00/E06 的原生 callback／同域进程死亡与 fencing。 | 部分已验 | P3/P5 端到端资格；若将来多实例，先另验租约／fencing，不能直接宣称已支持。 |
| B12：旧投放与风险 Agent 保持可用；共享模型／执行器影响单独验；最终解释 `.toolCallbacks(List.of())` 不变。 | I §2–3、§6；Issue #62 | E00 有双 Agent 既有回归。 | 保持边界 | 后续按实际共享改动运行必要回归；正式解释／报告不得暗中开启工具。 |

### 2.2 P0 与 P1：统一运行底座

| 要求 | 来源 | 证据 | 当前状态 | 剩余动作 |
| --- | --- | --- | --- | --- |
| P0-01：真实 1.1.2.3 ReactAgent＋直接 ChatModel，合法单 call、整批零派发拒绝、唯一配对修复、坏 ID 零调用。 | I §4.2；G §4.1；R §1.1 | E00/E01；NativeExplorationAdapterTest。 | 组件已验 | P3 复用这些边界，不能叠现有 ToolCallAdvisor；耐久修复预算另验。 |
| P0-02：PENDING 后工具／模型 0；原生清 state 的未决超时仍读账本；队列超时、拒绝和 Throwable 都正确撤销资格、真实退出归还。 | G §4.1；R §1.2、§1.5 | E00/E01；原生超时、排队、AssertionError／大异常回归。 | 组件已验 | 持久化 Child／native callback 同步门控见 P3，不把 P0 内存替身当生产账本。 |
| P0-03：规范 READY 观察、唯一 PENDING 配对、消息写失败可重试、重复恢复不追加；同 callId 可出现在不同 Assistant 中。 | G §4.2；R §1.3 | E01：NativeExplorationResumeTest 9 项。 | 组件已验 | P3 耐久 invocation／assistant／action 映射与 observation sequence；未发布响应路径单列验收。 |
| P0-04：HTTP／错误体／模型响应／参数／深 JSON 到每次 saver 的有界保护；不先收全量再压缩，不静默截断分析。 | R §1.4；G §4.1 | E00/E01 业务／模型传输与每 saver 边界；E08 逐页存储。 | 组件已验 | P3 集成路径与多 Run 组合峰值，额度依据可测；报告完整正文在后端。 |
| P0-05：Plan 模式、DAG、端口、schema、参数、覆盖与 DELIVERY 静态校验；恶意／未知能力调用前拒绝。 | C §2–3、§7–8；I §6 | E00：PlanContractTest／PlanValidatorTest 54 项。 | 组件已验 | 静态可执行性不等于业务完成；实际 Planner 与目标终评见 P4/P5。 |
| P0-06：原生 Skill 固定内容／版本、read_skill 当前授权，不扩大工具闭包；原生挂载／投影与跨执行身份隔离有实际证据。 | C §6；G §4、§5；R12 | E00/E01：RunPinnedSkills、Checkpoint tests。 | 组件已验 | P2 注册真实业务组合，P3 冻结包和闭包接耐久 Run。 |
| P0-07：多 Run 有界执行器／许可，CallerRuns／silent discard 不绕过，超时不早还实际 worker 许可。 | R §1.6；G §8 | E00：ProcessCapacityExecutorTest。 | 组件已验 | 真实装配后的解析／序列化驻留峰值与风险 Agent 共享影响仍待 P3。 |
| P1-01：Run／Plan／InputSet 不可变完整定义和 hash，可信 tenant/subject/authVersion/session，CAS token／attempt，取消改版旧 token 不能派发发布。 | C §1–2、§7；I §4.1 | E03/E04：JdbcCampaignRunStore、FrozenCampaignRun。 | 组件已验 | 用户请求／Run 创建幂等与生产装配；跨版本采用不是仅 revise 成功。 |
| P1-02：Action 保存可恢复原调用合同；每同步／异步 child 在 I/O 前持久化稳定 ID、wire 正文/hash；requestId 合法且≤96，attempt 不改逻辑身份。 | R §2–2.1；C §3.3 | E03/E10：RunLedgerTest、FrozenStatisticsJobQueryTest。 | 部分已验 | 接所有业务适配器与 REACT assistant 映射；记录原请求期限／策略版本／安全余量，不用新 ID 绕冲突。 |
| P1-03：PREPARED→DISPATCHING→已知 job／READY／UNRESOLVED；未知异步仅 recover-existing，未知同步 READ_RESULT_UNKNOWN 不盲目重 GET。 | R §2–2.1；I §4.3 | E03/E06/E10；StatisticsSubmissionReconciler。 | 部分已验 | 真实同步 Tool 适配／显式新收集代次；失败、等待和可用证据同时保留。 |
| P1-04：Agent→Admin→Analytics 独立 EXISTING_ONLY，复用 gate/hash/授权但零创建；旧端点、剥字段、近过期清理、正文冲突保留结构化错误。 | R §2；R13；V08/V28 | E02、E10。 | 组件已验 | FROZEN_SET 恢复原成员适配仍归 P2；真实跨服务与 MySQL gate 行为未实测。 |
| P1-05：已知 job 只读原 status/page，权限／过期／scope 变化／协议错不转空数据，不把 SUCCEEDED 等同 resultReady。 | R §2；G §6 | E07/E08：机器错误码及原 job 验证。 | 部分已验 | P2 RESULT_RELEASED 语义；生产查询接线与原期限对账。 |
| P1-06：同步 READY Artifact＋child 原子提交，重开不重查 A；B 等待／C 失败不抹去 A 或 B，丢失首次同步响应不冒充旧快照。 | R §2.1；R15；V09/V30/V32 | E03/E04 账本／绑定；E08 原页恢复。 | 部分已验 | 改造实际 compare／rank／dimension 等复合 Tool 子调用；补真实适配 A GET=1、B submit=1 故障窗口。 |
| P1-07：Step 冻结全定义、依赖、具名端口、必需输出；RUNNING 真实 callback；只在 output/schema/权限/质量/所有 child 满足后原子 SUCCEEDED。 | C §3–4、§7；I §4.1 | E04：JdbcCampaignStepStoreTest、PersistentPlanDriverTest。 | 组件已验 | 扩到 REACT／Skill；WAITING 需真实已受理 child，非可恢复 BLOCKED 不自动复活。 |
| P1-08：typed INPUT/STEP_OUTPUT/ARTIFACT，拒表达式／JSONPath／隐藏 scope；直接依赖、同 revision、类型基数／必需性；空、非空、完整、质量分别判定。 | C §3、§6 | E00/E04：PlanValidator、StepBindings、ArtifactContractRegistry。 | 组件已验 | P2 业务集合策略和 P3 局部 INPUT/ARTIFACT；上游部分／空集规则不能由 Planner 任意弱化。 |
| P1-09：Artifact 后端管理 type/schema/producer/scope/period/quality/provenance/hash/expiry，payload 独立；读前当前主体授权，旧 producer 不改。 | C §5；R §4.3 | E03/E04；inspectArtifact/readArtifact。 | 部分已验 | querySnapshot／复用与报告留存分层、HISTORY_VIEW/EXPORT 和消费关系尚未完成，不能用单 expiresAt 替代。 |
| P1-10：正常 END 后从领域账本扫描，A 等待不阻 C，完成不重跑；Graph 只保存短引用，递归保护按拓扑，checkpoint 不充当业务账本。 | G §3、§5–6、§8 | E03/E04/E08；NativePlanGraph、CampaignPagedResultGraphTest。 | 组件已验 | P3 native 探索嵌入、共享 saver／嵌套锁最终装配及 G06/G10 完整验证。 |
| P1-11：可证明进程死亡才清 exact callback；未知／存活零写，事务外观察后重锁核对，取消只 STOPPED，清理／审计／新 owner 同事务。 | I §4.3–4.4；R §1.5；G §6 | E06；CampaignProcessRecoveryTest 的真实子 JVM＋H2 文件。 | 组件已验 | 部署可信 processDomain／单实例边界配置；不把新实例 nonce 或超时当死亡，非跨主机租约。 |
| P1-12：逐页耐久接收 original job/snapshot、schema、scope、日期、filters、metrics 一致；连续页 hash 幂等，冲突／过期不混页；零行也读取 page0。 | R §4.1–4.2；C §5 | E08：Protocol／Store／Receiver。 | 组件已验 | P2 固定全集成员证明与跨查询可比性；不能把单查询页齐当 selectionComplete。 |
| P1-13：完整页集发布小 manifest＋Artifact＋READY 同事务，SQL 复核 counts／chain；STAGING 不可读，权限／期限／hash 检查，不全量 collect。 | R §1.4、§4.1、§4.3 | E08：5501 行、多 quantum、故障回滚；8MiB 单页可配置。 | 组件已验 | P5 报告引用／清理保护；P2 释放远端结果前的全部 consumer 可读证明。 |
| P1-14：固定统计查询四种 kind、冻结 scope/period/query、严格参数、稳定逻辑 slot；首次 submit／未知恢复／READY 重用同可信门控。 | I §4.1；C §7 | E10：METRICS／ACCESS_RECORDS／LINK_METRICS／DIMENSION_BREAKDOWN。 | 组件已验 | 该适配不等于全部 Tool；生产 QueryAuthorizer／注册及受理计划装配，FROZEN_SET 尚拒绝。 |
| P1-15：同事务当前 revision 进度快照，不领取 token／不读大 payload；cancel 可看原授权结果；逐结果计数，未知 children 不虚构总完成率。 | I §2、§5；R §5.2 | E05/E09：ProgressSnapshot／ResultProgressReader。 | 组件已验 | 接正式授权读取 API、恢复 nextAction；Goal 仍 NOT_ASSESSED，不冒充最终完成。 |
| P1-16：整体生产底座：可信入口装配版本目录／授权、请求幂等、Run/Step/Child/Artifact/recovery/receiver/driver、增量迁移与诊断。 | I §2–3、§7；C §7 | E03–E10 都注明组件未打开生产入口。 | 待实现／验收 | 实现受配置保护的新入口和组件装配；按后端合同测试用户请求→固定运行→恢复→结果；启用仍受客户端/P2/P3 相应门槛。 |

### 2.3 P2：固定范围、持续全量取数与业务组合

| 要求 | 来源 | 证据 | 当前状态 | 剩余动作 |
| --- | --- | --- | --- | --- |
| P2-01：CURRENT_GROUP／FROZEN_SET 不混；可信 FrozenScope 保存主体、来源组、完整 members/hash、枚举证明与授权版本；Graph 仅 scopeRef。 | C §3、§5；R §4.1；R14 | E11固定成员、E15严格权威页、E16耐久collector/ScopeArtifact/流式摘要。 | 部分已验 | 复合Skill/Plan绑定及生产当前授权装配；枚举版本不是历史可重读快照。 |
| P2-02：>500 成员确定性无重叠分片，各期间同片，scopeProof 区分片／冻结全集／当前全组；并集、唯一性、hash、返回 members 对账。 | R §4.1；C §3；V29 | E11成员proof、E16耐久501→500＋1/两期同片及完整成员摘要。 | 部分已验 | 全部统计 shard Artifact 的父集合覆盖对账；UV/UIP cohort 仍必须独立去重不能求和。 |
| P2-03：完整候选＋两期间全页＋逐对象可比或明确原因＋计算完成才 selectionComplete；51/16组合/10页之外的下降对象不能遗漏。 | C §4、§6；R09；V22 | E08 解开单任务 10 页障碍；旧 rank/compare 有界。 | 部分已验 | 持久化跨对象／期间／分片收集 coverage、cursor、代次；过期不接新快照后半页；不完整显式 PARTIAL。 |
| P2-04：periodsRef 冻结自然日期／时区；query signature 各自 snapshot；可比性 VERIFIED/UNVERIFIED/INCOMPATIBLE，有证据才共同底座。 | R §4.2；C §5、§9.3 | E08/E10 同查询范围日期／版本校验。 | 部分已验 | 同期间跨维度／过滤／回填验证；不同期间 manifest 不要求字面相等；分母、指标定义、观测截止和限制入产物。 |
| P2-05：活动执行、结果数量／字节、轻量恢复身份分账；QUERY_CAPACITY_EXHAUSTED＋capacityKind＋admitted=false；未知 ACK 不退回待提交。 | R §4.4；R18；I §2 | E12服务端分账、E14耐久拒绝/到期锁内校验/混合pending恢复。 | 组件已验 | 生产装配与复杂Skill接相同边界；无可信回执的旧未知记录不自动重投。 |
| P2-06：release-result 只新协议终态同主体 job；先耐久 READY／所有 consumer 可读，再同 binding CAS releaseIntent；网络事务外，重复／丢 ACK 对账。 | R §4.4；C §5 | E08耐久接收、E12远端协议、E13全页证明与单producer协调。 | 部分已验 | 生产当前 grant 装配、多消费者 adopt 与 GC 须锁同 binding；单producer组件不替代P4消费竞争。 |
| P2-07：第九个异步查询可在结果释放后推进，只做剩余 child；旧协议 TTL 不变；身份容量仍有高配置与清理，未知释放时间不编造 ETA。 | R §4.4；V33 | E12服务端第9任务、E13本地证明与释放、E14真实Agent执行链第9项续接，原TTL不变。 | 部分已验 | 生产/真实MySQL接线及P4多消费者竞争；不把可重试时间当容量恢复ETA。 |
| P2-08：`decline_selection` 真实执行两期全量对齐，delta<0 保留全部且按口径排序；baseline=0 rate=null，缺失／未创建／无遥测不当零。 | C §6、§9.3 | 拟新增，旧 rank 只是整窗 Top N。 | 待实现／验收 | 注册版本执行器与 selectedEntities／selectionEvidence 合同；未结束／长度不同／同对象重叠／近似／定义变化进入可比性。 |
| P2-09：`dimension_change` 继承确切动态入选集合，两期间真实联合维度；UNKNOWN/NOT_APPLICABLE 分开，PV 全窗分母，UV/UIP 独立去重。 | C §6；R §4.2 | 既有单对象下钻＋E10 DIMENSION_BREAKDOWN 是底层能力。 | 待实现／验收 | 固定集合／逐对象或明确 cohort 的组合适配与 artifacts；不可拼边际分布冒充交叉分布。 |
| P2-10：四种上游结果：真 NO_DECLINES、部分有选中、证据不足空集、无有效 Artifact 的 WAITING/NEEDS_INPUT/FAILED，各自传播。 | C §6“上游部分结果与空集合” | E04 只有一般依赖／schema 规则。 | 待实现／验收 | 真空集下钻零请求＋可追溯 NOT_APPLICABLE；不足空集不得称无下降；可用子集按策略 PARTIAL；缺必需输出阻断。SKIPPED 需可选端口及传播合同。 |
| P2-11：Skill 方法包用原生注册／Hook，确定性业务用普通代码或原生 StateGraph；固定内容版本、传递能力闭包，每内部调用恢复／授权同入口。 | C §6、§9.4；R12 | E01 RunPinnedSkills 只证明方法包机制。 | 部分已验 | 注册上述成熟组合及 evidence_synthesis 合同；没有匹配 Skill 仍允许合法 Tool 计划，不建新 loader/Runner/workflow DSL。 |

### 2.4 P3：耐久局部 ReAct 集成

| 要求 | 来源 | 证据 | 当前状态 | 剩余动作 |
| --- | --- | --- | --- | --- |
| P3-01：REACT 是一个外层 Step 的原生 ReactAgent 薄适配，共用 Driver／Artifact／Child，不自写循环；首期一个未完成 CALL。 | C §3.1；G §4 | E00/E01 原生适配；E04 Driver 仍固定执行器。 | 部分已验 | 将真实 NativeExplorationAdapter 接耐久 Step，明确产物验收与 WAITING/停止映射；不添加每 action 外层 Node。 |
| P3-02：冻结注册 policy/version、allowlist、scope/period、completion、termination；只允许 Tool 与非探索 Skill，校验递归闭包。 | C §3.1；I §4.2 | E00 静态合同；E01 Skill 包固定。 | 部分已验 | 实际执行器／方法包加载与策略校验；禁止直接／间接嵌套、SQL/URL/范围藏参数。 |
| P3-03：局部冻结 InputSet 仅 INPUT/已授权 READY ARTIFACT；无 ACTION_OUTPUT／局部 STEP_OUTPUT；动作 CALL/COMPLETE/NEEDS_INPUT/REQUEST_REPLAN/NO_PROGRESS。 | C §3.2；I §4.2 | P0 接纳机制；无完整耐久局部绑定。 | 待实现／验收 | 具名输入／参数／输出校验，决策只短 summary/证据；每 action 保存固定版本、范围、幂等及累计预算后派发。 |
| P3-04：持久化 invocation/assistant/toolCall→action/request/hash；规范 PENDING＋READY 新观察，投影版本／序列／ack 与失败重试防丢防重。 | R §1.3；G §4.2 | E01 实际 native 恢复仅可信内存 ledger。 | 部分已验 | JDBC ExplorationLedger／耐久投影事实；覆盖提交、账本、checkpoint 三个断点及未曾发布 PENDING 的唯一 READY 响应路径。 |
| P3-05：每次模型／handler／内部 HTTP 读当前 Run/step/attempt/child；timeout 清 native state 后仍阻模型，未决旧 callback 不因新 writer 恢复派发。 | R §1.2、§1.5；V31 | E00 原生反例；E04/E06 fixed 资格／死亡对账。 | 部分已验 | 两者集成故障用例；已在途仅记原事实，不能自动发布取消／旧版成功，不能承诺网络原子撤销。 |
| P3-06：有限协议修复、无进展、停止／重试／预算按 Run 累计，继续／重启／新 revision 不重置；耗尽保留证据与明确 stopReason。 | R §1.1；C §3.3、§9.5 | E00 内存 ledger 跨 invoke 修复额度。 | 部分已验 | 耐久 repair/budget/no-progress 合同及可配置高额度，补充授权和恢复窗口；COMPLETE 候选不能绕后端验收。 |
| P3-07：同 action 新 attempt 重试，WAITING 原 action/job/revision，容量阻断未受理；重放同义完成请求先复用事实，新鲜数据显式新分析。 | R §1.3、§2；I §4.3 | E01 同义调用阻断；E03/E10 fixed 请求身份。 | 部分已验 | 真实 Tool／Skill 适配与恢复顺序：READY→未决对账→已知 job→未提交；父级不丢局部失败／等待。 |
| P3-08：模型／活跃推进／大结果许可在加载前获得，固定短获取顺序，队列轻量且公平；拒绝不绕许可，真实退出才释放。 | R §1.6；G §8 | E00 容量类单独验收。 | 部分已验 | 同统一运行器接模型／解析／序列化路径，测多 Run 组合峰值／共享风险 Agent，配置有实测依据。 |
| P3-09：Plan/Explore 独立逻辑身份固定版本；子图不重复持锁，合法多轮不受旧16误限，有限模板缓存不随 session 无限长。 | G §5–8 | E00/E03 身份／40步／投影组件。 | 部分已验 | 持久化嵌套装配与缓存边界验收；原生 interrupt/resume 不成为未验证依赖。 |

### 2.5 P4：规划、重规划与复用

| 要求 | 来源 | 证据 | 当前状态 | 剩余动作 |
| --- | --- | --- | --- | --- |
| P4-01：拆 goal→具名 requirements，DATA/CALCULATION/DELIVERY/CAUSAL_EVIDENCE；固定 criterion/version/typed参数/证据端口、目录版本。 | C §2.1；R §5.1 | E00 静态 PlanningAssessment。 | 部分已验 | 真实需求与能力评估器；任意自然语言 acceptance 不是可执行检查器，未知规则保留 gap。 |
| P4-02：完整／部分／无 Skill 均能选择合法组合；单目标多步骤，required 由合法输出覆盖或显式 gap；静态可执行不证明语义理解正确。 | C §2.1；I §1 | E00 校验目录；业务生成未有验收。 | 待实现／验收 | 候选规划和搜索／组合；真实证据决定后续查询，而非固定 prompt 路由或名称相似匹配。 |
| P4-03：缺输入 NEEDS_INPUT、真实能力缺 UNSUPPORTED、数据不可用 UNAVAILABLE；规划未找到但未证实缺能力 PLANNING_UNRESOLVED，有部分答案 PARTIAL。 | C §2.1；I §4.4 | 合同与进度 NOT_ASSESSED。 | 待实现／验收 | 保留全部原目标／缺口；不因一次模型失败删目标，不放松过滤／指标／成员。 |
| P4-04：仅新证据、输入／能力变化可 replan；局部顺序／允许能力／普通重试／等待不变 revision；等价计划无新信息不循环。 | I §4.4；C §2.1；G §7 | RunStore 有 revise/fencing，不是业务重规划。 | 待实现／验收 | REQUEST_REPLAN 证据／未满足项校验、累计无进展；允许变化前先 P4 验收。 |
| P4-05：新定义先持久化／校验／编译，再 CAS 切 revision；失败不半覆盖，旧回调不更新新图／报告，消费者与 producer 分开。 | G §7 五步；C §7 | E03 的版本底座。 | 部分已验 | 原子发布新图及消费关系；输入改变新 inputSet，完成 Artifact 保留原 producer。 |
| P4-06：adopt 仅 Agent 本地，同原冻结请求/hash/scope/filter/period/snapshot/contract/currentauth/期限；兼容原 job 不 submit，不修改 Analytics lease。 | R §3；C §5、§9.6 | 未有 adopt binding 实现／验收。 | 待实现／验收 | job binding＋consumer 精确校验；取消冲突、释放后只许经授权本地消费，不能只靠 jobId。 |
| P4-07：adopt/cancel 同 binding CAS：NONE 才接管；退休只停自己consumer；无消费者才 REQUESTED，事务外远端 cancel，丢 ACK 对账后 CONFIRMED不重开。 | R §3 五项；V11–V13 | E03/E06 Run 取消不等于共享 job 消费取消。 | 待实现／验收 | 双向竞争、迟到 READY、外部直接取消、释放交叉用例；新旧有效 consumer 不误取消。 |
| P4-08：跨 Run Artifact 复用当前权限／成员／schema/period/snapshot/epoch/quality/expiry，显式消费关系；重新分析默认新数据。 | C §5；R §4.2–4.3 | E04 同版本绑定与 Artifact 授权。 | 部分已验 | ANALYSIS_REUSE 兼容策略；与历史读分离，不能同session／queryhash永久复用。 |

### 2.6 P5 与客户端：完整、可复现、真实交付的输出

| 要求 | 来源 | 证据 | 当前状态 | 剩余动作 |
| --- | --- | --- | --- | --- |
| P5-01：公共 GoalAssessor 在成功／等待／失败／单Tool／无综合Skill都运行，按 rule 记录 MET/NOT_MET/UNKNOWN/NOT_APPLICABLE与证据。 | C §2.1、§4、§6；I §5 | E00 定义；E05/E09 明确 NOT_ASSESSED。 | 待实现／验收 | 登记真实检查器；必需未知不 ANSWERED，NOT_APPLICABLE 需证据不能模型豁免，独立结果不被综合失败丢弃。 |
| P5-02：事实初评→report draft→DELIVERY 终评；每 ANSWERED goal 真实结果块或可授权访问完整入口，清单有排序，解释目标有数据对应解读。 | R §5.1；R17；V35 | 仅静态每 goal DELIVERY；无发布实现。 | 待实现／验收 | 固定 goal/requirement/section/block/evidence/read-action 绑定；删首目标表和入口使其降级，第二目标不受牵连；不能靠省略 DELIVERY 绕过。 |
| P5-03：观察／计算／假设／因果主张／建议分开；完整联合分布不是因果；全部／唯一／导致等强结论须覆盖与方法证据。 | C §2.1；R §5.1；R10 | 静态 CAUSAL_EVIDENCE 合同。 | 待实现／验收 | 矛盾解释拒该块保数据；因果条件不足 PARTIAL/gap，脚本反例通过后仍标真实模型质量待验。 |
| P5-04：evidence_synthesis 只产 analysis 建议，不发布权威完成；可信 ExecutionContext 注入原 goalSpecs；无模型也可确定性交付已算结果。 | C §6；I §5 | 仅拟议执行器。 | 待实现／验收 | 版本化类型合同与事实引用，解释失败／缺综合时公共发布器仍可工作。 |
| P5-05：按问题组织可重复章节：指标／图表→分段解释→相关表→假设反证→验证建议；不按节点强生等量卡片，不限几句话。 | I §5；C §4、§6；Issue #62 | 旧结果展示不等于新正式协议。 | 待实现／验收 | 版本化通用 ReportSection／block schema，goalIds/evidenceArtifactIds，全量结果入口和分页，保完整分析。 |
| P5-06：reportId/revision 预分配，实际固定载荷和 EvidenceManifest READY/hash/auth/read-contract/clientcapability 校验，同事务发布报告与保留引用。 | R §4.3、§5.1；C §5 | E03/E08 只提供 Artifact／分页事务前置。 | 待实现／验收 | ReportStore／公共发布器，正式读取路由；草稿不预公开、不靠 locationRef 或无法访问的 artifactId算交付。 |
| P5-07：reuseExpiresAt 与 retainedUntil 分开，旧 expiresAt兼容一致；清理与新引用同载荷锁/CAS，不可引用 STAGING/清理中载荷。 | R §4.3；C §5、§9.1 | 当前 Artifact 单 expiresAt，不能证明报告留存。 | 待实现／验收 | 增量生命周期／保留保护；失败仅本次无引用新增载荷待清理，不删除共享 READY，不暴露半报告。 |
| P5-08：HISTORY_VIEW/EXPORT 固定报告manifest、checksum、留存与当前对象权限；源job过期可读本地；不要求旧authVersion字面等当前；撤权仍拒绝。 | R §4.3；C §5 | 当前 Artifact严格授权不等于历史用途合同。 | 待实现／验收 | 正式历史／导出授权读、ANALYSIS_REUSE分离；清理后 REPORT_DATA_EXPIRED，不能静默重查当前数据；不新增远端 pin。 |
| P5-09：后端 executionStatus／goalAssessments／reportRef／nextAction 分开；旧 answer 从同一报告／状态适配，WAITING可与部分已答并存。 | R §5.2；C §4 | E05/E09 仅内部进度视图。 | 部分已验 | 版本化响应与旧 answer 兼容器；上次历史完成报告不可冒充本轮等待结果。 |
| UI-01：开放前最低客户端状态协议；老客户端新请求旧路径，续接新Run明确升级；客户端能力不是授权。 | I §2、§7；R §5.2；R11 | 新入口关闭，尚无最小状态消费验收。 | 待实现／验收 | API后端能力门与前端状态标签／导出修正；缺客户端验收不得开放新运行器。 |
| UI-02：通用章节／图表／文本／表格渲染、业务进度与局部恢复、证据入口、长表分页；不每Skill／prompt一页。 | I §5；后续前端阶段 | 旧页面功能不可视为新协议验收。 | 待实现／验收 | 消费正式 Report schema 与授权读取／nextAction；Plan/Action详细诊断独立入口，产品不暴露编排术语。 |
| UI-03：页面、历史、复制、导出同 reportId/revision 与固定证据；完整结果入口发布后可读，撤权／过期状态准确。 | I §5；R §4.3、§5.1 | 后端报告／客户端均无该验收。 | 待实现／验收 | 后端正式路由用fixture验收，允许前端验证后再做视觉／交互；不得把后台有 Artifact 等同用户已拿到结果。 |
| UI-04：保留计划中的前端与浏览器验收，不用“本轮仅后端”删除范围；真实模型质量同理单列。 | I §2、§5–7；Issue #62 | 当前明确禁止服务／Docker／真实模型，仅必要后端验证。 | 环境待验 | 在允许前不执行这些检查、不写“全量验收通过”；继续可独立完成的代码／后端工作，并记录可验边界。 |

## 3. 总计划 35 项后端验收逐项追踪

来源均为 I §6，顺序与原表一致。这里保留完整反例和必要断言；“部分”不可因相关类存在改为已通过。

| 要求 | 来源 | 证据 | 当前状态 | 剩余动作 |
| --- | --- | --- | --- | --- |
| V01：FIXED缺executor、REACT缺policy或字段冲突，工具前拒绝。 | I §6 #1；P0-05 | E00 PlanValidatorTest | 组件已验 | 新生产接纳入口复用同校验，不自动补猜。 |
| V02：单目标完整Skill／部分Skill／无Skill，能力够均合法，不误 UNSUPPORTED。 | I §6 #2；P4-01–03 | E00 静态目录／覆盖规则 | 部分已验 | 实际规划三个配置的产出与工具路径验收。 |
| V03：比较返回不同证据，脚本模型实际选择不同后续查询。 | I §6 #3；P3/P4 | E00 原生单工具循环，不是业务证据分支验收 | 待实现／验收 | 固定比较→局部探索实际调用顺序断言。 |
| V04：未知能力、超范围参数、错误Artifact，真实调用0。 | I §6 #4；P1-08/P3-02–03 | E00/E04/E10 静态及fixed授权 | 部分已验 | REACT当前授权／typed artifact错误路径零派发。 |
| V05：Skill额外工具／包内容变更，额外调用0且版本不漂。 | I §6 #5；P0-06 | E01 RunPinnedSkillsTest | 组件已验 | P2/P3业务组合接同版本闭包规则。 |
| V06：伪COMPLETE、缺输出、覆盖不足，不误Step成功／Goal完整。 | I §6 #6；P3-06/P5-01 | E00/E04静态与fixed输出；Goal未实现 | 部分已验 | native COMPLETE到耐久输出及公共Goal终评。 |
| V07：等待、丢ACK、宕机重复恢复，原submit次数／request/job／consumer正确。 | I §6 #7；P1-02–05/P4-06 | E02–E04/E06/E10 | 部分已验 | 多consumer/adoption及耐久REACT集成，不重提交。 |
| V08：request映射清理、同键不同正文，原action不新建、冲突失败。 | I §6 #8；P1-04 | E02/E10 | 组件已验 | 固定成员恢复及真实MySQL不在既有证据内。 |
| V09：一个Tool多个任务，稳定child且仅恢复未完。 | I §6 #9；P1-06 | E03/E04通用child底座 | 部分已验 | 实际复合Tool多任务适配与独立失败／等待。 |
| V10：局部多查询与等待同外层revision，action独立审计。 | I §6 #10；P3-01/04 | E01内存恢复／E03持久化组件分离 | 待实现／验收 | 耐久探索多action完整集成。 |
| V11：兼容新revision adopt不submit，不兼容拒绝。 | I §6 #11；P4-06 | 无adopt实测 | 待实现／验收 | 原request/hash/范围/版本/期限验证与consumer事务。 |
| V12：旧revision退休清理不取消活consumer job，不改workerlease。 | I §6 #12；P4-07 | Run取消不覆盖该场景 | 待实现／验收 | 多consumer共享job与退休测试。 |
| V13：adopt/cancel双向竞争、cancel丢ACK，同binding CAS且REQUESTED不重开。 | I §6 #13；P4-07 | 无完整binding | 待实现／验收 | 两个竞争顺序及丢回执对账。 |
| V14：旧callback、取消响应、checkpoint落后，不覆盖当前，不重做输出。 | I §6 #14；P1-07/11/P3-05 | E00/E04/E06/E08/E10 | 部分已验 | REACT和报告发布同样受当前资格保护。 |
| V15：无进展／策略边界明确原因、保留证据，恢复不清累计。 | I §6 #15；P3-06 | E00修复计数跨内存invoke | 部分已验 | 持久化budget/no-progress跨重启、改版。 |
| V16：直接／间接嵌套探索编译或调用前拒绝。 | I §6 #16；P3-02 | E00静态校验基础 | 部分已验 | 实际Skill依赖闭包和运行时额外能力拒绝。 |
| V17：多轮大结果／重复provenance，Graph按引用增长不复制表格。 | I §6 #17；B05 | E00/E03/E08 | 部分已验 | 多action／正式报告集成每saver仍受控；不是仅最终state测量。 |
| V18：首checkpoint、多call、空重复ID，每次saver有界、坏批0、配对合法。 | I §6 #18；P0-01/04 | E00/E01 | 组件已验 | 持久化挂载维持同边界。 |
| V19：拒批修复／重复违规重启，唯一未执行响应、耐久预算、无孤立或额外调用。 | I §6 #19；P3-04/06 | E00/E01内存跨invoke／重建adapter | 部分已验 | 真正重开JDBCledger恢复repair累计。 |
| V20：无Content-Length、大arguments、深JSON、巨大错误体在HTTP/解析前拦。 | I §6 #20；P0-04 | E00/E01，真实loopback传输fixture | 组件已验 | 接新所有网关，超界理由明确，不能静默裁切成完整。 |
| V21：完整联合分布不能把原因goal标ANSWERED；可发布观察。 | I §6 #21；P5-03 | 只有静态因果要求 | 待实现／验收 | GoalAssessor因果反例与真实报告降级。 |
| V22：第51候选／第17组合／第11页才有下降，全量找出或披露缺口。 | I §6 #22；P2-03/08 | E08单job5501行解开页限 | 部分已验 | 固定完整集合跨期筛选；不可把该分页测试当全量下降测试。 |
| V23：换维度/filter及回填，不跨signature复用snapshot；未知版本披露限制。 | I §6 #23；P2-04 | E08/E10同查询签名检验 | 部分已验 | 跨查询可比性策略／声明，不要求不同期间manifest相等。 |
| V24：单Tool、部分失败、无综合，公共评估报告均可工作；未知≠0，等待≠完成。 | I §6 #24；P5-01/04/09 | E05/E09进度分离 | 待实现／验收 | GoalAssessor＋ReportPublisher及旧answer完整路径。 |
| V25：历史／导出固定版本和范围，过期不换当前查询。 | I §6 #25；P5-06–08/UI-03 | 无正式report存储 | 待实现／验收 | 本地固定manifest正式读取／导出。 |
| V26：源expiry/epoch与报告清理竞争，历史本地manifest＋当前权限，新分析严格复用，无悬空引用。 | I §6 #26；P5-07–08 | E03/E08仅artifact前置 | 待实现／验收 | 发布与清理同载荷CAS及两种用途授权。 |
| V27：旧客户端无新协议不得新入口，状态显示另有前端门槛。 | I §6 #27；B09/UI-01 | 入口尚未开启 | 待实现／验收 | 能力协商及新Run旧client恢复拒绝；禁止fallback重取。 |
| V28：旧Admin/Analytics、剥字段、近expiry gate竞争恢复INSERT0，合法首submit正常。 | I §6 #28；P1-04 | E02/E10 | 组件已验 | 保留故障注入证据，真实部署联通另列环境待验。 |
| V29：冻结AB后加C、撤B、501分片；原集合、授权、并集/唯一/hash，UV不求和。 | I §6 #29；P2-01–02 | E11成员授权及proof拒错，E16耐久完整成员收集/两期同片/撤权与变代停止 | 部分已验 | 全部统计结果并集对账、实际业务UV口径与生产授权接线。 |
| V30：A同步READY、BWAIT；A落盘B ACK前崩溃，A GET1/hash不变、B submit1只对账B；失败不丢等待。 | I §6 #30；P1-06 | E03通用ledger，E10单异步 | 部分已验 | 实际同步＋异步复合业务adapter，非只wrapper聚合响应。 |
| V31：callback忽略cancel、旧/新writer重叠，额外model0/后续I/O0、迟到不发布。 | I §6 #31；P3-05 | E00原生反例；E06进程/epoch | 部分已验 | native＋JDBCchild＋当前恢复者一体化测试。 |
| V32：首次sync丢响应明确unknown/新代次；冻结page超时原snapshot恢复不混。 | I §6 #32；P1-03/12 | E03 unknown分类，E08页cursor恢复 | 部分已验 | 实际sync适配和新收集代次；页恢复证据不可代替首GET语义。 |
| V33：八个保留完成任务后第九、release/consumer竞争；明确未受理、只推进剩余、同ID不重建。 | I §6 #33；P2-05–07 | E12远端第9任务、E13单producer证明/释放、E14Agent九任务续接 | 部分已验 | P4多消费者竞争及生产/真实MySQL联合验收。 |
| V34：多Run慢模型/大结果cancel/timeout，推进/model/解析峰值受控，实际worker退出才还，队列短refs。 | I §6 #34；P3-08 | E00容量组件，不是整链峰值 | 部分已验 | 新运行器真实装配的后端多Run测量，共享风险Agent影响。 |
| V35：两goal有证据但删首表/完整入口，首不能ANSWERED；恢复后通过，第二独立，无强制长文。 | I §6 #35；P5-02 | 仅静态DELIVERY覆盖 | 待实现／验收 | 真草稿＋正式授权读入口的发布终评。 |

## 4. Graph 19 项专项验收

| 要求 | 来源 | 证据 | 当前状态 | 剩余动作 |
| --- | --- | --- | --- | --- |
| G01：同Plan节点身份稳定，新revision独立链，CompiledGraph不动态改节点。 | G §9 #1 | E03 NativePlanGraphTest | 组件已验 | 混合挂载保持冻结拓扑。 |
| G02：FIXED不探索，REACT多轮不增加外层Step。 | G §9 #2 | E00探索/E04fixed分开 | 部分已验 | 同一持久化混合Plan实际运行。 |
| G03：A等/B依赖/C独立，C先完成，A就绪B得正式输出、C不重做。 | G §9 #3 | E03/E04/E08 | 组件已验 | 新业务适配不破坏此规则。 |
| G04：父大哨兵不进子checkpoint，双向仅允许字段。 | G §9 #4 | E00 NativeExplorationCheckpointTest | 组件已验 | 生产子图沿用显式投影。 |
| G05：合法逻辑身份／长度；同执行恢复，跨用户/步骤/版本不串链。 | G §9 #5；§5.2 | E00/E03身份矩阵 | 组件已验 | 真实MysqlSaver字段／物理ID仍列待验，不误把thread_id36当thread_name限制。 |
| G06：同/不同saver的子图命名与配置继承，包装映射、逻辑长度和物理主键。 | G §9 #6 | E00同saver身份／投影，E03逻辑UUID | 部分已验 | 不同saver装配与物理ID专项证据；真实MySQL另验，不能只反射API。 |
| G07：checkpoint领先/落后、循环崩溃、END再推进，action/job幂等且正式输出不重做。 | G §9 #7 | E01/E03/E06/E08固定路径 | 部分已验 | 耐久REACT三处断点恢复。 |
| G08：native END但业务WAITING仍等待，callback/继续竞争单合法转移。 | G §9 #8 | E00/E04/E06/E08 | 部分已验 | 公共响应接真实状态，耐久探索竞争。 |
| G09：超旧16规模合法Plan／多轮探索各用自身保护，风险图不改。 | G §9 #9 | E03四十步；E00原生循环 | 部分已验 | 混合运行、累计业务预算与Explore节点转换额度。 |
| G10：两层子图不重复锁死；cancel/revision旧token不可提交。 | G §9 #10 | E03/E04/E06 token隔离 | 部分已验 | 同Run实际嵌套持锁路径及取消／改版集成。 |
| G11：原生interrupt/resume独立版本测试，未证实不得当关键依赖。 | G §9 #11 | 当前采用显式END＋领域恢复 | 保持边界 | 首期不依赖；若引入则先专项测试，不用API存在冒充通过。 |
| G12：无第二Advisor循环；多call/PENDING/error/final映射，不绕门控。 | G §9 #12 | E00/E01 | 组件已验 | P3持久化映射保持原生机制。 |
| G13：批次派发前拒绝，已有WAITING防御后工具/model0；无最终文本可invoke返回。 | G §9 #13 | E00/E01 | 组件已验 | 生产resume preflight不重复START/输入。 |
| G14：空重复ID零调用，submit/ledger/checkpoint断点唯一配对与submit1。 | G §9 #14 | E00坏ID、E01内存恢复、E10丢ACK | 部分已验 | 持久化消息映射同一完整链故障注入。 |
| G15：每次saver有界，END→START不重复消息，首次checkpoint前大结果已refs。 | G §9 #15 | E00/E01/E03 | 组件已验 | P3生产账本／P5报告同样投影，不把全量子账本进state。 |
| G16：忽略取消、SDK清state、新旧writer叠加；账本阻model/后续I/O，晚到不能成功。 | G §9 #16 | E00实native、E06独立持久化 | 部分已验 | 两者接通并验实际callback生命周期。 |
| G17：多Run慢model/大结果峰值、实际exit许可、队列无全量载荷。 | G §9 #17 | E00 ProcessCapacityExecutorTest | 部分已验 | 完整装配与内存测量，而非仅serializer字节。 |
| G18：syncREADY＋asyncWAIT恢复不重GET/改hash，父不丢等待，容量拒绝不造job。 | G §9 #18 | E03/E04通用账本 | 部分已验 | 实际复合adapter＋P2容量合同。 |
| G19：恶意label/维度仍数据，不进入SystemMessage权限。 | G §9 #19；R2 §3候选3 | 原生短引用与角色边界有基础，未找到该专门业务反例运行记录 | 待实现／验收 | 接投影器后用恶意业务字段断言角色不提升／工具规则不改变；不把受信信封误当所有文本可信。 |

## 5. G0／G1／G2 与阶段开启条件

| 要求 | 来源 | 证据 | 当前状态 | 剩余动作 |
| --- | --- | --- | --- | --- |
| GATE-G0：原生接纳/唯一配对、WAIT/timeout后0model、旧callback0后续I/O、首次HTTP/saver有界、多Run真实worker许可。 | R §6；P0/P3 | E00/E01满足P0组件；V31/V34仍需生产组合 | P0组件门槛已验，P3集成未验 | 完成P3耐久／准入挂载，不能只复用“P0通过”开启REACT。 |
| GATE-G1：Admin全链恢复零新建、近expiry、adopt/cancel/release、sync不重查、冻结分片、不混页、第9查询及恢复身份。 | R §6；P1/P2/P4 | E02–E10仅部分子项 | 部分已验 | P1全适配＋P2范围/容量＋P4binding；逐反例完成后才关闭该协议门。 |
| GATE-G2：发布清理无悬空、无因果不ANSWERED、逐goal实际可达、旧client不恢复新路径、历史/导出同版。 | R §6；P5/UI | 无完整发布/客户端运行证据 | 待实现／验收 | P5公共发布、固定留存与正式读取＋最低client门。 |
| OPEN-P3：P0机制、P1幂等/等待恢复、P2统一授权先通过；用户开放另需客户端状态适配。 | I §7 | P0已验；P1部分/P2未交付 | 保持关闭 | 依次完成，不以调高循环上限或先接模型替代。 |
| OPEN-P4：版本切换/兼容adopt/CAS及旧consumer安全先验，才允许运行中模型外层replan。 | I §7；R §3 | 未验 | 保持关闭 | P4-04–07及V11–V13。 |
| OPEN-P5：逐goal事实/交付终评与报告校验先验，才宣称完整图文能力。 | I §7 | 未验 | 保持关闭 | P5-01–09、UI-01–03。 |
| CLOSE-ALL：每阶段附风险→条款→测试名→关键断言→实际结果，scope完整、未知不隐去；代码与文档、真实环境边界分别交代。 | I §7；Issue #62 | 本矩阵及既有批次报告 | 尚未达到总交付 | 所有待办有实际证据再更新；环境未允许运行的验收保留待验，不能写全部完成。 |

## 6. R01–R19 风险追踪

级别是原审查中的风险优先级，不是实施阶段编号。设计全部 SPECIFIED；“组件验收”均不等于跨阶段风险关闭。

| 要求 | 来源 | 证据 | 当前状态 | 剩余动作 |
| --- | --- | --- | --- | --- |
| R01/P1：WAITING需同批接纳、handler与next-model多层门控。 | R1/R §1.1–1.2 | E00/E01；V18/G13 | 实现部分；组件已验 | P3耐久ledger集成，写失败／Hook异常不让模型继续。 |
| R02/P1：空／重复callID不可猜配对。 | R1/R §1.1 | E00/E01 provider＋native；V18 | 对应机制已实现／已验 | 新链调用必须使用同校验；P3持久化配对另由R03覆盖。 |
| R03/P1：业务身份与消息唯一恢复，不能重复PENDING/READY或丢观察。 | R1/R §1.3 | E01 Resume9；G14 | 实现部分；组件已验 | P3 durable mapping/projection/ack，各崩溃点重建。 |
| R04/P1：首次checkpoint前及HTTP前的大载荷内存边界。 | R1/R §1.4 | E00/E01/E08；V17/V20 | 实现部分；组件已验 | 全新路径每页／每saver／缓存／报告投影；组合峰值另R19。 |
| R05/P1：未知提交原身份、requestTTL/gate竞争不能重建。 | R1/R §2 | E02/E03/E06/E10；V08/V28 | 窄协议已实现／已验，接线部分 | 全Tool冻结原请求／期限；生产入口接recover-existing，不用默认submit兜底。 |
| R06/P2：共享job consumer接管／取消，非远端worker迁移。 | R1/R §3 | Run token基础E03，不是adopt | 待实现／验收 | P4binding CAS、consumer、cancel intent及release交叉。 |
| R07/P2：短snapshot不足历史复现，报告本地耐久留存。 | R1/R §4.3 | E03/E08只存结果 | 待实现／验收 | P5保留引用/清理事务、HISTORY_VIEW/EXPORT与ANALYSIS_REUSE区分。 |
| R08/P2：同期间不同query不保证共同数据版本。 | R1/R §4.2 | E08/E10同query验证 | 实现部分；跨查询待验 | P2 querySnapshot/comparability，未知披露，不要求两期间manifest相同。 |
| R09/P1：TopN／有界组合不能证明全部下降。 | R1/R §4.1 | E08完整单job分页 | 实现部分；全集待验 | P2固定候选/两期全量/selectionComplete/覆盖与可比性。 |
| R10/P1：自由文验收和产物齐全不证明因果或ANSWERED。 | R1/R §5.1 | E00静态requirement | 合同已实现；终评待验 | P5真实检查器/因果gap/解释与事实冲突处理。 |
| R11/P1：旧页面／导出误把部分结果显示完整。 | R1/R §5.2 | 入口关闭是防护，不是兼容实现 | 待实现／验收 | v2能力门＋旧answer适配＋客户端最小状态消费。 |
| R12/P2：晚验框架导致重复建设。 | R1/R §6 | E00/E01真实native及方法包；非静态API自证 | P0机制已验；保持约束 | P2/P3复用原生、禁止自造循环/Skill DSL；版本变化做必要独立回归。 |
| R13/P1：EXISTING_ONLY经旧Admin丢失降级创建。 | R2 §2/R §2 | E02独立双端恢复及旧协议fixture；V28 | 窄协议已实现／已验 | P2固定成员、生产装配保留该窄入口，不以可选字段替代。 |
| R14/P1：冻结候选与当前全组鉴权/证明冲突。 | R2 §2/R §4.1 | E11指定成员授权／proof、E15权威页、E16耐久枚举及固定成员凭证 | 实现部分；统计全集集成待验 | 统计结果的父集合最终覆盖对账与生产当前授权装配。 |
| R15/P2：复合Tool恢复会重查syncREADY、丢pending。 | R2 §2/R §2.1 | E03/E04底座 | 实现部分；业务适配待验 | V09/V30/V32真实同步异步复合执行路径，不能只保存聚合jobs数组。 |
| R16/P1：native超时后旧callback仍可继续请求。 | R2 §2/R §1.5 | E00实际忽略cancel回调，E04/E06fencing | 实现部分；集成待验 | P3每真实I/O与next-model durable门控；新writer≠旧callback退出。 |
| R17/P2：算完未交付每个目标。 | R2 §2/R §5.1 | E00静态DELIVERY覆盖 | 待实现／验收 | P5真draft与正式可读结果终评；V35。 |
| R18/P2：完成job仍占8保留名额，第9子查询阻断。 | R2 §2/R §4.4 | E12远端分账、E13释放、E14耐久退避与Agent九任务续接 | 单producer组件链已验；整项保持部分 | P4多consumer协作与生产联合验收；cancel不当释放。 |
| R19/P2：单请求有界≠多Run内存安全。 | R2 §2/R §1.6 | E00进程准入组件 | 实现部分；整链峰值待验 | P3活跃/model/解析准入和实际退出、共享风险Agent；高上限依据测量。 |

## 7. 当前可执行的下一批与最终验收边界

接下来的三个关键缺口，按依赖顺序推进，不将其余阶段移出目标：

1. **完成 P1 的统一可信运行装配与剩余业务子调用适配。** 已验组件接成受配置保护的请求／Run 幂等、冻结输入、授权、运行、恢复、结果及进度合同；补实际同步 READY＋异步 WAIT 的复合 Tool 路径。生产启用与最低客户端状态门分开，不因后端装配完成自动开放。对应 B01/B09、P1-02/06/16、V07/V09/V30/V32。
2. **完成 P2 的固定范围与持续取数基础，再上线下降筛选／维度变化组合。** 三层 FROZEN_SET、501 成员证明、容量分账／release-result、两期全量对齐先于宣称“全部下降”；不以现有 TopN、单job全页或调大上限充数。对应 P2-01–11、V22/V23/V29/V33、R08/R09/R14/R18。
3. **把 P0 原生探索接耐久运行，并沿总计划完成 P4 与 P5/客户端交付。** P3 durable消息／action／budget／callback／容量集成后，完成真实混合规划与adopt/cancel，再做公共逐目标评估、固定报告、历史导出和通用前端。不能到“模型能跑”或“统计能出表”就结束。对应 P3/P4/P5/UI 全矩阵、G0/G1/G2。

验收记录必须把下面几类证据分开：

| 要求 | 来源 | 证据 | 当前状态 | 剩余动作 |
| --- | --- | --- | --- | --- |
| VERIFY-01：必要的受影响后端单元／组件／H2／loopback／脚本模型测试，失败修复后只重跑受影响项。 | I §6、仓库 AGENTS.md、当前用户限制 | E00–E10按批次报告；本次未运行 | 保持边界 | 后续实现附准确selector、断言、实际结果；不反复全量测试或扩大无关审查。 |
| VERIFY-02：真实 MySQL 方言/锁/CAS/迁移/MysqlSaver字段与物理主键。 | G §9 #6；I §6；R §6 | H2不能替代；本次无MySQL运行证据 | 环境待验 | 当前不启动服务/Docker；保留明确验证项，不能以H2通过标真实MySQL已验。 |
| VERIFY-03：真实跨服务统计队列、真实数据量/内存配置、线上模型语义质量。 | I §6；R §1.6、§5.1 | fixture与真实子JVM只证明对应机制 | 环境待验 | 不调用真实模型／统计服务；先完成可测后端合同，实际环境证据在允许条件下另记。 |
| VERIFY-04：新客户端状态、结果可访问性、图文布局/交互、历史/复制/导出同版。 | I §2、§5–7；UI-01–04 | 尚无新协议前端验收 | 环境待验／待实现 | 保留全部客户端交付任务；本次不跑浏览器、前端构建或应用，不把不可执行的验收写成通过。 |

收尾条件：以上矩阵没有被静默删掉的要求；每项实现/验证状态有证据；所有实际允许且所需的后端验收通过；尚未允许的真实环境与客户端验收仍明确标识，**不能据此宣称全部范围已经验收完成**。后续工作以用户要求的全计划为目标继续推进。

## 8. 本清单自身核验

本次只做文档静态检查：V01–V35、G01–G19、R01–R19 逐项存在且不重复，G0/G1/G2 和 P0–P5／客户端阶段未丢失；本地 Markdown 链接可解析；表格和代码围栏完整。此检查不产生任何业务实现、后端运行或真实环境通过结论。

[I]: ../plan/campaign-agent-architecture-2026-09-19/implementation-plan.md
[C]: ../plan/campaign-agent-architecture-2026-09-19/contracts.md
[G]: ../plan/campaign-agent-architecture-2026-09-19/graph-adaptation.md
[R]: ../plan/campaign-agent-architecture-2026-09-19/risk-controls.md
[R1]: ../plan/campaign-agent-architecture-2026-09-19/risk-review.md
[R2]: ../plan/campaign-agent-architecture-2026-09-19/risk-review-round2.md
[E00]: ../integration/campaign-plan-p0-2026-09-19.md
[E01]: ../integration/campaign-plan-p0-recovery-2026-09-19.md
[E02]: ../integration/campaign-plan-p1-recovery-2026-09-19.md
[E03]: ../integration/campaign-plan-p1-ledger-2026-09-19.md
[E04]: ../integration/campaign-plan-p1-steps-2026-09-19.md
[E05]: ../integration/campaign-plan-p1-progress-2026-09-19.md
[E06]: ../integration/campaign-plan-p1-process-recovery-2026-09-19.md
[E07]: ../integration/campaign-plan-p1-job-read-2026-09-20.md
[E08]: ../integration/campaign-plan-p1-paged-results-2026-09-20.md
[E09]: ../integration/campaign-plan-p1-result-progress-2026-09-20.md
[E10]: ../integration/campaign-plan-p1-statistics-executor-2026-09-20.md
[E11]: ../integration/campaign-plan-p2-frozen-scope-2026-09-20.md
[E12]: ../integration/campaign-plan-p2-result-release-2026-09-20.md
[E13]: ../integration/campaign-plan-p2-release-coordination-2026-09-20.md
[E14]: ../integration/campaign-plan-p2-capacity-continuation-2026-09-20.md
[E15]: ../integration/campaign-plan-p2-authority-pages-2026-09-20.md
[E16]: ../integration/campaign-plan-p2-scope-collector-2026-09-20.md
[CODE-PLAN]: ../../services/agent-service/src/main/java/com/jupiter/shortlink/agent/campaignanalysisagent/planning
[CODE-RUNTIME]: ../../services/agent-service/src/main/java/com/jupiter/shortlink/agent/campaignanalysisagent/runtime
[CODE-SKILLS]: ../../services/agent-service/src/main/java/com/jupiter/shortlink/agent/campaignanalysisagent/skills
[TEST-CAMPAIGN]: ../../services/agent-service/src/test/java/com/jupiter/shortlink/agent/campaignanalysisagent
[CODE-DRIVER]: ../../services/agent-service/src/main/java/com/jupiter/shortlink/agent/campaignanalysisagent/runtime/plan/PersistentPlanDriver.java
[CODE-FIXED]: ../../services/agent-service/src/main/java/com/jupiter/shortlink/agent/campaignanalysisagent/runtime/plan/StatisticsJobFixedExecutor.java
[CODE-PROGRESS]: ../../services/agent-service/src/main/java/com/jupiter/shortlink/agent/campaignanalysisagent/runtime/progress/CampaignProgressService.java
