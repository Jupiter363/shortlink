# 投放分析 Agent：外层 Plan 与局部 ReAct 实施计划

状态：**P0 组件验证完成，P1 实施中，P2 范围合同已开始，P3–P5 待实现**。日期：2026-09-19。设计审查基线：`6c93bd9`。实施跟踪 [Issue #62](https://github.com/Jupiter363/shortlink/issues/62)，已执行检查与边界见 [首批 P0 验证](../../integration/campaign-plan-p0-2026-09-19.md)及[补充验收](../../integration/campaign-plan-p0-recovery-2026-09-19.md)。后续阶段清单不等于已实现；新入口仍关闭。

P2 前置进度：[冻结成员与范围证明](../../integration/campaign-plan-p2-frozen-scope-2026-09-20.md)补齐指定成员当前授权、501 成员确定性分片、跨服务专用查询／恢复及 Artifact proof；耐久枚举收集、父集合对账、配额释放和业务组合仍待完成。全部完成条件见[逐项完成矩阵](../../development/campaign-agent-completion-2026-09-20.md)。

P1 分批进度：[独立恢复协议](../../integration/campaign-plan-p1-recovery-2026-09-19.md)、[耐久账本与原生计划扫描](../../integration/campaign-plan-p1-ledger-2026-09-19.md)、[步骤输出与持久化 Graph 驱动](../../integration/campaign-plan-p1-steps-2026-09-19.md)已合并；[授权进度与可用结果视图](../../integration/campaign-plan-p1-progress-2026-09-19.md)区分执行进度与交付结论；[进程死亡确认与恢复协调](../../integration/campaign-plan-p1-process-recovery-2026-09-19.md)提供精确回调接管与原生 Graph 续接；[统计任务读取合同](../../integration/campaign-plan-p1-job-read-2026-09-20.md)补齐结果接收前置的跨服务机器错误码；[分页耐久接收](../../integration/campaign-plan-p1-paged-results-2026-09-20.md)保存进度并在完整发布后唤醒原生 Graph；[结果接收进度](../../integration/campaign-plan-p1-result-progress-2026-09-20.md)按授权快照展示已登记结果的页数与行数；[真实统计固定执行器](../../integration/campaign-plan-p1-statistics-executor-2026-09-20.md)将首次提交纳入冻结请求与原任务恢复链路。整体 P1 与生产运行入口尚未完成。

关联：[架构方案](../campaign-agent-architecture-2026-09-19.md)、[Graph 适配](graph-adaptation.md)、[合同附件](contracts.md)、[固定计划示例](example-plan.json)、[混合计划示例](example-hybrid-plan.json)、[框架复用清单](framework-reuse.md)、[风险审查](risk-review.md)。

R01–R19 的处理顺序与故障出口已经细化为[风险处理协议](risk-controls.md)。实施按其 G0／G1／G2 门槛提供证据，不以“文档写了防护”判定风险关闭。

[第二轮审查 R13–R19](risk-review-round2.md)已纳入方案：P1 覆盖 Admin 恢复代理及所有子调用；P2 完成固定范围／分片、结果配额分账与释放；P0／P3 验证未决超时和多 Run 容量；P5 检查每个目标的实际交付。方案确定与代码／运行验收分别记录。

## 1. 本轮确定的设计

**外层 Plan 管目标、依赖、范围与完成条件；局部按需探索。** 所有请求共用 Plan／Node／Graph 运行记录。路径明确的步骤直接执行；只有下一项行动需要依赖新观察时，才使用局部 ReAct。

两条维度分别建模：

- `executionMode=FIXED`：步骤绑定一个版本化 Tool 或 Skill。
- `executionMode=REACT`：步骤绑定经服务端批准的探索策略，由 LocalExplorer 根据证据选择行动；每次行动仍绑定 Tool 或 Skill。
- `executor.kind=TOOL|SKILL`：能力类型，不新增一个与它们并列的「REACT 业务能力」。通用探索运行器无需某个专属领域 Skill 才能工作。

场景／意图帮助确定要回答的问题，不能硬编码执行模式。一个复杂单目标可以包含多个固定步骤、一个探索步骤或混合步骤；多个目标也可以完全按确定的计划执行。

Graph 适配采用三层职责：沿用现有控制流程；冻结 PlanSpec 编译成原生 PlanGraph；REACT 步骤复用 ReactAgent 内部循环，逻辑上称 ExploreGraph。三层不强制对应三套新图或持久化设施。P0 优先验证 asNode／原生子图挂载，必要时才增加薄投影适配；LocalExplorer 不自建循环，局部行动不自动新增外层 Node。

示意执行：

```text
外层 Plan：解释本期访问变化，并提出有证据的验证建议
  A 固定步骤：确认范围、取得可比较的变化事实
  B 探索步骤：定位变化相关的分布，按证据选择下一项取证
      读取现有证据 → 提出行动 → 后端校验 → 调用能力 → 记录观察
      满足步骤合同则提交输出；需要改变外层约定则请求重新规划
  C 固定步骤：综合证据，整理观察、假设与限制
外层评估：逐目标判断完成情况，再组装完整图文报告
```

A／B／C 是业务执行示意，具体可执行名称以能力目录为准。没有某个 Skill 时可由已登记工具组成等价计划；缺少实际查询／计算能力时明确缺口，不生成虚构执行器。

## 2. 实施范围与顺序

首期主体在现有 `services/agent-service/` 内实现；`services/admin/` 与 `services/shortlink-analytics-api/` 同步补独立 recover-existing、冻结范围传递与证明、结构化错误、终态结果释放及配额分账。复用原授权、submit gate／请求哈希、ToolRegistry、查询任务及原生 Graph checkpoint，不拆微服务、不引入通用编排平台。风险 Agent 继续原执行方式；共享模型／执行器的容量影响单独回归，不顺带改其规划策略。

按以下顺序拆 PR。每项依赖前项的可验证合同；若单项差异过大可拆内部提交，但不能提前打开尚无持久化／恢复保障的探索入口。P0 已开始编码，其交付状态以验证记录及关联 PR 为准。

| 阶段／PR | 交付内容 | 完成门槛 |
| --- | --- | --- |
| P0／PR 1：框架验证、合同与校验 | 以原生 ReactAgent＋Tool 替身验证等待、配对、超时后存活回调、体积、节点组合与多 Run 准入；冻结目标交付、范围／子调用合同 | 关键原生扩展点通过组件验证；未决超时后无额外模型调用；非法计划在调用前失败；不得先开发替代引擎 |
| P1／PR 2：统一运行底座与跨服务恢复 | PlanGraph、Action/ChildLedger、Artifact；Agent→Admin→Analytics 独立恢复入口、结构化错误和稳定 requestId | 旧代理／清理竞争恢复零 INSERT；同步 READY 不重查；已有 job 与容量阻断分开；未决调用不得自动替代 |
| P2／PR 3：固定范围、持续取数与 Skill | CURRENT_GROUP/FROZEN_SET、可信分片与证明；配额分账和 release-result；再登记确定性组合与原生 Skill 方法包 | 成员增删不漂移；501 分片对账；第 9 个异步查询在释放后推进而恢复身份仍有效；无通用 Skill Runner／DSL |
| P3／PR 4：局部 ReAct | 集成 P0 原生循环与 Hooks／Interceptors；接所有子请求派发门控、持久化账本和进程准入 | 等待／超时均让出；旧 callback 只记事实、不能继续派发；多 Run 峰值受控且许可不因 Future 超时提前归还 |
| P4／PR 5：混合规划与续接 | 能力／不确定性评估、候选计划校验、REQUEST_REPLAN、跨轮 Artifact 与在途任务接管 | 部分失败不丢目标，新旧版本隔离；重规划复用结果，不重新提交兼容的在途任务 |
| P5／PR 6：报告协议与兼容 | 事实初评→草稿组装→逐目标 DELIVERY 终评；图文 Schema、固定历史／导出、旧 answer 兼容 | 每个 ANSWERED 目标的实际结果可访问；一步 Tool／等待／部分失败均有真实状态；解释失败不污染事实 |
| 后续前端阶段 | 通用章节／图表／文本模块、局部分析进度、证据入口和同版本导出 | 在允许前端与浏览器测试后再做视觉验收；本轮不进行 |

P1 必须提供最小的目标状态和可用结果说明。**新运行器对用户开放前，还必须完成旧客户端的最小状态消费适配**；当前页面与导出硬编码“完整结果”，只新增后端字段无法修复。在当前仅允许后端验证的阶段保持新入口关闭，可增加协议能力检查，旧客户端不得进入新路径。P5 完成正式图文协议；P3 不以完善专属 Skill 库为前提。

P2 先实现范围／容量协议，再登记下降筛选 Skill：固定完整候选、相同两期分片、LINK_METRICS 全页 Artifact 读取和按对象对齐计算。Top 50、16 组合、10 页和保留任务 8 个名额均不能作为全量承诺的依据。结果耐久接收后受控释放远端页，保留恢复身份；真正容量不足时保留未提交子项与退避条件。不得只调高常量、拼接 UV／UIP 或宣称完成任务自动腾位。

PR 2 可按“2a 跨服务恢复合同→2b 领域账本／子调用”拆分，PR 3 可按“3a 范围与证明→3b 配额／释放→3c 组合能力”拆分；顺序和启用门槛不变。新增入口必须随 Admin 与 Analytics 的合同测试一起交付，禁止只合入 Agent 字段然后打开能力。

## 3. 对应代码边界

以下新类型／包均是拟议名称，实现时按仓库既有规范调整；职责与边界不随名称改变。

| 区域 | 现有入口与拟新增职责 |
| --- | --- |
| `campaignanalysisagent/graph` | 保留范围、期间解析与旧结果适配；逐步替换 Invocation 列表和单节点工具循环，不把它们重命名后当成步骤执行 |
| `campaignanalysisagent/planning` | PlanSpec、覆盖评估、执行策略选择、PlanValidator、剩余任务重规划；不直接运行工具 |
| `campaignanalysisagent/runtime` | PlanStepNodeAdapter、LocalExplorer、Action／ChildLedger、推进资格与派发 epoch、状态归并和恢复；准入复用有界执行器 |
| `campaignanalysisagent/skills` | 版本化非探索 Skill 及工作流合同；首期优先少量成熟组合 |
| `campaignanalysisagent/artifact` | 服务端 Artifact 元数据、必要载荷、授权读取、输入绑定、复用与有效期 |
| `campaignanalysisagent/report` | GoalAssessor、事实／解释分离、章节组装、报告版本与导出清单 |
| `tool/registry` 与 `tool/shortlink` | 现有能力的可信调用适配；新增服务端 action／子调用上下文，稳定传递幂等与任务引用 |
| `harness/checkpoint` 与 `infrastructure/persistence` | 保持原生 threadId 兼容，增加领域账本与状态 CAS；不误把 native FINISHED 当业务完成 |
| `admin/.../controller` 与 `remote/analytics` | 新恢复／释放入口和 DTO／Facade／Client；固定成员重新授权、scopeProof、结构化错误；resultReady 按结果状态判断 |
| `shortlink-analytics-api/.../job` | 独立恢复入口复用原 gate/hash；冻结查询校验；结果与恢复身份分账；release-result 保留原身份、原期限与幂等 |

代码定位：[固定 Graph 执行器](../../../services/agent-service/src/main/java/com/jupiter/shortlink/agent/campaignanalysisagent/graph/DefaultCampaignAnalysisGraphExecutor.java)、[Planner](../../../services/agent-service/src/main/java/com/jupiter/shortlink/agent/campaignanalysisagent/graph/CampaignAnalysisPlanner.java)、[统计工具](../../../services/agent-service/src/main/java/com/jupiter/shortlink/agent/tool/shortlink/CampaignStatisticsTools.java)、[ToolRegistry](../../../services/agent-service/src/main/java/com/jupiter/shortlink/agent/tool/registry/AgentToolRegistry.java)。

当前解释节点显式设置 `.toolCallbacks(List.of())`。保留这条边界：局部探索在新的受控运行器中执行，不通过给最终解释节点挂全部工具来开启隐式循环。最终报告生成不应额外触发未经计划记录的查询。

P0 先验证原生挂载／必要投影、身份映射和探索循环，P1 接入计划图与业务持久化，P3 完成集成故障测试。Graph 专项验收包含独立步骤跨等待推进、END 后账本续接、状态投影、逻辑标识与物理主键区分、嵌套调用不重复持锁及投放专属配置；详见[Graph 验收矩阵](graph-adaptation.md#9-graph-专项验收)。原生 interrupt／resume 不作为未验证的隐含依赖。

## 4. 运行闭环

### 4.1 固定步骤和探索步骤共用调用入口

1. 服务端解析冻结的主体、范围、期间、执行器版本和输入引用。
2. 在调用前持久化逻辑 action 与请求摘要，分配稳定幂等键。
3. Tool／Skill 通过相同入口执行。所有同步／异步子查询有稳定 childCallId；每个真实 HTTP 派发重新核验运行资格，分页也不例外。
4. 校验真实结果，把完整数据放入 ArtifactStore；账本只记录状态、引用和短摘要。
5. 输出合同满足后，原子提交该步骤的具名输出及状态，外层消费者才能绑定使用。

模型提供参数不能覆盖 principal、actionId、白名单或幂等键。InputSet／Artifact 引用既校验合同，也校验当前权限；同一 session 并不自动授权全部历史数据。

统计服务已有按主体＋requestId 幂等的 POST，但本地 TTL 不能排除等锁时映射被清理。Agent 冻结 requestId 和正文，无 jobId 的未知提交只走 Admin→Analytics recover-existing；旧入口或合同不支持时明确阻断，禁止回退 submit。缺失／过期保持 SUBMISSION_UNRESOLVED，已知 jobId 继续原 status/page；requestId 全链统一不超过 96 字符。同步 READY 先耐久保存，B 等待时不得重 GET 已完成 A；首次同步响应丢失且无稳定快照时明确 READ_RESULT_UNKNOWN／新收集代次。

### 4.2 局部行动决策

保留 ReactAgent 标准工具调用协议；适配层把 tool_calls 与结构化最终候选输出归一化为 `CALL / COMPLETE / NEEDS_INPUT / REQUEST_REPLAN / NO_PROGRESS`。应用记录简短决策摘要、所用证据、待满足条件和需要执行的能力；不要求模型输出或持久化完整内部思维。

每一次 CALL 都需重新检查能力是否位于冻结策略允许范围、输入类型、对象与期间、操作参数及可用证据。能力候选不只靠名称相似；普通排名不能替代变化排名，边际分布不能替代联合下钻。

探索仅在本步骤合同内选择路径。COMPLETE 是候选决定，不是直接写入 SUCCEEDED；后端检查输出和证据。证据可以支持变化分布但不能证明原因时，保持假设和未满足条件，不能改写目标完成标准。

首期执行顺序保持串行。探索可以调用 Tool 和非探索 Skill；对 Skill 的递归依赖进行校验，阻止通过另一个 Skill 间接嵌套 ReAct。

P0 首先验证直接 ChatModel 与 ReactAgent 的兼容性，避免叠加已有 ToolCallAdvisor。关闭并行不保证每轮一个调用：首期 afterModel 在任何派发前拒绝多调用批次；ToolInterceptor 逐次检查推进资格，下一模型前的消息 Hook 负责让出。空／重复 toolCallId 在派发前拒绝；派发前保存其与 action／requestId 的映射。完整规则及唯一恢复消息策略见[Graph 消息配对](graph-adaptation.md#42-工具消息与异步恢复配对)。P3 只集成并验证这些已经确定的适配点。

### 4.3 暂停、停止与恢复

| 情况 | 状态／操作 | 恢复要求 |
| --- | --- | --- |
| 已提交统计任务尚未完成 | WAITING，保存 actionId、jobId 和查询摘要 | 续查原任务；不新建相同 CALL、不改变 revision |
| 原生超时但 callback／HTTP 未确认退出 | BLOCKED/EXECUTION_UNRESOLVED，撤销旧 attempt 的后续派发资格 | 恢复只对账原身份；领域账本阻止下一模型，晚到事实不能自行发布成功 |
| 本地／远端容量未受理请求 | BLOCKED/LOCAL_CAPACITY 或 REMOTE_CAPACITY，保留未提交 child | 可信退避条件后推进剩余项；不伪造 WAITING job、不让模型反复改计划 |
| 明确的临时调用失败 | 按策略重试原 action 的新 attempt | 逻辑幂等键不含 attemptId；避免响应丢失引发重复提交 |
| 缺少必要用户输入 | NEEDS_INPUT，报告具体缺项 | 新输入集和受校验计划版本，不覆盖旧输入 |
| 无合法下一步／无进展／运行策略边界 | BLOCKED 与明确 stopReason，保留证据和恢复点 | 按批准策略恢复；累计行动与消耗不能清零 |
| 用户取消 | CANCELLED | 晚到结果不能标记本任务成功；底层任务是否可取消以实际能力为准 |
| 合同已满足 | 后端验收并发布步骤输出 | GoalAssessor 另行判断整个用户目标是否回答 |

运行策略值在实现中可配置，并对较长任务支持分批推进和已有异步查询。不在方案里任意写死几步探索或少量分析卡片作为用户能力上限。耗尽或停止要明确暴露，不把截断后的结果当完整分析。

### 4.4 何时修改外层 Plan

在已批准范围内改变局部查询顺序、选择另一个允许的 Tool 或恢复等待任务，都不改变 Plan revision。需要新增后续依赖、调整执行器允许集合、改变范围／期间／输出合同，或者用户明确修改目标时，才进入外层评估并建立新版本。

`REQUEST_REPLAN` 必须附触发证据、未满足条件与预期补足项。外层检查是否确有新信息，拒绝在同样证据下反复生成等价计划；不允许以更换模式为由删除过滤条件、缩小候选对象或换指标。

计划版本切换使用当前 revision 的条件更新，旧版本失去推进权；完成 Artifact 保持原 producer，新版本显式引用。兼容的在途查询由服务端显式接管，检查范围、过滤、期间、快照、执行器合同及权限，继续使用原 action／任务身份；新 consumer 与原 producer 分开记录。只把 revision 拼入新幂等键并重新提交并不构成安全接管。

接管仅是 Agent 自有数据库中 revision 与消费关系的事务，不修改 Analytics worker lease，不新增远端 adopt。旧版本退休不得取消仍有有效消费者的 job；PENDING 是否兼容通过原 action 冻结请求判断，不能只看 status 或 jobId。

首期约束为单实例写入，但仍需 revision／状态 CAS 来隔离并发请求和旧回调。多实例上线前再补分布式租约与 fencing；本地 ReentrantLock 不作为分布式保证。

## 5. 结果与展示计划

外层 GoalAssessor 在成功、等待、失败和没有综合 Skill 时都运行。局部探索只完成步骤合同，不能直接决定整份报告“完整”。部分证据要带缺口，不因综合步骤被阻断就丢弃前面已经取得的结果。

验收以版本化 requirement 检查器为依据，区分数据、计算、交付与因果证据；Goal.acceptance 不充当自由文本程序。先对证据初评，再在真实报告草稿上终评 DELIVERY，每个 ANSWERED goal 都需要结果块或已授权可访问的完整结果入口；解释目标还需关联数据的分析。报告存在 goalIds 或后台数据已算完不足以通过。Schema 合法、图表齐全、引用正确也不能证明原因；必需条件未知保留 PARTIAL／缺口。

报告正文按用户问题组织可重复章节：指标／图表 → 分段解释 → 相关数据表 → 假设和反证 → 验证建议。保留完整分析，不按节点数量生成等量卡片，也不限制成固定几句话。

产品只需显示“正在比较两期”“正在验证设备分布”等业务进度；Plan、ActionLedger 与详细执行轨迹进入诊断入口，不让用户被编排术语打断。页面、历史、复制和导出使用同一报告版本与 Artifact 清单。

P1 定义查询快照与报告证据的生命周期，P5 发布前耐久保存报告实际使用的固定数据载荷。现有同步快照 5 分钟、job 24 小时的保留期不足以支撑历史复现。相同期间的不同查询也不是同一冻结数据版本，跨维度证据必须单独记录查询签名与可比性限制。

后续前端按已有木星品牌和通用块渲染，不能为每个 Skill 或提示词增加一张新页面。当前只定义合同与后端输出，视觉实现及浏览器验收单列后续任务。

## 6. 后端验证矩阵

各实现 PR 先运行影响范围内的后端单元／组件测试；使用脚本化模型、工具网关替身、内存存储和已有 H2 测试条件，禁止为本计划启动 Docker。H2／替身只验证相应合同和状态逻辑，不宣称已验证真实 MySQL 方言、统计队列或模型线上质量。

| 验证场景 | 必须断言的结果 |
| --- | --- |
| FIXED 缺 executor，REACT 缺策略，或两种模式字段冲突 | 工具调用前拒绝；不能自动猜测修正 |
| 同一单目标的完整 Skill、部分 Skill、无 Skill 三种配置 | 能力齐备则分别形成合法计划；未命中 Skill 不直接变成 UNSUPPORTED |
| 比较数据 → 根据返回证据选择不同查询 | 脚本化模型产生的实际调用顺序与证据对应；不能只断言最终文本 |
| 模型申请未知能力、超范围参数、错误 Artifact | 真实调用次数为零，不仅返回一条警告后照常执行 |
| 原生 Skill 激活额外工具，或恢复时包内容变化 | 未批准工具真实调用为零；固定版本不被自动重载替换 |
| 伪造 COMPLETE、缺失输出、覆盖不足 | 步骤不误成功，目标保留部分或缺口状态 |
| 异步等待、响应丢失、宕机后重复恢复 | 原提交次数、幂等键、jobId 和消费关系正确 |
| requestId 映射已被清理、同键不同正文 | 老 action 不创建新 job；正文冲突明确失败 |
| 一个工具内部提交多个任务 | 稳定子调用身份互不混淆，恢复只推进未完成部分 |
| 局部多轮查询与等待 | 同一外层 revision；action 有独立进度与审计 |
| 新 revision 接管兼容在途任务 | 不重复提交；范围不兼容时禁止接管 |
| 接管后旧 revision 退休或清理 | 不取消其他有效消费者依赖的 job，不修改 worker lease |
| adopt 与 cancel 双向竞争，cancel 回执丢失 | 同一 binding 的 CAS 只允许一方取得资格；REQUESTED 不重新开放 adopt |
| 旧回调、取消后的响应、checkpoint 落后 | 不覆盖当前状态；已提交输出不重跑 |
| 无进展与运行策略边界 | 明确停止原因、保留证据；恢复不重置累计记录 |
| 直接或间接嵌套探索 | 在编译或调用校验时拒绝 |
| 多轮大结果与重复来源元数据 | Graph 状态随步骤／引用增长，不因原始表格反复复制膨胀 |
| 首次 checkpoint、多调用批次、空／重复工具 ID | 每个 saver 写入都有界；非法批次真实调用为零；恢复消息配对完整 |
| 合法多调用拒绝后的修复、反复违规后重启 | 每个 ID 有唯一未执行响应；修复预算持久化；无孤立消息或额外调用 |
| 无 Content-Length、大 arguments、深层 JSON、超大错误体 | 在 HTTP／解析阶段受控处理，不等进入 state 后才裁剪 |
| 完整联合分布被写成已证实原因 | 不能使因果目标 ANSWERED；观察事实可发布，因果条件保持未满足 |
| 51 条以上候选、16 组合以外、10 页以外才有下降对象 | 全量找到或明确披露覆盖不足，不把 Top N 完成当候选全集完成 |
| 同期间换维度／过滤条件、查询间发生回填 | 不跨 query signature 复用分页快照；未证实同版本时披露可比性限制 |
| 一步工具、部分失败、综合步骤不可用 | 公共评估／报告层均能工作，未知与零、等待与完成不混淆 |
| 历史报告／导出 | 使用固定版本和范围，数据过期不偷偷换成当前查询 |
| 源 snapshot 过期、epoch 变化与报告留存／清理竞争 | 历史按本地耐久清单和当前权限读取；新分析仍走严格复用检查；不产生悬空引用 |
| 旧客户端未声明新状态协议 | 后端不将其导入新运行器；状态显示验收仍为前端待完成门槛 |
| Agent→旧 Admin／旧 Analytics 恢复、字段剥离、近过期 gate 竞争 | 结构化恢复失败而创建 INSERT 恒为零；首次合法 submit 正常 |
| 冻结 A/B 后新增 C、撤销 B、501 成员分片 | 各期精确 A/B；撤权阻断；分片并集／唯一性和证明一致，cohort UV 不求和 |
| A 同步 READY、B WAITING；A 落盘后 B ACK 前崩溃 | A GET 总计 1 且 checksum 不变；B submit 1，恢复只对账 B；失败项不抹掉等待项 |
| callback 忽略取消、旧 writer 与新恢复重叠 | 额外模型调用为零；尚未获资格的子请求为零；旧事实不得自动发布成功 |
| 首次同步 GET 响应丢失、冻结 page 读取超时 | 前者保留未知／显式新代次；后者按原快照恢复，不混合新旧证据 |
| 8 个保留完成任务后第 9 个查询；释放与消费竞争 | 容量拒绝明确未受理；耐久接收后受控释放，只推进剩余项；同 requestId 不重新创建 |
| 多 Run 慢模型／大结果与取消／超时 | 活跃推进、模型与解析峰值在配置内；真实 worker 未退出前许可不归还，等待仅短引用 |
| 两个目标均有证据，但删去第一个的表／完整结果入口 | 第一个不可 ANSWERED；恢复可访问结果后通过；第二个独立保留，不强加长文本 |

可复用的现有测试入口包括 `CampaignAnalysisPlannerTest`、`DefaultCampaignAnalysisGraphExecutorTest`、`CampaignStatisticsToolsTest`、`DimensionBreakdownToolTest`、`CampaignEvidenceContextTest`、`AgentToolRegistryTest`、`SpringToolContextAuthorizationTest`、`JdbcGraphCheckpointStoreTest`。新测试围绕行为与故障窗口建立，不仅断言 DTO 字段或复制实现分支。

最终后端回归还要确认风险 Agent 的工具授权、图配置和既有响应未受影响。真实数据库／统计链路和浏览器验收保持待验证标记，不以替身测试通过代替。

## 7. 兼容与交付门槛

- 新执行模式通过开始运行前的配置选择；同一个已提交任务不在失败后自动切到旧路径重做。
- 旧 Plan 示例默认 FIXED；新合同解析时明确版本和模式。生产 schema、运行器和数据迁移必须同一 PR 明确兼容范围。
- 新账本及 Artifact 记录采用增量数据库迁移，不覆写旧 Graph checkpoint 的格式或历史结果。
- P3 集成前，P0 的原生机制验证、P1 的幂等／等待恢复与 P2 的统一授权必须通过；对用户开启前还须完成客户端状态兼容。不能只调高工具循环上限当作上线。
- P4 的版本切换与接管验证通过后，才能让运行中的模型请求外层重规划。
- P5 的目标评估和报告校验通过后，才能对外宣称完整图文报告能力；数据缺口和未知原因必须保留。

当前继续完成 P1 的恢复协调与生产接线，再进入 P2 的固定范围与结果容量协议。P0 原生组件验证已经完成；新入口仍受客户端兼容和生产恢复门槛约束。验证仅使用必要的定向后端测试，不启动应用或 Docker；各批实际范围与结果见上述验收记录。

阶段交付需附 `风险编号 → 处理协议条款 → 测试名称 → 关键断言 → 实际结果`。设计、实现和验证状态分别记录；没有运行证据的风险保持待验证。P0 必须覆盖忽略取消的原生 callback 与多 Run 准入；P1 覆盖 Admin 全链恢复和同步子调用；P2 覆盖冻结分片、释放和幂等身份；P5 覆盖发布／清理、历史权限撤销和逐目标实际交付。
