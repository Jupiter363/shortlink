# 投放分析 Agent：第二轮风险审查

日期：2026-09-19。审查基线：应用代码 `6c93bd9`、本地 Spring AI Alibaba `1.1.2.3`，以及第一轮优化后的[风险处理协议](risk-controls.md)、[合同](contracts.md)、[Graph 适配](graph-adaptation.md)、[实施计划](implementation-plan.md)和两份计划示例。

**新增登记 7 项：3 项 P1、4 项 P2。** 重点是跨服务传递、复合工具恢复和方案组合后的遗漏，不重复登记第一轮 R01–R12。另有三个候选问题经反证后不计入正式发现。没有新增 P0 的充分证据。

方法：三个独立只读审查方向加主代理交叉核验；检查源码、固定版本 JAR 字节码和协议反例。未启动 Docker、应用或浏览器，未运行后端测试、真实并发或模型请求。首次登记状态为 `IDENTIFIED / NOT_STARTED / NOT_RUN`；以下发现描述的是审查时的方案，文档行号和示例随后已修订，不能当作修订后仍然缺失的证据。

**后续方案优化（同日）：R13–R19 均已纳入正式设计，当前为 `SPECIFIED / NOT_STARTED / NOT_RUN`。** 具体对应关系见第 5 节；这不是风险关闭或运行修复证明。第一轮风险也保持待实现／验证。

以上状态保留为方案冻结时快照。后续 P0 的部分实现和实际测试另见 [后端验证记录](../../integration/campaign-plan-p0-2026-09-19.md)；局部控制点通过不等于跨服务风险已关闭。

## 1. 新发现

| 编号 | 级别 | 发现 | 相对第一轮新增的失败面 | 实施归属 |
| --- | --- | --- | --- | --- |
| R13 | P1 | EXISTING_ONLY 在 Admin 中可能丢失或无法识别 | R05 只补统计服务语义，尚未覆盖实际必经代理 | P1：Agent、Admin、Analytics |
| R14 | P1 | 冻结候选无法沿用当前全组查询／完整性证明 | R09 定义了冻结集合，但现有接口会重新枚举当前分组 | P2：范围合同及工具适配 |
| R15 | P2 | 复合工具恢复可能重查已完成的同步子查询 | 原恢复围绕 jobId，遗漏无 jobId 的同步 READY 结果 | P1–P3：子调用账本 |
| R16 | P1 | 工具超时后可能仍在后台继续派发请求 | R01 门控覆盖入口，未覆盖已进入的回调及未决超时 | P0 验证，P3 集成 |
| R17 | P2 | 目标“算完”仍可能未向用户交付 | R10 增加了检查器，但第一目标没有最终交付条件 | P0 合同，P5 发布校验 |
| R18 | P2 | 分批查询可能耗尽保留任务名额，完成任务也不释放 | R09 的分批推进没有对齐远端任务保留配额 | P1–P2：容量与推进策略 |
| R19 | P2 | 单请求内存有界不能证明多 Run 同时运行安全 | R04 覆盖单链路字节量，未定义组合容量验收 | P0／P3：模型与运行容量 |

P1 是对应能力启用前的阻断项。P2 同样需要在所属阶段验证，不表示可以忽略。以下区分“现有源码事实”和“方案在实施时必须解决的问题”，不将未实现功能描述为已发生事故。

## 2. 具体反例、建议和验收

### R13／P1：恢复模式必须经过 Admin 完整传递

**证据：**[AgentToolInternalController.java](../../../services/admin/src/main/java/com/jupiter/shortlink/admin/controller/AgentToolInternalController.java) 第 289–327 行的 StatisticsJobRequest 与转调没有 recoveryMode；[AgentAnalyticsFacade.java](../../../services/admin/src/main/java/com/jupiter/shortlink/admin/remote/analytics/AgentAnalyticsFacade.java) 第 179–211 行重建请求，最终只发送 requestId／query。其第 276 行起把后端失败转为普通 RemoteException，尚未保留新恢复错误的判定合同。[实施计划](implementation-plan.md) 第 39 行将首期服务改动限定为 Agent 与 Analytics，漏掉必经 Admin。

