# 投放分析 Agent：执行与产物合同附件

状态：架构草案，2026-09-19。本附件描述拟新增协议，不代表对应运行器、Skill、Artifact 服务已经实现。主方案见[架构设计](../campaign-agent-architecture-2026-09-19.md)，运行边界见[Graph 适配](graph-adaptation.md)。配套 [example-plan.json](example-plan.json) 为固定计划，[example-hybrid-plan.json](example-hybrid-plan.json) 为固定与局部探索混合计划；均不可直接提交给当前 Agent 执行。

两轮 R01–R19 的故障处理顺序、事务边界与安全恢复出口以[风险处理协议](risk-controls.md)细化；保持原生框架复用，不新增通用运行器。第二轮协议已经纳入本合同，仍待代码和运行验收。

## 1. 协议边界

统一层级为 `Conversation(sessionId) → Run(runId) → PlanSpec(planId, revision, goals, steps)`。

- Conversation 组织多轮交互；`sessionId` 不是用户身份或授权凭证。
- Run 表示一次受控分析执行。新分析不能因文字相同而自动命中上一轮统计快照。
- PlanSpec 是不可变的计划版本。一份计划可以编译为对话主图 `execute_plan` 节点执行的子图；每个 PlanStep 对应该版本中的一个 Node 实例。
- 外层 Plan 管理目标、依赖、授权边界和公开输出；必要的局部探索在一个 REACT 步骤内部进行。探索不是第三种业务执行器，也不能替代外层计划管理。
- 第一版按 `steps` 的拓扑顺序逐步执行。`dependsOn` 表达数据与控制依赖，不承诺并行执行；没有依赖关系的独立目标也可以顺序完成。
- 单目标也允许多步骤、多个 Skill 或纯 Tool 计划。是否复用 Skill 取决于合同覆盖，不取决于意图数量；场景未命中不能直接映射为目标 UNSUPPORTED。
- Node 是执行基础单元；鉴权、恢复、发布等普通基础设施 Node 不一定对应业务目标。业务 PlanStep 用 `goalIds` 声明服务哪些目标，不能把整个 Node 轨迹当成业务目标清单。
- 会话续问不向同一个 CompiledGraph 永久追加节点。运行状态与 checkpoint 按服务端身份、`runId`、`planId`、`revision` 隔离；跨轮只显式引用经过校验的上下文和 Artifact。

这是拟新增运行协议。现有固定五节点图与节点内工具循环不能直接充当动态 Plan Runner；WAITING 挂起/恢复、跨实例状态 CAS 与晚到回调隔离也需要专门实现。后端为各图层级生成稳定逻辑标识并保存关联，沿用 UUID 命名。MysqlSaver 将逻辑 threadId 写入 `thread_name VARCHAR(255)`，物理 `thread_id VARCHAR(36)` 由 saver 自行生成；不能把 36 字符当成逻辑标识的框架上限。

Graph 保存计划引用、Artifact 引用、步骤状态、简短进度与错误码。完整表格、原始访问记录、窗口 provenance、报告正文与运行审计存在后端；不得同时把相同正文复制到多个 state key。

## 2. Conversation、Run 与 PlanSpec

| 对象 / 字段 | 含义与约束 |
| --- | --- |
| `Conversation.sessionId` | 服务端认可的会话标识；访问必须绑定当前可信主体及租户。客户端持有标识不等于有权限。 |
| `Run.runId` / `sessionId` | 分析运行标识与所属会话。每个 Run 的身份上下文来自服务端认证，不由 Planner 或 LLM 填写。 |
| `Run.planRef` | `{planId, revision}`，指向当前获准执行的不可变计划版本。历史版本保留审计，不原地覆盖。 |
| `InputSet.inputSetRef` | 服务端生成的不可变输入集引用；每个 Plan revision 固定自己的输入集，旧版本不得读取后来修改的新输入。 |
| `InputSet.inputContracts` / `inputValues` | 具名输入端口的合同与已校验值。数据范围、期间等大对象优先传后端引用；不携带用户令牌、数据库凭据或任意存储地址。 |
| `PlanSpec.schemaVersion` | 本协议版本，例如 `campaign-plan/v1`；不等同于 Skill 版本或产物 schema 版本。 |
| `PlanSpec.planId` / `revision` / `runId` | 标识所属 Run 与计划修订。`revision` 为正整数且单调增加；已开始的步骤固定使用其所属版本。 |
| `PlanSpec.inputSetRef` | 指向该版本所属 Run 内已经校验的不可变 InputSet。澄清或变更产生新输入集及新 revision；查询进度保持原引用。 |
| `PlanSpec.goals` | GoalSpec 列表。每项含唯一 `goalId`、`question`、`required`、`acceptance`；描述用户要求，不存运行状态。可执行验收规则固定在该版本的 PlanningAssessment 中，不能仅由 acceptance 自由文本判定完成。 |
| `PlanSpec.steps` | PlanStep 列表；`stepId` 在本计划版本内唯一，依赖必须存在且无环。 |

重新规划必须创建新 revision，重新绑定受影响步骤的输入；旧节点回调不能更新新版本。补充当前 Run 缺少的必要信息，或明确修改尚未完成的原任务，在同 Run 保存新的不可变输入记录并建立新 revision，旧 revision 停止推进。已经完成后的新请求创建新 Run；只检查已提交任务状态的“继续”恢复原版本。用户换对象、期间或问题时不可只替换界面标签继续读取旧任务。

### 2.1 需求覆盖与能力选择

Planner 对每个目标拆出证据需求和约束，匹配 Skill／Tool 的输入、输出与适用范围。可先尝试成熟场景，但允许继续搜索、组合已有能力；一个单目标不受“一目标一个 Skill”的限制。

每个候选计划保存 `PlanningAssessment`，作为计划版本的附属记录，不新增第三种步骤执行器：

