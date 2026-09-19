# 投放分析 Agent 架构计划风险审查

日期：2026-09-19。审查基线：应用代码 `6c93bd9`、本地 Spring AI Alibaba `1.1.2.3`、本目录方案及示例。方法：源码、JAR API／字节码、文档与合同交叉审查；包含三个独立只读审查方向：框架恢复、统计任务／数据生命周期、目标完成与报告兼容。

**结论：架构可行，优先复用框架的方向成立；目前发现 8 项 P1 和 4 项 P2，需要在实施中验证关闭。** 没有足够证据认定新的 P0，也不能据静态审查声称不存在其他故障。本轮已将防护要求写回方案，未修复业务源码，未运行 Maven、真实模型／数据库测试、Docker 或浏览器。

本文为设计审查快照。后续实现与实际测试单独记录在 [P0 后端验证](../../integration/campaign-plan-p0-2026-09-19.md)，不回写为本次静态审查已经运行测试。

P1 表示相关能力对外开启前必须关闭；P2 表示对应阶段的交付门槛，不能因级别较低而省略。下表“阶段 P0”是实施计划阶段名称，与风险严重性 P0 不同。全部风险独立记录为：**designStatus=SPECIFIED、implementationStatus=NOT_STARTED、verificationStatus=NOT_RUN**。风险级别未因补充文档而降低。

关联：[实施计划](implementation-plan.md)、[框架复用清单](framework-reuse.md)、[Graph 适配](graph-adaptation.md)、[业务合同](contracts.md)。

具体处理规则集中在[风险处理协议](risk-controls.md)。本报告保留发现与证据，协议负责规定异常后的行为，实施计划负责阶段验收，避免三处分别维护不同的恢复规则。

后续审查：[第二轮风险发现](risk-review-round2.md)，新增 R13–R19 共 7 项，包含 3 项 P1 与 4 项 P2；本文件的 12 项统计仅指第一轮，未因第二轮审查自动关闭。

## 1. 风险与责任边界

| 编号 | 级别 | 问题 | 性质 | 负责模块／关闭阶段 |
| --- | --- | --- | --- | --- |
| R01 | P1 | WAITING 不能只在下一轮模型前检查 | 框架语义与方案适配缺口 | runtime／P0 验证、P3 集成 |
| R02 | P1 | 空／重复 tool_call_id 造成消息配对歧义 | 现有源码问题 | infrastructure/llm／P0 |
| R03 | P1 | 业务账本与工具消息恢复缺少统一配对策略 | 方案缺口 | runtime／P0、P1、P3 |
| R04 | P1 | 大结果在裁剪前已进入 checkpoint | 框架语义与方案时序缺口 | runtime、artifact／P0、P3 |
| R05 | P1 | 提交丢响应会失去原任务；幂等映射会过期 | 现有链路问题 | tool、runtime／P1 |
| R06 | P2 | 新旧版本共享 job 时误取消或误迁移所有权 | 方案缺口 | runtime、persistence／P4 |
| R07 | P2 | 短期结果引用不足以复现历史报告 | 方案缺口 | artifact、report／P1 定义、P5 验收 |
| R08 | P2 | 将同期间误认为跨查询的同一冻结版本 | 方案语义缺口 | artifact、业务计算／P2 |
| R09 | P1 | Top N／有界分页无法证明找到全部下降对象 | 现有能力与目标合同不匹配 | tool、skills／P2 |
| R10 | P1 | 产物齐全不等于因果目标已回答 | 方案判定边界缺口 | planning、report／P0 定义、P5 验收 |
| R11 | P1 | 新后端部分状态被旧页面标成完整结果 | 现有前端与发布计划不兼容 | API 协议、前端／公开入口启用前 |
| R12 | P2 | 太晚验证框架适配，导致重复建设 | 实施顺序与选型风险 | graph、skills／P0、P2 |

风险到协议的对应关系：R01–R04 → 第 1 节；R05 → 第 2 节；R06 → 第 3 节；R07–R09 → 第 4 节；R10–R11 → 第 5 节；R12 及所有关闭证据 → 第 6 节。每项均需记录实现提交、实际测试与剩余边界，不能只勾选“已处理”。

## 2. 逐项发现与验收

### R01：PENDING 不会自动阻断同批工具调用

**证据与触发：**本地 JAR 的 AgentToolNode 顺序模式仍遍历一次模型输出的整个 toolCalls 集合。若 A 返回 PENDING，单纯在下一次 beforeModel 检查，不能阻止 B 已被执行；parallelToolExecution(false) 不能解决这个问题。AsyncToolCallback 的 Future 也不等于可跨进程恢复的统计任务。