**反例：**Agent 发送 EXISTING_ONLY → 未适配的 Admin 拒绝或丢弃字段 → 若字段被丢弃，Analytics 收到默认 CREATE_OR_FIND → 原映射已清理 → 新 job 被创建。仅验证 QueryJobService 的 EXISTING_ONLY 分支，无法证明实际恢复链路安全。字段究竟拒绝还是丢弃取决于反序列化配置，本轮不假定一定静默丢弃。

**建议：**P1 纳入 Admin DTO／Facade／Client，以及 Agent 业务网关的端到端合同；模式和 REPLAY_UNAVAILABLE 等错误码必须可判定地到达调用者。恢复操作使用能够在旧代理上明确拒绝的独立入口，或有可信能力核验的版本化合同，不能因为字段缺失回落到创建。复用现有调用链，不新增任务服务。

**验收：**使用后端控制器／客户端替身串起 Agent→Admin→Analytics；分别注入旧代理、字段剥离、拼写错误、清理映射和结构化错误。恢复路径真正 INSERT 次数必须为零；首次合法提交仍可创建。不能只断言 Agent 收到一段错误文案。

### R14／P1：冻结集合与当前全组是不同的查询语义

**证据：**[风险处理协议](risk-controls.md) 第 91–95 行要求冻结候选；AgentAnalyticsFacade 第 192 行却在每次提交时重新 resolve 当前分组。第 243–248 行使用当前全组成员与任务成员是否相等生成 groupScopeComplete。[CampaignStatisticsTools.java](../../../services/agent-service/src/main/java/com/jupiter/shortlink/agent/tool/shortlink/CampaignStatisticsTools.java) 第 333 行要求没有单链 URL 的结果满足该全组证明。

**反例：**分析开始冻结 A／B；第二期间提交前新增 C，接口查询范围就变成 A／B／C。若 C 在 job 创建后才加入，A／B 仍合法的冻结结果反而可能因不是“当前全组”而被拒绝。把 501 个成员拆为固定子集也无法直接沿用全组证明。

**建议：**新增业务范围类型，明确区分 `CURRENT_GROUP` 与 `FROZEN_SET`。可信 frozenScopeRef 解析为固定 linkIds／hash／分片信息，经 Admin 对指定成员重新授权后传给已有 AnalyticsQueryRequest；结果校验根据范围类型判断。分片完成只证明该分片完整，不能冒充全组完整；权限撤销不允许偷偷缩小集合继续。EXISTING_ONLY 重建原请求时同样不能重新枚举当前组。

**验收：**冻结 A／B 后新增 C，两期间查询仍只含 A／B；撤销 B 时拒绝相关读取；501 个成员分片后能对账全集；注入额外成员、错误 hash 或伪造范围类型时真实查询为零或结果被拒绝。

### R15／P2：同步结果也必须进入子调用恢复记录

**证据：**CampaignStatisticsTools 第 66 行每次比较都遍历全部对象／期间；第 76–80 行只保存有 jobId 的引用，continuation 主要携带 jobs。同步成功在第 224 行附近返回空 jobId。父结果还可能同时包含 INCOMPLETE 与 pending jobs，不能只凭一个聚合状态决定恢复行为。

**反例：**A 同步 READY，B 转异步 WAITING；恢复 compare 时 A 再次 GET 取得新快照，B 继续读取旧 job。若 C 同时失败，适配器只看总体 INCOMPLETE 还可能丢掉 B 的等待任务。稳定 requestId 只能保护任务提交，不能保存 A 的同步结果。