| 字段 | 用途 |
| --- | --- |
| `planId` / `revision` / `capabilityCatalogVersion` | 固定评估所依据的计划和已登记能力版本。 |
| `requirements` | 各项的 `requirementId`、`goalId`、所需事实／方法、对象／期间／过滤／粒度等约束；`kind=DATA\|CALCULATION\|DELIVERY\|CAUSAL_EVIDENCE`、`required`、`criterionRef`、`criterionVersion` 和类型化 `parameters` 固定可执行检查，不接受任意表达式。用户原意与 GoalSpec.acceptance 保持可追溯。 |
| `coverageBindings` | requirementId 对应哪些候选／选定 stepId，哪些公开输出和合同条款可以满足它。不能只填写“名称相似”。 |
| `gaps` | 尚未覆盖的 requirementId、缺失输入／能力／证据的原因，以及可行的补足方式；没有方案时也保留目标。 |
| `previousPlanRef` / `replanReason` / `evidenceArtifactIds` | 初次计划可为空；重新规划记录从何版本修改、为何修改、依据哪些新证据及预期补足哪些需求。 |

模型可提出需求拆解和候选绑定；后端校验可确定的合同、类型、范围和计算条件。该评估证明候选方案是否可执行，不证明自然语言意图理解绝对正确或原因已经成立；最终仍由 GoalAssessor 根据实际结果判定。

GoalAssessor 逐 requirement 记录 `MET / NOT_MET / UNKNOWN / NOT_APPLICABLE` 与证据；NOT_APPLICABLE 仅由登记规则及证据确认，不能由模型自行豁免。必需条件不满足或未知时不得升级 ANSWERED。DATA／CALCULATION 检查对象、期间、指标及真实计算，DELIVERY 在最终报告草稿上检查目标要求的数据／解释及可访问结果，不宣称证明任意自由文本为真。用户要求证实原因时必须保留 CAUSAL_EVIDENCE 条件；没有支持该方法的数据／能力，只能交付观察和假设并保留缺口，不因联合分布完整而视为因果证据。无法形成可执行验收规则的目标保留 PLANNING_UNRESOLVED。两个 JSON 附件均包含 planningAssessment 与 proposedCriteria：每份 6 个具名条件、参数 Schema 及证据端口绑定，包括两个目标各自的交付要求；检查器仍为 PROPOSED，不能单凭示例视为已登记或获得执行授权。

能力完整覆盖时可以构造单步计划；部分覆盖时只复用匹配的 Skill 公开输出，再补其他 Skill／Tool；没有合适 Skill 时可以生成纯 Tool 多步骤计划。必需需求必须由合法步骤覆盖，或以明确 gap 保留并反映到目标状态；不存在的执行器不是合法补位。缺输入的目标保留 NEEDS_INPUT，能力已知但查询证据不可用是 UNAVAILABLE，确认可用目录及组合缺少必要能力才是 UNSUPPORTED。有可用部分答案时总体为 PARTIAL，并保留具体缺口原因。

只有新证据、输入变化或明确的能力可用性变化才触发重新规划。临时调用失败按原步骤重试，异步 WAITING 按 jobId 续查；不能不断创建 revision 来重复提交。等价计划／相同冻结查询没有新信息时复用结果，未发现新路径则结束推进并报告缺口。替代方案必须保留原目标和冻结口径，不得删过滤、换指标、缩小集合后认定已完成；用户明确修改要求时另存输入集与计划版本。

规划失败与能力不足分开记录：没有生成合法组合但尚不能确认能力缺失时，使用 `reasonCode=PLANNING_UNRESOLVED`，无答案时 GoalAssessment 为 UNAVAILABLE，有可用部分时为 PARTIAL；不将一次检索或模型规划失败直接映射为 UNSUPPORTED。

## 3. PlanStep 与类型绑定

| 字段 | 合同 |
| --- | --- |
| `stepId` | 本版本内稳定、唯一的步骤标识。Node 实例身份为 `(runId, planId, revision, stepId)`；重试使用独立 `attemptId`。 |
| `goalIds` | 本步骤服务的 GoalSpec 标识列表；不得引用不存在的目标。基础设施步骤允许为空，但不能因此宣称业务目标已覆盖。 |
| `executionMode` | `FIXED` 或 `REACT`，缺省为 FIXED，以兼容旧固定计划示例。每一步只能选择一种模式。 |
| `executor` | 仅 FIXED 必需：`{kind: TOOL\|SKILL, name, version}`，必须在后端能力注册表中可用。REACT 不设置此字段，不伪造名为 explorer 的 Tool/Skill。 |
| `explorationPolicy` | 仅 REACT 必需，固定允许的局部执行器、范围、期间、完成条件和终止策略，见下文。FIXED 不设置此字段。 |
| `dependsOn` | 上游 stepId 列表。每个 STEP_OUTPUT 绑定的提供者必须是直接依赖；第一版依赖顺序必须先于当前步骤。 |
| `inputBindings` | 以具名输入端口为 key 的绑定对象；来源仅允许下表三种结构，不执行任意表达式、模板脚本或 JSONPath。 |
| `parameters` | FIXED 执行器或 REACT 已登记策略的输入 schema 允许的普通参数。不得用参数隐藏另一个 executor、SQL、URL、对象范围、快照或表达式以绕过 typed binding。 |
| `outputContractRef` | 注册的具名输出合同与版本。执行成功前必须验证所有必需输出端口、类型和产物 schema；不能由 LLM 猜测字段。 |