**修订：**复用 afterModel 在派发前拒绝多调用批次；ID 合法时为每个调用生成唯一的 `BATCH_REJECTED/executed=false` 标准响应，再进入受预算约束的原生修复跳转，避免未配对消息。ToolInterceptor 逐次核查资格；下一模型前消息 Hook 结束等待推进。每轮一个调用不限制任务的目标／对象数量；用 invoke 读取暂停状态，账本或 Hook 故障不能转换成普通工具错误后继续执行。

**验收：**整批多调用时真实调用数为 0；另注入已等待状态验证后续工具与模型调用数均为 0。必须以当前原生扩展点的实际执行顺序证明，失败时禁止开启探索。见[Graph 门槛](graph-adaptation.md#41-reactagent-接入的专项门槛)。

### R02：现有模型适配器可能生成重复的工具 ID

**证据与触发：**[DeepSeekSpringAiChatModel.java](../../../services/agent-service/src/main/java/com/jupiter/shortlink/agent/infrastructure/llm/DeepSeekSpringAiChatModel.java) 第 300 行附近把缺失 ID 补为同一个 `tool-call`，也未拒绝同批重复 ID。原生 AgentToolNode 的 partial-response 处理按已响应 ID 集合判断剩余调用；两个调用同 ID 时无法唯一配对。这是源码可确认的问题，不代表已观测到生产事故。

**修订：**在模型适配边界拒绝空／重复 ID，不猜测或统一补成同一个值。框架标准 ToolResponse 配对继续复用；actionId 解决业务幂等，不能修复消息层歧义。

**验收：**空 ID、同批重复 ID、重复响应在真正派发前失败；合法 ID 序列化往返不变。该修复需回归共享模型适配器对风险 Agent 的影响。

### R03：WAITING 恢复必须同时对账消息身份和业务身份

**触发与影响：**PENDING 已作为 ToolResponse 入 checkpoint，任务完成后又追加同 ID 的最终响应，或只恢复一个孤立 ToolResponse；会产生非法对话或误跳过调用。仅保存 actionId／jobId 不够，原生消息恢复不理解本项目的业务账本。

**修订：**派发前持久化 invocation／assistant message／toolCallId 与 action／requestId／请求摘要映射；取得或幂等找回 jobId 后以条件更新补齐映射，再发布 PENDING 响应。唯一策略为保留一次原调用与 PENDING 响应，READY 作为新的受信观察引用；使用原生消息 Hook 的 REPLACE 生成规范上下文，不向原 ID 追加第二个响应。见[配对合同](graph-adaptation.md#42-工具消息与异步恢复配对)。

**验收：**在远端已提交、领域账本已更新、checkpoint 已写入三个边界分别中断；重启后只有一个统计提交，下一模型请求无孤立／重复工具响应，已完成结果不会重新查询。

### R04：裁剪必须早于首次持久化

**证据与触发：**ReactAgent messages 使用 AppendStrategy；asNode 的 includeContents=false 只移除父 messages，其他父 state 仍可能传入；returnReasoningContents=false 不会清理子 checkpoint。若大工具结果先入 state，下一模型前才裁剪，saver 可能已经保存大载荷；最终 state 看似很小不能证明中间没有内存峰值。

**修订：**保护前移到现有 HTTP 客户端响应体／错误体读取及 JSON 解析，再逐页落 Artifact；不能等拦截器收到完整 DTO 后才压缩。在 ToolResponse 返回原生框架前转换成有界事实摘要和引用；消息 Hook 管理配对与历史替换。父子状态仅投影允许字段，原生挂载满足条件时不额外加重型包装。

**验收：**检查每一个 saver 写入的序列化体积，注入大载荷与父 state 哨兵；首次 checkpoint 已无完整大表，重复恢复不重复追加。阈值按真实合同与容量测试确定，不以静默截断分析内容换取通过。

### R05：复用现有幂等提交，但补齐稳定请求身份和过期边界

**证据与触发：**[CampaignStatisticsTools.java](../../../services/agent-service/src/main/java/com/jupiter/shortlink/agent/tool/shortlink/CampaignStatisticsTools.java) 第 232 行每次生成新 requestId。[QueryJobService.java](../../../services/shortlink-analytics-api/src/main/java/com/jupiter/shortlink/analytics/api/job/QueryJobService.java) 第 79 行起已支持主体＋requestId＋请求正文校验，同键同正文返回原任务、不同正文冲突；第 21 行与第 687 行后的清理表明保留期为 24 小时。若提交成功但 Agent 丢失响应，重新调用使用新 ID 可创建新 job；映射过期后，即便同 ID 也不再保证找回旧任务。

**新增竞态证据：**submit 查映射／创建与 cleanup 使用同一 gate；请求可以在本地截止前发出，等清理先完成才取得锁。此时默认 POST 仍可能创建新任务，本地 24 小时判断不足以保证不重复。

**修订：**在原 POST 增加 EXISTING_ONLY 恢复模式，复用 gate、主体／requestId 和正文校验，只查找有效原任务，不进入创建分支。未知提交恢复必须使用此模式；缺失／过期转 SUBMISSION_UNRESOLVED。首次提交保持兼容，各子调用冻结稳定 requestId（不超过 96 字符）与正文。该局部 API 增强尚未实现，不新增任务系统。

**验收：**提交成功后丢响应／重启找回原 job；同键不同正文冲突；恢复请求等待 gate 时先清理旧映射，仍不能 INSERT。缺失任务不等于零数据，也不等于从未受理。

### R06：adopt 是本地消费关系，不是远端 worker 接管

**证据与触发：**QueryJobService 第 323 行起已实现队列与 worker lease，第 591 行起检查 fencing；当前 API 无 adopt。取消逻辑第 271 行起取消整个 job、变更令牌并删除页。新 revision 复用任务后，旧 revision 清理若顺便 cancel，会破坏新消费者。status 也不含可用于证明请求匹配的完整指纹。

**修订：**在 Agent 自有数据库的同一事务激活 revision 与 consumer relation；原 job、producer、worker lease 不变。adopt 与取消竞争同一 job binding 的 version／CAS；取消仅在事务内确认零有效消费者后写入 REQUESTED，再于事务外发送。REQUESTED 后拒绝新 adopt，回执丢失也不重置。PENDING 匹配依靠原 action 请求，退休仅撤销自身消费者。

**验收：**adopt 后旧版本清理、迟到回调和重复恢复不能取消或覆盖新消费者；不同权限／范围禁止接管。无需建设远端所有权迁移服务。

### R07：报告不能只指向短期统计快照

**证据与触发：**[AnalyticsQueryService.java](../../../services/shortlink-analytics-api/src/main/java/com/jupiter/shortlink/analytics/api/AnalyticsQueryService.java) 第 20 行同步 snapshot TTL 为 5 分钟，异步 job 为 24 小时。若只保存元数据和结果位置，源数据清理后历史图表、证据和导出无法复现。

**修订：**发布前耐久保存报告实际载荷与 EvidenceManifest，并与保留引用同事务发布；清理竞争同一载荷版本／锁。HISTORY_VIEW／EXPORT 检查固定报告、本地完整性、留存期限和当前归档对象权限；ANALYSIS_REUSE 仍检查严格的数据版本／范围／新鲜度。历史不再要求源快照有效或旧 authVersion 字面不变，但权限撤销始终拒绝。

**验收：**源快照清理后页面协议和导出仍对应原版本；超出产品保留期明确不可用，绝不偷偷重新查询当前数据。存储规模与清理策略仍需实现测试，不在本轮宣称容量已验证。

### R08：查询快照不等于共同的数据时间切片

**证据与触发：**AnalyticsQueryService 第 469 行附近校验分页请求哈希，第 574 行起哈希包含 queryKind／维度／过滤；QueryJobService 第 757 行附近拒绝新任务携带已有 snapshotId。当前快照绑定具体查询，不能直接给另一个维度查询复用。两个查询期间相同，中间发生回填时结果仍可能来自不同数据版本。

**修订：**periodsRef 只冻结期间；Artifact 分别记录 query signature、snapshot、epoch／manifest 等可取得的证明。同次扫描禁止混版本，不同查询按相同语义／窗口判断可比性；前后期间 manifest hash 不要求字面相等。未知一致性保留限制，需要共同底座时再扩展统计服务能力。

**验收：**相同日期换维度／过滤不能复用旧分页快照；模拟两次查询间回填，不得未经核验宣称同一冻结版本。

### R09：全量下降筛选不能从 Top N 推导

**证据与触发：**CampaignStatisticsTools 第 130／176 行将排名限制在最多 Top 50；第 60 行比较最多 16 个对象／期间组合；第 271 行起完整收集最多 10 页。排名的 resultComplete 只说明该排名请求完成。若下降对象在两期 Top 50 之外，依赖排名会漏掉它甚至得出“没有下降”。

**修订：**先冻结授权候选及定义，两期使用同一集合；每页载荷和进度 CAS 一起提交，重复页核对 checksum，不能重复累计、跨快照拼页或静默混入新成员。复用现有 LINK_METRICS 及任务分页，容量不足分批续接或明确部分覆盖；selectionComplete 由成员、页及逐对象分类对账证明，不从排名完成标记复制。

**验收：**至少 51 条候选且只有 Top 50 外对象下降；超过 16 个比较组合；需要超过 10 页。必须找到全部符合条件对象或明确未覆盖集合，不能发布完整结论。

### R10：后端完成评估不能靠自由文本验收句

**证据与触发：**原 Goal.acceptance 及 JSON 示例仅有自然语言；局部 criterionRef 不能自动成为全目标的可执行判定。合法 Schema、完整联合分布和有效引用仍可能被写成确定原因。

**修订：**PlanningAssessment.requirements 分为 DATA／CALCULATION／DELIVERY／CAUSAL_EVIDENCE，固定 criterionRef／版本和类型化参数。GoalAssessor 检查 MET／NOT_MET／UNKNOWN／NOT_APPLICABLE，必需条件未知或缺失不能 ANSWERED。交付检查不声称证明任意文本为真；因果问题没有相应证据能力时只交付事实与假设。

**验收：**完整地域×设备分布、正确引用和合法图文块仍不能将“证实下降原因”标为已回答。两份 JSON 已各补 5 个 requirement、拟议检查器与证据绑定；静态检查确保目标不遗漏，实际检查器实现及反例测试仍属于 P0／P5。

### R11：旧客户端硬编码完整结果，后端新增状态还不够

**证据与触发：**[agentWorkspace.js](../../../frontend/console-vue/src/relay/domain/agentWorkspace.js) 第 22、35、78 行把当前／历史／导出称为“完整结果”；[agentModel.js](../../../frontend/console-vue/src/relay/domain/agentModel.js) 仍要求 answer 字符串。P1–P4 若直接开放，WAITING／PARTIAL 仍可能被旧页面误展示。

**修订：**公开新入口前要求最小状态适配与 `campaign-response/v2` 协议声明。新 Run 可根据能力选择既有／新路径；旧客户端续接已经使用新运行器的 Run 返回 CLIENT_UPGRADE_REQUIRED，不自动切旧路径重新查询。executionStatus、goalAssessments、reportRef 和 nextAction 分开，旧 answer 由同一结果适配。

**验收：**旧协议客户端不进入新运行器；新响应、历史与导出状态一致。真实页面标签及图文呈现仍需后续前端验收，后端替身测试不能替代。

### R12：框架复用若到 P3 才验证，可能被迫重造引擎

**证据与触发：**原顺序先做合同、持久化与自定义 Skill 包，后验证 ReactAgent 等待／消息／挂载，容易按未经验证的“三套 Graph”假设投入过多代码。自定义 manifest／workflow 也会重复框架已有能力。

**修订：**P0 前半先验证现有 Tool 的原生纵向链路，再冻结最小适配接口。沿用控制流程；Skill 采用原生 Registry／Hook；确定性组合仅用业务代码或 StateGraph，不引入 DSL。框架方法读取不等于业务 executor，版本固定及授权交集仍由项目保证。

**验收：**首个切片无需新领域 Skill 可执行；没有第二个模型／工具循环，没有新通用 Skill loader／workflow 解释器；旧版本恢复不自动换方法包。当前固定框架若存在缺口先形成具体失败证据，再评估扩展点／版本兼容，而非直接自研整套替代。

## 3. 实施前与上线前门槛

1. **先证明机制：**P0 用原生组件与脚本化模型关闭 R01–R04 的适配不确定性，并冻结 Goal 检查合同；这些测试全可在后端无 Docker 完成。
2. **再证明恢复和数据：**P1–P4 覆盖丢响应、重启、过期、共享任务取消、分页范围、跨查询版本及权限变化。复用现有统计服务与框架基础设施。
3. **最后证明交付：**P5 校验目标、事实、报告版本与耐久载荷。公开新入口仍需最小客户端兼容；真实 MySQL 方言、线上模型质量、统计链路和浏览器表现不能由内存／H2 替身证明。

关闭记录必须包含风险编号、实现提交／PR、测试名称、关键断言及未覆盖边界。当前只有文档修订与静态自洽检查，12 项均未标记为运行验证通过。

静态检查覆盖文档链接／格式、两份计划的模式／依赖／输出绑定、逐目标 requirement、检查器参数及风险映射；执行结果随本轮交付报告。静态自洽不等于生产 Schema、Goal 检查器或后端运行测试通过。

本次风险优化后的静态结果：7 份 Markdown、74 个本地链接、2 份计划示例／6 个步骤、10 个 requirement、14 个证据端口绑定检查通过；风险表仍为 8 项 P1 和 4 项 P2。还需按 G0–G2 执行故障注入与实际后端测试，当前没有关闭任何运行风险。