**建议：**所有逻辑子查询都记录 childCallId、冻结请求、状态和 resultArtifactRef／jobId；无 jobId 的 READY 同样必须耐久保存。恢复先复用已发布 Artifact，再推进未完成子项。父步骤执行进度与已有部分结果分开汇总；保存一个 Tool 的聚合回复不能替代子调用记录。

**验收：**A READY＋B WAITING 多次恢复，A 的 GET 次数为 1、checksum 不变，B submit 为 1；A FAILED＋B WAITING 仍可续接 B；在 A 落盘后、B ACK 前中断，只对账 B，不重做 A。该问题需改造／适配现有复合 Tool，不能仅包装最外层 ToolCallback。

### R16／P1：超时不等于回调已经停止

**证据：**本地 `spring-ai-alibaba-agent-framework:1.1.2.3` 的 AgentToolNode.executeAsyncTool 使用 orTimeout().join()；超时后对支持 CancellableAsyncToolCallback 的调用发出协作取消，再返回 ToolCallResponse.error，不保证回调或底层 HTTP 已停止。[风险处理协议](risk-controls.md) 第 24–30 行明确了 handler 入口与账本失败门控，但尚未规定执行未决超时的处理。

**反例：**回调通过入口检查后阻塞 → 框架超时或用户取消 → 旧回调继续运行并发起内部下一页／下一对象请求；同时模型把普通工具错误当作确定失败，选择重试或替代调用。晚到结果发布 CAS 只能防止改状态，不能阻止已经发生的额外派发。

**建议：**复用原生 CancellationToken／可取消回调与已有有界执行器；将每个真实子调用取得派发资格的动作纳入可信运行状态校验。对未确认停止的超时保留原身份并进入对账，阻断模型按普通失败继续尝试。已经在途的请求只能记录原事实，不能使取消的 Run 重新成功。网络调用与数据库不构成原子事务，不能承诺取消瞬间强制撤销所有在途请求。

**验收：**使用忽略取消的延迟回调，超时后新增模型调用为零；取消后尚未获派发资格的内部请求为零；旧回调恢复、晚到 HTTP 和重复推进均不发布成功或新建同义任务。单纯断言 Future 已完成／抛超时不够。原生超时语义来自静态字节码核验，项目适配仍需运行测试。

### R17／P2：每个 ANSWERED 目标需要可访问的交付物

**证据：**[固定示例](example-plan.json)和[混合示例](example-hybrid-plan.json)中，find-declining-links 的 requirements 只有 candidate-coverage（DATA）和 negative-selection（CALCULATION）；唯一 DELIVERY 条件 evidence-explanation 绑定第二个目标。两个目标 ID 出现在报告中，不证明第一个结果已经交付。

**反例：**后端找全并排序下降对象，报告仅显示维度分析；goalIds 仍列两个目标，但没有下降清单、结果表或完整结果入口。现有五个条件可能全部 MET，用户却拿不到要求的清单。

**建议：**公共发布器对每个 ANSWERED 目标检查交付绑定：最终报告中有符合该目标合同的结果块，或有经授权且可实际读取的完整结果入口。数据事实和解释分别判断，无需强迫每个目标额外生成一段文字，也不要求把全量表一次性塞入页面。链接只指向后端已有 Artifact 而客户端无法打开，不算完成交付。

**验收：**保留两个 goalIds 和所有后端 Artifact，删除第一个目标的表格及可访问入口，发布应失败或该目标降级；恢复正确排序的表格／完整入口后通过，第二目标状态不受牵连。这是交付覆盖问题，与第一轮的因果真实性 R10 不同。

### R18／P2：分批能力需要对齐远端保留任务配额

**证据：**[QueryJobService.java](../../../services/shortlink-analytics-api/src/main/java/com/jupiter/shortlink/analytics/api/job/QueryJobService.java) 第 110–137 行对所有保留任务计数：每 tenant 最多 8 个保留任务、2 个活动任务；完成任务仍占保留名额。第 21 行保留期为 24 小时，第 687 行起到期才删除任务；cancel 仅改变活动任务状态／清理页，不立即删除任务记录。查询过重时工具会使用这些异步 job。