| 绑定类型 | 允许的形态 | 解析规则 |
| --- | --- | --- |
| INPUT | `{"source":"INPUT","input":"scopeRef"}` | 从当前 PlanSpec.inputSetRef 读取具名输入端口，按该端口合同校验类型和基数；禁止读取 Run 最新输入。 |
| STEP_OUTPUT | `{"source":"STEP_OUTPUT","stepId":"select-declines","output":"selectedEntities"}` | 读取同一 plan revision 中指定步骤的具名输出端口。数据输出解析为 ArtifactRef，不把表格或 ID 大集合复制进 Graph。 |
| ARTIFACT | `{"source":"ARTIFACT","artifactId":"ARTIFACT_PLACEHOLDER"}` | 从后端产物目录解析不可变 Artifact，重新鉴权并校验 schema、范围、期间、质量和有效期。 |

编译时检查端口存在、类型可赋值、schema 主版本兼容、单值或集合基数、必需性、依赖与环。未知 `source`、额外的 `expression` / `jsonPath` 字段、未知输出名均拒绝，不能降级为字符串求值。领域筛选条件只能使用注册 schema 的结构化参数，由指定 Tool/Skill 实现解析。

示例中的 `ScopeRef` / `PeriodsRef` 是由 `campaign-plan/v1` 固定的内置引用类型；每个拟新增 Executor 另有版本化 `inputContractRef` 和具名 `inputPorts`。所有 ArtifactRef 的正文类型显式携带 schema 版本。

ScopeRef 指向可信范围记录，`scopeKind=CURRENT_GROUP|FROZEN_SET`。跨期间／依赖分析固定使用 FROZEN_SET：保存主体、来源分组、不可变成员清单／memberHash、枚举完成证明与授权校验版本，Admin 对明确成员重新授权，不能在各期／恢复时重新 resolve 当前全组。新成员不纳入，撤销成员阻断相关读取／发布，不静默缩小范围。

超过单查询成员容量时保存确定性分片的 parentScopeRef、shardId、shardMemberHash，各期分片相同；返回 scopeProof 明确分片完整性与父集合覆盖。只有同范围证明才可验收，不能将原 groupScopeComplete 作为冻结子集／分片的通用判定。父 scope 完整性由分片并集、唯一性、成员 hash 与分页对账产生。逐链接指标可以拼接，跨分片 cohort UV／UIP 不可相加；需要独立去重查询而能力不足时保留 gap。

运行时再检查实际 Artifact 与编译类型匹配。上游端口可能引用空集合 Artifact；“引用存在”“集合非空”“集合完整”“质量可接受”是四个不同判断。`inputPolicies` 属于已登记的 Executor 或探索策略合同，不由临时 Planner 任意弱化。

### 3.1 固定步骤与探索步骤

FIXED Node 通过登记的 Tool/Skill 适配器执行确定的输入输出合同。REACT Node 适配器调用通用 **LocalExplorer**，在批准的策略内选择下一次局部动作；它仍是一项外层 PlanStep，有同样的 `stepId/goalIds/dependsOn/inputBindings/parameters/outputContractRef`。局部动作记录属于该 Node，不追加为外层 PlanStep，也不伪造外层 STEP_OUTPUT。

LocalExplorer 首期优先包装当前框架 ReactAgent，复用其模型／工具循环，通过 Hooks、Interceptors 和受控 ToolCallback 接入业务合同。LocalAction 是标准 tool_calls、结构化最终候选输出及服务端停止事件的归一化记录，不要求 ReactAgent 每次生成自创格式的 CALL 文本。一个新行动增加账本记录，不自动扩展外层 Graph 节点；框架循环停止也不自动等于该步骤完成。

| `explorationPolicy` 字段 | 约束 |
| --- | --- |
| `policyRef` / `policyVersion` | 注册表批准的策略及固定版本；客户端或模型提出候选，由服务端解析、校验并冻结。 |
| `allowedExecutors` | 有版本的 `{kind: TOOL\|SKILL,name,version}` 白名单；只允许已登记 Tool 和不启动探索的 Skill，禁止嵌套 ReAct、自调用或经 Skill 间接绕过白名单。 |
| `scopeRef` / `periodsRef` | 服务端冻结的授权上界，必须与步骤已解析输入及 plan revision 一致；模型不能扩大对象范围、时间范围或改变期间含义。 |
| `completionCriteria` | 由注册的条件引用及 schema 允许参数组成，由后端验收；不得接受任意表达式或仅凭模型判断“已完成”。 |
| `terminationPolicyRef` | 已登记且带版本的停止、恢复、重试、无进展和预算策略引用；策略批准方式与预算账本由服务端管理。 |

嵌入 PlanSpec 的策略字段是经服务端批准的实例化值，须与注册版本及其允许的收窄规则相符，不是可随意修改的内联授权。质量要求、维度范围、输出合同等同样被冻结；模型和工具返回文本无权修改它们。缺少领域 Skill 不阻断合法工具探索；只要策略允许、工具合同覆盖所需证据，可以通过 Tool 完成局部步骤。

第一版局部动作也顺序调度，至多推进一个未完成的 CALL。Skill 若包含内部固定 workflow，其传递调用必须在策略允许的能力闭包中；不能借一个白名单 Skill 启动另一个 LocalExplorer 或隐式增加范围。

### 3.2 LocalAction 与局部输入集

外层输入绑定在 REACT Node 启动前解析为服务端冻结的局部 InputSet，记录 `explorationInputSetRef`。它包含允许探索读取的输入端口和 ArtifactRef，不能把后续产生的结果悄悄覆盖为新的 INPUT。

| LocalAction 字段 | 约束 |
| --- | --- |
| `actionId` | 该局部执行中的稳定标识，归属 run/plan/revision/step；重放与 WAITING 恢复保留同一 actionId。 |
| `kind` | `CALL / COMPLETE / NEEDS_INPUT / REQUEST_REPLAN / NO_PROGRESS`。不是 Tool/Skill 的第三种 executor kind。 |
| `executor` | 仅 CALL 必需，必须逐项匹配允许的 kind/name/version；其他动作不配置 executor。 |
| `inputBindings` / `parameters` | CALL 按实际执行器输入合同绑定和校验。局部来源只允许 INPUT 或 ARTIFACT，见下文。 |
| `decisionSummary` / `evidenceArtifactIds` | 简短可审计的选择理由与证据引用；不要求或保存完整隐藏思维链。 |
| `outputBindings` | 仅 COMPLETE 使用，将外层约定的具名输出绑定到已经发布、经过校验的 Artifact；后端验收后才公开。 |

局部 INPUT 为 `{"source":"INPUT","input":"selectedEntities"}`，从 `explorationInputSetRef` 读取；局部 ARTIFACT 为 `{"source":"ARTIFACT","artifactId":"ARTIFACT_PLACEHOLDER"}`，只读取后端已发布且当前有权访问的产物。上一步 CALL 的 actionId 不是 ArtifactId，不能作为 STEP_OUTPUT 或虚构 ACTION_OUTPUT；需要使用其真正发布的 Artifact。外层 PlanStep 的三种绑定规则保持不变。

每次调用重新校验产物身份、权限、类型、范围、期间、到期时间和质量；“READY”必须由后端产物目录确认，不能由 Explorer 自述。模型只读取用途所需的短摘要和 opaque 引用，完整业务结果留在后端。超出冻结输入集或策略边界的额外输入必须通过外层重新规划批准。

### 3.3 动作持久化、停止与恢复

CALL 的派发顺序为：后端校验动作 → 原子保存 actionId、固定执行器版本、规范化输入摘要、幂等键、dispatchState 与累计预算记录 → 实际执行 → 幂等记录 job/Artifact 引用。执行服务必须识别同一幂等键。若请求已提交但响应尚未写回即崩溃，恢复先按该键对账找回原任务，不能因为本地没有 jobId 就直接重提。

提交结果不明且无 jobId 时，P1 在 Admin 与 Analytics 增加独立 recover-existing 入口，服务端强制 EXISTING_ONLY，复用原有 gate／事务和请求哈希：只找回同主体、requestId 和原 query hash 的有效身份，缺失／过期返回 REPLAY_UNAVAILABLE，禁止创建。首次提交保持现有 CREATE_OR_FIND；恢复模式不改变业务 query hash。Agent→Admin→Analytics 保留冻结范围和结构化错误，不因旧端点 404／405、丢字段或不支持协议回退 submit。协议不可用记 RECOVERY_PROTOCOL_UNAVAILABLE；两类未知恢复都保留 SUBMISSION_UNRESOLVED。客户端本地 TTL 不能消除服务 gate 与清理竞争。已知 jobId 继续原 status/page；已释放结果只返回原身份及 RESULT_RELEASED，不重建。

复合 Tool／Skill 的每个同步或异步子查询都有 childCallId、冻结请求/hash、阶段与派发状态、attempt、可空 jobId、snapshot/cursor 和可空 resultArtifactRef。同步 READY 先耐久保存再声明完成，恢复不能重 GET 已完成 A 来等待 B。父步骤将执行进度、待完成子任务、可用证据与失败列表分开汇总；INCOMPLETE 不得覆盖掉 pending jobs。同步响应未落盘且无法凭原 snapshot 对账时，保留 READ_RESULT_UNKNOWN 或显式新收集代次，不伪造 exactly-once。

未决超时撤销旧 attempt 后续派发资格并记 BLOCKED/EXECUTION_UNRESOLVED。每个真实子 HTTP 派发均检查运行与取消状态；不能只在 handler 入口检查。恢复者先对账原身份，原生下一模型 Hook 与外围 invoke 适配都依据领域账本决定停止，不能只依赖可能被框架超时清空的 state updates。取消／超时前已获资格的在途请求可能晚到，只记录原事实，不能重新开放取消的 Run 或未决下的新模型调用。

| 动作或停止原因 | Step 与 Goal 的处理 |
| --- | --- |
| COMPLETE | 仅为完成申请；后端验证 completionCriteria、公开输出合同、Artifact 与允许的质量后才能置 SUCCEEDED。验收不通过保留缺口，不发布完整结果。GoalAssessment 仍独立判定。 |
| 异步 CALL 未完成 | Step 为 WAITING，保持同 run/plan revision/actionId/jobId；恢复只读取该任务进度，不先让模型再产生同义 CALL。 |
| 工具超时、真实调用是否结束未知 | BLOCKED/EXECUTION_UNRESOLVED，沿原 child/request/job 身份对账；不让模型替换调用，不把 Future 完成当 callback 停止。 |
| 本地准入／远端容量不足且尚未受理 | BLOCKED/LOCAL_CAPACITY 或 REMOTE_CAPACITY，保留未提交 child 与退避条件；无 jobId 不标 WAITING，模型不得绕过容量改写计划。 |
| NEEDS_INPUT | Step 为 NEEDS_INPUT，保存缺少的具名信息；现有部分证据可由外层公共评估层发布。 |
| REQUEST_REPLAN | 只提交外层调整请求和证据，Step 以 BLOCKED/REPLAN_REQUESTED 等待校验；Explorer 无权自行改外层计划。 |
| NO_PROGRESS | 保存具体无进展原因、已经尝试的规范化动作和证据；Step 为 BLOCKED，不循环调用相同查询伪造推进。 |
| 预算耗尽 | 保存 BLOCKED/BUDGET_EXHAUSTED、累计账本、可恢复进度与部分 Artifact，不置 SUCCEEDED，也不补造成功答案；恢复必须满足服务端批准的策略。 |
| 取消或不可恢复执行错误 | 分别进入 CANCELLED 或 FAILED，保存已发布证据并让公共 GoalAssessor 说明未完成目标。 |