**反例：**一个已获准的多期间／多分片分析需要 9 个异步查询，前 8 个都已完成且复制到 Agent，仍未过保留期；第 9 个被 TOO_LARGE 拒绝。此时没有可等待的第 9 个 job，单纯“串行分批、等待前一项完成”无法释放名额。历史任务还会占用同一用户的额度。

**建议：**规划／执行需识别远端容量拒绝，将其与永久不支持、已提交 WAITING 分开；已取证据保留，待容量满足时推进未提交子项，不能让模型不断改计划重试。实施时评估活动任务、保留元数据、结果字节的分别计量，或耐久接收后的受控结果释放，并保留恢复所需身份；不能直接删除幂等记录来腾位。优先调整现有服务能力和配置，不新建任务系统，也不通过任意截断目标解决。

**验收：**预置 8 个未过期的完成任务，再运行需要新异步查询的合法分析；应返回可判定的容量状态并保留进度，查询／模型调用次数不随重复“继续”无限增长。名额释放后只执行未提交子项；旧 requestId 恢复仍不得误创建。具体容量值与释放机制需结合用户要求的高上限及实际存储验证，不能据此直接调小配置。

### R19／P2：内存验收需覆盖多个 Run 的组合峰值

**证据：**[BoundedHttpTransport.java](../../../services/agent-service/src/main/java/com/jupiter/shortlink/agent/infrastructure/llm/BoundedHttpTransport.java) 第 21／27 行说明 16 个并发许可约束业务 I/O，独立于模型传输。[SpringAiChatConfig.java](../../../services/agent-service/src/main/java/com/jupiter/shortlink/agent/infrastructure/config/SpringAiChatConfig.java) 第 29–32 行配置模型连接／读取超时。[GraphSessionExecutionCoordinator.java](../../../services/agent-service/src/main/java/com/jupiter/shortlink/agent/harness/checkpoint/GraphSessionExecutionCoordinator.java) 第 17／44 行使用 64 条锁防止相关图并发写入；分条锁有间接串行化作用，但并非按驻留内存或模型容量设计的准入合同。

**反例：**单 Run 的 HTTP、消息、页面和 saver 均不超各自额度；多个落在不同锁条带的 Run 同时持有临界大小的解析对象、模型上下文及序列化副本，组合峰值仍可能超出可用堆。第一轮的单链路上限不能证明该组合安全。本轮未测得实际 OOM，也不声称系统完全没有并发限制。

**建议：**给投放模型调用／大结果处理定义进程级容量验收及可观测准入，优先复用现有配置、有界 executor／许可机制；等待只保留轻量引用，公平地推进不同 Run。设置可配置且经测量的容量，不在文档中凭空指定低上限或删减分析内容；风险 Agent 与业务 I/O 的共享影响也需核验。

**验收：**用脚本化慢模型和大结果替身制造不同 Run 同时推进，覆盖许可耗尽、取消、超时与排队恢复；检查最大在途数量、驻留对象、许可释放和最终覆盖。单次 serializer 字节测试通过不能替代该验收，真实容量结论需后端测量。

## 3. 经反证后未计入正式发现的候选

1. **报告 owner 被对象权限替代：暂不成立为当前 P1。** 当前 tenantId 来自 userId，分组按 username 所属，且合同已有会话主体绑定；未构造出两个主体同时获准同一分组的可达例子。未来引入共享授权时应复核报告／分析文本归属，但本轮不据假设认定泄露漏洞。
2. **旧 PARTIAL 报告晚到覆盖新 ANSWERED：现有单写者可排除。** 若同一 Run 的推进资格覆盖 assess→report→发布全段，慢旧报告与快新报告并发反例不可达。应在 P1／P5 验证这个锁／资格范围；仅当后来允许锁外解释重试或独立异步生成时，才引入 assessmentSequence／证据版本与当前报告指针 CAS。不能忽略已有协调约束而直接报确定性竞态。
3. **“受信观察”被拼成系统指令：保留实现检查项。** 信封来源可信不代表 label 等自由文本是指令；现协议已禁止外部数据改变规则，但消息投影器尚未实现，未发现实际 SystemMessage 提权路径。P0 加入恶意 label／维度文本的角色边界用例，避免将此描述为已发生的提示注入漏洞。