停止原因、步骤执行成功、目标回答程度是三种独立信息。局部步骤未成功也不能阻断已有部分结果与原因的对外呈现。NO_PROGRESS 的判据需识别实际分页推进、覆盖增加、异步状态变化等有效进展；相同输入和快照下证据没有增长才应阻止重复调用。

预算按服务端策略记录动作、资源与进度消耗，本合同不指定任意的小数值上限。同 Run 中的恢复和计划修订不能清零累计消耗；同一逻辑 action 重放/adopt 不重复记为新调用，实际新增尝试及消耗仍记入审计和相应预算。获准补充额度须留追加记录，不能由模型重置账本或用新 revision 绕过限制。

### 3.4 探索内推进与外层重新规划

在同一策略、冻结输入、外层依赖和输出合同内选择另一个允许的 Tool/Skill，是局部探索推进，不增加外层 revision。只有需要调整后续步骤依赖、外层执行器、授权范围、期间或公开输出合同时，才发 REQUEST_REPLAN；新证据引起的外层调整仍适用需求覆盖校验。

外层接受新 revision 时必须原子停止旧版本推进并记录状态版本，拒绝旧调度者、恢复请求及晚到回调直接发布新版本结果。在途任务仍可在原归属下完成并记录事实，但不能自动成为新 revision 的完成证据。

兼容在途任务通过服务端显式 `adoptJob` 接管等待与取结果：重新核对主体与当前授权、完整对象集合、queryKind、两个期间及冻结截止时间、metric/dimensions/filters、snapshot/recovery epoch、执行器与输出合同版本、TTL 及任务状态；尚未产生 snapshot 的任务按原不可变请求先核对，并在结果就绪时再次核验。取消、过期、失败或范围不符的任务不得 adopt。

新 revision 的激活与 adoption 关联以同一受控状态事务发布，保留原 plan/step/action/producer 和幂等键的来源关系。adopt 不改变原查询参数、不重写 Artifact.producer，也不重新提交同一查询；新 revision 不能仅因有新的 step/action 幂等键就重复提交仍在执行的等价任务。模型传入 jobId 不是 adopt 授权。

这里的事务仅覆盖 **Agent 自有数据库的 revision 与 consumer relation**。adoptJob 是本地业务操作，不新增远端 adopt API，不迁移 Analytics worker 的所有权或 lease；复用现有任务执行与 fencing。PENDING status 不含完整查询指纹，匹配依据必须来自可信原 action ledger 的冻结请求。旧 revision 退休只撤销其消费／推进资格；取消物理 job 前检查其他有效 consumer 及明确取消意图，避免终止新版本仍依赖的任务。

上述检查必须与 adopt 竞争同一 job binding 的 version／CAS。取消在消费者为零的事务内写 `cancelIntent=REQUESTED`，再于事务外调用远端；一旦请求取消即拒绝新 adopt，响应丢失也不清空意图。仅先读取消费者计数再发远端 cancel 不满足合同。

## 4. 执行状态、目标结论与数据质量

步骤状态使用 `PENDING / RUNNING / WAITING / NEEDS_INPUT / SUCCEEDED / FAILED / BLOCKED / SKIPPED / CANCELLED`。

| 步骤状态 | 语义 |
| --- | --- |
| PENDING / RUNNING | 已编排待执行（含正常等待上游调度） / 正在执行当前 attempt。 |
| WAITING | 已受理的异步任务等待结果；保存服务端任务引用，单次恢复只检查状态，不在图节点内忙轮询。 |
| NEEDS_INPUT | 当前步骤缺少必须由用户补充的输入。 |
| SUCCEEDED | 固定执行器或局部探索节点已通过后端验收并按输出合同发布产物；不保证数据完整，也不自动表示业务目标已回答。 |
| FAILED | 当前执行失败且没有满足合同的可用输出。重试条件独立记录，不能补造空表或零值。 |
| BLOCKED | 上游 WAITING、失败、缺输入或质量策略造成依赖阻断，或局部无进展、策略边界、等待外层重新规划而不能推进；记录明确的 reason/stopReason。 |
| SKIPPED | 按明确的计划规则无需执行，必须记录 `reasonCode`；不能用来隐藏失败或静默忽略目标。 |
| CANCELLED | 当前步骤被取消；晚到的 attempt 回调不得发布为当前成功结果。 |

生命周期通过显式事件推进。完成步骤的重试、恢复、替换不能回写成未开始；新增 attempt 与状态转移均留审计。`tool.success` 只表示当前工具调用结果，不可直接映射成 GoalAssessment 的 ANSWERED。

GoalAssessment 与 GoalSpec 分离，字段为 `goalId`、`status`、`reasonCode`、`evidenceArtifactIds`、`limitations`。状态使用 `PENDING / ANSWERED / PARTIAL / NEEDS_INPUT / UNSUPPORTED / UNAVAILABLE / CANCELLED`：

- `ANSWERED`：满足该目标的验收条件，有明确范围、期间和证据；不意味着所有数据都是精确值。
- `PARTIAL`：有可用结论，但覆盖、质量、缺失维度或未完成步骤限制了目标满足程度。
- `NEEDS_INPUT` / `UNSUPPORTED`：分别表示必须补充输入 / 当前已注册能力无法满足；不能执行不存在的 Skill。
- `UNAVAILABLE`：所需证据因工具失败、数据不可用等原因无法取得，且没有足以支持部分答案的证据。
- `PENDING` / `CANCELLED`：目标尚未完成 / 已取消且未形成可交付结论。

ReportSection 统一使用 `goalIds` 和 `evidenceArtifactIds` 建立目标—结论—证据映射。公共发布器先组装草稿，再按真实 section/block、固定证据 manifest 和授权读取器验收 DELIVERY，最后与报告一起发布 GoalAssessment。每个 ANSWERED goal 必须绑定可用结果块或可访问的完整结果入口；仅有 goalIds／内部 Artifact 不算交付。需要解释的目标同时检查分段分析与数据引用；清单目标无需额外大段文字，NO_DECLINES 可用有证据的空结果说明交付。任意省略 DELIVERY requirement 也不能绕过这项公共约束。失败只影响对应目标，不能丢掉独立有效结果；Skill／LLM 无权发布权威完成状态。