旧 Narrative Schema 的五条／短文本限制已被主方案明确废弃，本轮不重复登记为新的内容阉割问题。

## 4. 推进建议与审查边界

先处理三个 P1：**R13 的恢复模式全链路传递、R14 的固定范围合同、R16 的超时／取消派发门控**。这三项决定第一轮优化能否真正生效，应作为对应后端 PR 的前置验收。

随后将 R15、R17、R18、R19 分别纳入子调用恢复、报告发布、统计容量和并发内存验收。全部保持 Spring AI Alibaba 原生优先；新需求主要是现有业务适配合同，没有证据要求另建 ReAct 或通用 Graph 引擎。

建议测试均可先通过后端替身与组件验证，不需要启动 Docker。本轮只完成风险发现及证据归档，没有执行这些测试；真实 MySQL 竞争、线上模型行为和实际容量仍须明确保留验证边界。

## 5. 方案优化后的处理决策

| 风险 | 选定处理协议 | 契约／阶段验收 |
| --- | --- | --- |
| R13 | [处理协议第 2 节](risk-controls.md)：Admin 与 Analytics 双端独立 recover-existing；入口强制 EXISTING_ONLY，保留结构化错误，禁止降级 submit | P1 跨层旧端点／剥字段／清理竞争恢复零 INSERT |
| R14 | [第 4.1 节](risk-controls.md)：CURRENT_GROUP／FROZEN_SET、可信成员及确定性分片、按范围证明完整性 | P2 新增不漂移、撤权阻断、501 成员分片对账；cohort UV 不求和 |
| R15 | [第 2.1 节](risk-controls.md)：同步／异步全部 child 记账，READY 耐久保存，恢复只推进未完项 | P1 A GET=1、B submit=1；失败与等待共存；无快照响应丢失不偷换证据 |
| R16 | [第 1.5 节](risk-controls.md)：原生取消＋逐次派发资格；未决超时阻断下一模型，实际活动状态独立于 Future | P0／P3 忽略取消的 callback、新旧 writer 重叠及晚到事实 |
| R17 | [第 5.1 节](risk-controls.md)：事实初评→草稿→DELIVERY 终评；每目标实际结果可读 | 两例已各增为 6 条 requirements／criteria；P5 删除一个目标交付不得仍 ANSWERED |
| R18 | [第 4.4 节](risk-controls.md)：执行／结果／身份分账；新协议任务耐久接收后受控释放结果页，保留恢复身份 | P2 第 9 个异步查询恢复推进；释放竞争、重复释放及旧 requestId 零重建 |
| R19 | [第 1.6 节](risk-controls.md)：活跃推进／模型／大结果准入，有界等待与实际退出后归还许可 | P0／P3 多 Run 峰值和共享执行器验证；容量由实测确定 |

合同统一见 [contracts.md](contracts.md)，后端 PR 顺序及验收见[实施计划](implementation-plan.md)。当前只做文档／JSON 静态一致性校验，没有新增业务源码或运行验证结论。

本轮静态核验：8 份 Markdown 的代码围栏、尾随空白及 104 个本地文件链接通过；两份 Plan 示例合计 6 个步骤、12 项 requirements、20 项证据端口绑定通过模式、拓扑、类型、参数及逐目标 DELIVERY 检查。子审查另核验 12 个破坏示例被静态校验拒绝。这里验证的是文档自洽，未运行生产 Schema 检查器或后端测试。