Artifact 的 `quality` 单独描述 availability、completeness、freshness、各指标 approximation、维度覆盖与限制。`resultComplete` 是产物合同中“请求结果是否完整收集/分页完成”的标志，不代替采集完整性、对象扫描覆盖或目标状态。例如：结果分页已读完可以 `resultComplete=true`，同时 `quality.completeness=PARTIAL`。

下降筛选产物还必须声明 `selectionComplete` 与 `coverage`：请求/已扫描/缺失对象和期间、选中数量、不可比数量、缺失原因。质量缺失保持 UNKNOWN；后端未知扩展保留在产物或标明不能解释，不静默变成 COMPLETE。

## 5. Artifact 与复用合同

| 字段 | 服务端职责 |
| --- | --- |
| `artifactId` / `type` / `schemaVersion` | 后端生成不可变产物标识，登记具体类型和正文 schema。 |
| `producer` | `{runId, planId, revision, stepId, executorVersion}`，保留本次实际生产版本；复用不能改写原 producer。 |
| `scopeRef` | 指向服务端冻结的对象范围与授权证明；绑定可信 tenant、主体、authVersion、成员集合及其 hash。 |
| `periodsRef` | 指向冻结的期间定义：baseline/target、时区与起止边界。自然日期在 Run 受理时解析，恢复时不随“今天”漂移；不包含可跨查询通用的 snapshot。 |
| `querySnapshot` | 每个实际查询的 query signature、snapshotId、观测截止时间及可取得的数据版本；无法取得的字段明确 UNKNOWN。同期间不同维度查询不保证同一冻结数据底座。 |
| `quality` | 后端判定和传播的数据质量；LLM 只能引用、解释，不能自行升级。 |
| `expiresAt` / `reuseExpiresAt` | 新分析复用有效期；expiresAt 是旧名称的兼容字段，二者同时存在时必须一致。正文尚在存储中不代表可用于新分析。 |
| `retainedUntil` | 载荷留存期限；报告引用需提供保留保护。历史查看按固定报告版本及当前权限判断，不依赖原统计快照仍有效。 |
| `locationRef` | 后端 opaque 存储定位符。客户端、Planner、LLM 不得指定 URL、SQL、文件路径或持有存储凭据；不进入模型上下文。 |

上述元数据及正文由后端管理。Graph 只保存 `artifactId/type/schemaVersion` 等短引用及状态；需要摘要时通过受控 Artifact Reader 按用途读取。产物中另存 checksum、输入 Artifact 引用、快照与完整 provenance，审计可追溯，Graph 不承载这些大对象。

现有 snapshot 冻结特定查询的分页结果，不能把父步骤的 snapshotId 填入另一维度／过滤条件的新查询，也不能借相同期间声称使用同一冻结底座。跨查询比较按实际数据版本、观测截止及回填情况判断可比性；无法核实时保留限制。未来若要求共同数据版本，需要扩展统计服务的共享 manifest 能力，不由 Planner 虚构一个快照实现。

报告发布前，持久化本报告实际呈现的固定指标、图表／表格载荷、解释与最小证据清单，并在一个发布事务中关联 report revision；已有不可变耐久 Artifact 可直接引用，不重复存整份原始访问数据。当前同步快照仅保留 5 分钟，异步 job 保留 24 小时，不能仅依赖它们维持历史报告与导出。报告审计存留与新分析复用有效期分开：已过复用期的历史数据可依产品留存及权限策略用于原报告查看，不自动用于新结论；载荷不可用时明确说明，不偷偷重查。

局部 CALL 产物的 `producer.stepId` 指向所属外层 REACT 步骤，`executorVersion` 记录实际 Tool/Skill 版本，额外审计 lineage 记录 actionId 与策略版本。COMPLETE 复用这些 ArtifactRef 时保留原 producer；若需由公共发布器汇编新产物，登记发布器真实版本及输入 lineage，不伪造一个外层 executor。

同一会话或同一租户不能自动授权读取。`ANALYSIS_REUSE` 校验当前对象权限、scope 成员、授权版本、schema、期间、snapshot/recovery epoch、复用期限和质量；不得把单链快照挂到全组，成员变化也不能冒充当前完整范围。跨 Run 复用记录消费关系；“重新分析”默认取得新数据快照，不用 query hash 永久复用旧任务。`HISTORY_VIEW/EXPORT` 则检查固定 report revision、本地耐久 EvidenceManifest、checksum、留存期与当前对归档对象的权限，不要求已过期的源 job／snapshot 可读或当前 authVersion 与历史字面相等。两种用途都拒绝权限撤销，不能用历史读取绕过新分析复用检查。

大集合推进将远端活动执行、保留结果与轻量恢复身份分别计量。仅在所需结果已耐久接收且有效消费者均可读取本地载荷后，按 job binding 锁／CAS 登记 releaseIntent，再调用受控 release-result；仅释放新协议终态任务页与结果名额，保留原 requestId、queryHash、冻结请求、jobId、主体及原保留期限。结果状态 RELEASED 与任务 SUCCEEDED 分开；Admin 不可再仅凭 SUCCEEDED 推断 resultReady=true。丢回执先对账，重复释放幂等，远端页不可读不重新提交。未受理容量拒绝返回 QUERY_CAPACITY_EXHAUSTED/capacityKind，保存进度并退避；恢复身份元数据仍有独立高容量配置与到期清理，不承诺无限存储。

前后期间的成员集合如何对齐必须在筛选合同中固定。对象缺失、尚未创建、无采集数据与真实零访问不能混为一类。只有完整统计合同证明某桶存在且为零，才允许使用 0；否则标记缺失/不可比。

## 6. 三个拟新增 Skill 的输出合同

这三个 Skill 是拟议业务组合，不是复杂分析的必经路径。方法包复用原生 SkillRegistry／SkillsAgentHook；可执行组合另登记类型化业务适配器、输入／输出合同及测试，用普通代码或 StateGraph 实现，不建设新的 Skill 加载器、通用 Runner 或 workflow.json 语言。仅放置 SKILL.md 不会自动获得确定性执行能力。后端锁定业务执行器与方法包版本、传递工具能力、输入策略及产物合同；未注册仅否定该候选，继续评估其他 Skill／Tool 组合。内部实际调用也需可恢复、有审计并受相同权限限制，详见[框架复用清单](framework-reuse.md)。

| 拟新增 Skill | 输入 | 具名输出端口及正文要求 |
| --- | --- | --- |
| `decline_selection` | `scope: ScopeRef`、`periods: PeriodsRef`；metric、排序口径等普通参数 | `selectedEntities: ArtifactRef<campaign.selected-entities/v1>`：确切对象集合、selectionComplete、coverage、emptyReason；`selectionEvidence: ArtifactRef<campaign.decline-evidence/v1>`：每个对象的 baseline/target、delta、rate、可比性、排除原因、质量及来源。 |
| `dimension_change` | 以上 selectedEntities 与 selectionEvidence，原 scope/periods；联合 dimensions | `dimensionChanges: ArtifactRef<campaign.dimension-changes/v1>`：继承确切入选集合，逐对象或明确标识的同一 cohort 在两期间的真实联合维度统计、变化、PV 分母、整窗独立 UV/UIP、覆盖和限制。 |
| `evidence_synthesis` | selectionEvidence、dimensionChanges，原 scope/periods | `analysis: ArtifactRef<campaign.evidence-analysis/v1>`：章节建议、goalIds/evidenceArtifactIds、分段解读、假设与限制；不是已发布报告，不包含模型决定的目标完成状态。 |

所有执行器另接收后端提供的可信 `ExecutionContext`。其中 `goalSpecs` 从冻结计划按当前步骤的 `goalIds` 读取，让综合步骤理解原始问题与完成条件；身份、任务状态和完成条件均不能由普通模型参数覆盖。最终 GoalAssessment 和 ReportRevision 由公共评估／报告组装层生成。外层 GoalAssessor 在成功、等待、失败及无综合 Skill 时都运行；综合步骤被阻断不能阻断部分结果与未完成原因的发布。

`decline_selection` 必须先完整读取两个冻结期间的对象指标，按同一对象身份对齐，再算 `delta=target-baseline` 并筛选 `delta<0`。示例按 PV 绝对下降量排序，保留所有符合条件的对象；不把当前按整窗 PV/UV/UIP 排序的 `rank_short_links` 包装成“变化排名”。`rate=(target-baseline)/baseline`；基期为 0 时 rate 为 null，并保留原因。期间未结束、长度不同、同对象期间重叠、指标版本不一致等必须进入可比性与限制判断。

P2 实施门槛是“完整冻结候选集合／分片证明＋两期 LINK_METRICS 全页耐久读取＋按对象对齐计算＋受控结果释放和容量恢复”，复用既有统计 job 与分页服务。当前排名只返回最多 Top 50，比较最多 16 个对象／期间组合，单次收集最多 10 页，远端完成 job 仍占保留名额；不能以 ranking.resultComplete 代替 selectionComplete，也不能认为串行完成即可无限腾位。超出单次推进容量时记录覆盖和恢复条件；不足以全量时允许部分结果，禁止报告已找到全部下降对象。上述数据能力落实后，才将示例 Skill 标为可用。

`dimension_change` 所需“对动态选出的短链集合做两期间联合维度变化分析”同样是拟新增编排能力。现有单对象的统计/下钻工具可以作为经过适配的底层能力，但不得假称已经支持该集合合同。省份 × 设备要求真实联合分组，不可拼接两个边际分布冒充交叉证据；UNKNOWN 与 NOT_APPLICABLE 保持区别。PV 占比分母说明所属对象/cohort及完整期间，UV/UIP 跨对象、桶或天不能相加；若需 cohort 去重值必须独立查询。

### 上游部分结果与空集合

| 上游情况 | 下游与目标处理 |
| --- | --- |
| 完整扫描、两个期间可比、入选集合为空 | selection Artifact 明示 `emptyReason=NO_DECLINES`。dimension_change 不查询统计，发布 `evidenceDisposition=NOT_APPLICABLE` 的可追溯产物；该步骤可 SUCCEEDED，报告明确“没有符合条件的下降短链”，不能写成“地域/设备没有变化”。两个目标可据此 ANSWERED。 |
| 只扫描了部分对象，但有已确认下降对象 | 仅在 Executor 合同允许时分析该显式子集，传播 coverage/质量限制；不能声称已找全下降对象，相关目标至少 PARTIAL。 |
| 部分/缺失/不可比造成空集合 | 明示 `emptyReason=INSUFFICIENT_EVIDENCE`，不能解释为 NO_DECLINES。dimension_change 不发查询，发布“证据不足”的有限产物供汇总；有部分证据则目标 PARTIAL，无可用证据则 UNAVAILABLE。 |
| 上游 WAITING / NEEDS_INPUT / FAILED，没有符合合同的 Artifact | 必需消费者保持 BLOCKED，不能绑定一个伪造空集合继续执行；最终目标分别保持 PENDING / NEEDS_INPUT / UNAVAILABLE，或按已有证据判定 PARTIAL。 |

示例采用“显式发布不适用/证据不足产物”的 Skill 合同，因此 evidence_synthesis 仍能消费已完成的具名端口。其他流程若选择 SKIPPED，必须声明可选端口与跳过传播策略；不能让依赖必需输出的步骤自动越过 SKIPPED。

## 7. 版本、幂等与发布

- Plan schema、executor.version、输入/输出合同版本、Artifact.schemaVersion 分别锁定；运行时解析到的 Skill 包内容必须与注册版本对应。版本不兼容时拒绝或使用已注册的显式适配器，不自动重命名字段。
- 编译器验证目标需求由合法步骤覆盖或以 gap 明确保留，按模式检查固定执行器或探索策略及其白名单可用性、端口类型、参数 schema 与 DAG；运行器验证实际输入范围、权限、产物质量与输出 schema；报告发布器验证 GoalAssessment 和 ReportSection 的证据引用。带 gap 的部分计划可执行独立、已满足条件的步骤，但不能宣称所有目标都有完整执行路径。
- HTTP 请求重试通过服务端主体、会话及客户端请求幂等标识找回原 Run；相同幂等标识对应不同请求内容必须拒绝。
- FIXED 步骤幂等键包含 `runId + planId + revision + stepId + executorVersion + 规范化参数/输入摘要 + 输入 Artifact/snapshot 摘要 + 授权范围版本`；REACT 的 CALL 另固定 actionId 与策略版本，并按同样的输入、权限和执行器信息生成调用幂等键。`attemptId` 不进入逻辑幂等键。新 revision 派发前先核对已有等价任务，兼容者按 adoptJob 接管，不能用新键绕过在途任务去重。
- 下游绑定冻结 Artifact 版本；异步任务引用必须与这些范围、期间、过滤条件及 producer 绑定，不能接受只凭 jobId 的结果替换。
- 完成发布采用原子状态更新：先验证 Artifact 可读和 schema，再公布具名输出和 SUCCEEDED。重复/晚到回调依据版本与 attempt 校验去重；取消或过期 revision 不能覆盖现行状态。

## 8. 示例与静态验收

示例目标为“找出本期相比上期下降的短链，再分析这些短链的地域和设备变化”。所有业务标识与引用值均为占位符；期间边界由 `PERIODS_REF_PLACEHOLDER` 所引用的服务端记录提供，不在示例中伪造时间快照。`proposedExecutors` 与 `outputContracts` 是说明示例所需注册能力的内嵌目录，不代表客户端可以注册任意执行器。

可用 Node 或 Python 标准库检查：JSON 可解析、steps 为预期三个步骤、所有 stepId/goalId 唯一、依赖存在且无环、每个 STEP_OUTPUT 引用存在的输出端口、端口类型匹配、INPUT 命名存在、目标被步骤覆盖、执行器与输出合同对应。另检查每个目标的 DELIVERY requirement、检查器版本／参数约束和实际证据端口。reportAssemblyContract.goalDeliveryPolicy 是服务端登记合同的示例，最终 report draft、manifest 和授权读取上下文由发布器注入，不能作为模型可调整参数。静态通过只证明示例自洽，不证明 Skill 已实现、运行授权有效或业务结论正确。

混合示例为固定下降比较 → 局部维度探索 → 固定证据综合，两种模式共用外层输出绑定。REACT 不带 executor，白名单同时展示拟新增 Tool 和非探索 Skill。内嵌 `proposedPolicies`、`proposedTerminationPolicies`、`illustrativeLocalInputSet` 与 `illustrativeLocalActions` 仅说明待实现注册合同和一种可能的局部路径，不是硬编码的必经工具顺序，也不表示已经执行；其 Artifact 类型目录仅供静态校验，不能替代真实后端 READY/权限判断。

混合示例另检查：FIXED/REACT 字段互斥、策略版本与冻结范围匹配、白名单指向登记能力、局部动作仅 INPUT/ARTIFACT、局部端口类型与 COMPLETE 输出类型匹配、停止策略存在。旧示例缺省 executionMode 的行为保持 FIXED。

## 9. 实现期配置与策略

1. 首期用现有 MySQL 保存 Artifact 元数据，数据优先引用冻结统计结果；必要的派生载荷持久化在 Agent 自有结果表。复用到期时间不得晚于依赖数据的可读期限；报告审计保留与分析复用期限分别配置，具体时长在实现时对齐现有快照保留规则。
2. 对象集合较大时 dimension_change 按固定对象／分片持久化所有同步与异步 child 进度。已受理任务报告 WAITING，尚未受理的容量拒绝报告 BLOCKED 与可恢复条件；不能静默截断后声明完整。P2 启用前通过 Admin 全链路冻结范围、分账和 release-result 合同。
3. 下降筛选默认要求相同指标定义、可比期间和足够覆盖。期间未结束、长度不同、指标近似或其他质量限制不允许被隐藏；有影响时区分“观测值差异”与“可比较的变化结论”。示例只承诺有证据的观测 PV 变化，不承诺因果归因；具体可比性策略需写成可测试的 Executor 合同。
4. LocalExplorer 的具体模型适配、可调用能力闭包、完成检查器及无进展判据需在实现 PR 固定为可测试的注册合同；“有领域 Skill 才能探索”不是前置条件。
5. 终止策略的具体配额维度、默认额度、补充授权与恢复窗口仍需按真实业务成本确定；本方案只固定累计、不重置、不伪造成功的行为。
6. adoptJob 的查询指纹、任务对账与状态事务需要和各异步服务的幂等接口对齐；无法证明同一请求时不能靠模型猜测接管。
7. 进程级模型／大结果准入复用有界执行器与许可，排队只留轻量引用；上限按实际堆和并发峰值设较高可配置值，不删减分析内容。包装 Future 超时不提前释放仍在运行的 worker 许可；真实结束后回收。多 Run、取消、超时及共享风险 Agent 的影响列入 P0／P3 容量验收。
