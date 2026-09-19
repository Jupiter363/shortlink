# 风险处理协议与实施验收

状态：**两轮风险已纳入方案，P0 局部验证进行中**。2026-09-19。本附件把[第一轮审查](risk-review.md)与[第二轮审查](risk-review-round2.md)的 R01–R19 转成处理顺序、持久化边界和失败出口；它是业务约束，不替代 Spring AI Alibaba 的循环、Graph、Skill 或 saver。已完成组件和未验证边界见 [P0 后端验证](../../integration/campaign-plan-p0-2026-09-19.md)，不因组件测试通过标记整项风险关闭。

正文中的状态／错误码为拟议业务合同；原生 Hook 组合需先经过 P0 后端组件测试。只有“设计已明确”，不能将风险标为“已修复”。同一规则以本附件为细化依据，实施顺序见[实施计划](implementation-plan.md)。

## 1. 行动接纳、等待与消息恢复（R01–R04）

### 1.1 每轮接纳与修复

| 模型输出 | 接纳动作 | 下一步 |
| --- | --- | --- |
| 一个合法工具调用 | 先验证 ID、执行器、参数、权限及运行资格，再登记 action | 经原生 ToolCallback 执行 |
| 多个调用，ID 均有效且互不重复 | 整批真实调用为零；为每个 callId 生成唯一标准 ToolResponse，标明 `executed=false`、`BATCH_REJECTED` | 用消息 Hook 保留完整配对并要求重新生成单调用；修复成功再接纳 |
| 空 ID、同批重复 ID、孤立／重复响应 | 不猜 ID、不建立含糊配对、不派发工具 | 结束本次推进，`FAILED/MODEL_PROTOCOL_INVALID`；保留已有事实 |
| 无工具调用的最终输出 | 按原生结构化输出解析为候选决定 | 后端验收输出与完成条件，不能仅因模型停止而成功 |

批次修复使用原生消息 Hook／跳转候选，不另写一个模型循环。修复计数写入 Run 账本，采用版本化 `protocolRepairPolicy`，不能因 END→START、用户点击继续或换 revision 清零。达到策略边界时 `BLOCKED/PROTOCOL_REPAIR_EXHAUSTED`，明确可用结果与原因；修复不等同于已执行业务 CALL。具体额度在 P0 定稿，不把“每轮一个调用”变成任务对象、分析维度或总步骤上限。

工具调用批次的原始提示与外部数据都不能改变这些规则。Skill 的 read_skill 也参与框架消息配对；其只读方法事件与统计任务 action 分开记录，不能作为派发未授权业务 Tool 的通道。

### 1.2 等待的执行顺序

1. 模型后置 Hook 完成整批接纳；ToolInterceptor 在每次进入 handler 前检查 Run／revision／step 及推进资格。
2. 受控 ToolCallback 调用业务能力。提交前持久化 action 与 requestId，取得或幂等找回 jobId 后条件更新账本，才允许返回 PENDING。
3. 工具结果进入原生消息前转成小型观察；等待标记进入本次原生 state，账本是权威来源。
4. 下一模型前消息 Hook 读取等待标记／账本并结束本次推进；返回业务 WAITING，不再请求模型，也不依赖最终 AssistantMessage 存在。
5. 恢复先查询原 job。仍等待时直接返回进度；已就绪时校验并发布 Artifact，再恢复允许的模型推进。

账本写入失败或等待 Hook 异常属于本次推进故障，不能转换成普通可修复 ToolResponse 后继续循环。保留 DISPATCHING 等真实提交状态，由下一次可信对账确定结果，不能假装未调用或已完成。

P0 必须证明第 3–4 步之间原生框架没有额外模型／工具调用。失败时保持 REACT 入口关闭，FIXED 路径可以独立推进；不得以阻塞 Future、节点内长轮询或另套工具循环掩盖适配失败。

### 1.3 规范消息与账本

派发前记录 `(invocationId, assistantMessageId, toolCallId) → actionId → requestId + canonicalRequestHash`；jobId 在提交成功或对账找回后补齐。toolCallId 只在其 assistant 消息内唯一，不是业务幂等键。

- 原调用和唯一 PENDING ToolResponse 成为一组合法历史；READY 作为后续受信观察输入，携带 actionId／jobId／ArtifactRef，不给原 callId 再追加第二条响应。
- 若崩溃发生在响应写入前，根据账本构造唯一配对；已知 READY 且尚无已发布响应时可直接生成唯一 READY ToolResponse。只有原来已发布 PENDING 的情形才补新观察，两条路径按持久化的响应状态区分。
- `messageProjectionVersion` 与 `observationSequence` 固定这次投影；以原生 REPLACE 替换派生消息窗口。重复恢复不得重复附加观察；完整历史配对组可以整体移出窗口，不能裁剪出孤立消息。
- 已完成 action 的同义调用先按冻结输入／快照对账，复用事实或报告原任务进度；不得让模型重复提交来“找回”数据。需要新鲜数据时建立显式新分析身份。

规范上下文可由可信账本重建时，忽略落后的 checkpoint 消息；缺失原始调用身份、内容冲突或无法唯一配对时终止该次推进，保留证据并标明协议错误，不能由模型猜测修复。

### 1.4 内存边界覆盖整条链路

仅在 ToolInterceptor 压缩结果不足以覆盖此前的峰值。保护依次落实到：现有 HTTP 客户端的响应体／错误体读取 → 有界页面反序列化 → 页面持久化 → 小型 ToolResponse → 原生消息窗口 → 每次 saver 写入。也需覆盖模型响应、工具参数及批次长度；不能只检查 Content-Length 或最终 checkpoint。

复用现有客户端配置／响应流包装、统计分页和原生消息 Hook。大数据按页保存到 Artifact，失败时保留已确认的游标；不要先 collect 全部页再压缩，不把伪流式封装当成流式反序列化。所有额度按配置与容量测试确定，超界返回明确原因并支持可行的分批路径，不静默截断数据后交付完整结论。

### 1.5 超时、取消与真实派发资格（R16）

原生 Future 超时只表示调用方停止等待，不证明 callback／HTTP 已停止。复用原生 CancellationToken／CancellableAsyncToolCallback 及有界执行器；业务账本在工具超时后记录 `BLOCKED/EXECUTION_UNRESOLVED`，关闭下一次模型推进，保留原 action、childCallId 和已取得的 requestId／jobId。不能把未决超时仅作为普通工具错误交给模型选择替代调用。

每次真实子请求（首次提交、分页、状态读取或下一个对象）都通过同一受控网关，在派发前重新检查 Run／revision／step、advanceId／writerEpoch、当前 attempt 与取消意图，并原子登记单次派发资格。旧回调不能沿用 handler 入口的通过结果继续发下一页。取消或 revision 切换先撤销后续派发资格；已获资格／已在途请求可以晚到，记录原事实但不能发布取消 Run 的成功。网络与账本不是原子事务，不承诺撤销瞬间停止已授权请求。

恢复必须先隔离旧 attempt：只允许可信恢复入口对账已知身份，不能同时开放新模型行动。已知 jobId 走原状态／分页；提交结果未知走第 2 节独立恢复入口。同步读取若没有稳定 snapshot／耐久响应可找回，保持明确缺口；新取数必须成为显式新收集代次，不能把较新响应冒充原结果。旧 callback 的迟到事实只有经当前恢复者核验才能接收，不能自己重新取得推进资格。

单写者范围覆盖本次 assess→组装→发布；异步 callback 只提交按原身份校验的事实。首期不另开锁外报告生成流水线。下一模型前检查账本、writer 资格及 callback 实际活动标记；新 writer 获锁不代表旧 callback 已退出。原生超时会清空工具 state updates，不能只依赖其中的 WAITING 标记；外围 invoke 适配同样读取领域账本，P0 必须证明原生下一模型门控有效，返回后才拦截不能补救已经发生的调用。无法证明“未决时额外模型调用为零”则保持 REACT 关闭。

### 1.6 多 Run 的容量准入（R19）

复用有界 executor／许可机制，计量活跃推进、模型在途和大页面解析／序列化。活跃推进许可覆盖状态装载至结果持久化／投影结束，避免模型许可释放后大量结果仍驻留；模型许可在构造请求前取得，大结果许可在加载／解析前取得。64 条 Run 锁与现有 16 个业务 I/O 许可不能代替这项容量合同。首期按测量配置较高可用上限，不凭空指定小数值；固定顺序短时取得所需许可，获取失败释放已获资格并让出，禁止持有一种许可长等另一种。执行器拒绝策略不得通过 CallerRunsPolicy 绕过总许可。

未获容量时记录轻量恢复引用与 `BLOCKED/LOCAL_CAPACITY`，让出 Graph 推进及执行线程；等待队列有界、公平按 Run 推进，不携带完整 prompt／页面对象或派生表格。可复用现有推进入口按 nextAction 恢复，不为本方案另建通用任务队列。许可在成功／异常／拒绝／取消后释放；但调用仍在后台运行时不能只因包装 Future 超时就提前归还其在途许可。按实际 worker／传输终止归还，未终止量继续可观测地占用容量。

后端容量验收同时覆盖多 Run、风险 Agent 的共享模型／执行器影响、解析对象与序列化副本。配置依据包含实际 JVM 堆、单次峰值、目标并发和留量；可执行的高上限与完整分析并存，排队不删除目标、结果或章节。进程重启后由持久化运行资格重新接纳，不恢复失效的内存许可。

## 2. 提交与恢复协议（R05、R13、R15）

以下是 action 的提交状态，不新增一套 Step 状态机或任务队列：

| 提交状态 | 已知事实 | 允许动作 |
| --- | --- | --- |
| PREPARED | requestId、完整规范请求、主体、执行器版本和输入摘要已持久化，尚未声明派发 | CAS 获得派发资格 |
| DISPATCHING | 派发意图已落盘；崩溃后可能不知道远端是否受理 | 优先对账；有 jobId 则读状态，无 jobId 仅按安全重放协议处理 |
| ACKNOWLEDGED | jobId 已可靠保存 | 只续查原 job；WAITING／READY 属于远端任务事实 |
| RESULT_PUBLISHED | 已校验 Artifact 和消费关系已提交 | 返回原输出，不重新提交 |
| SUBMISSION_UNRESOLVED | 无法确认远端受理结果且已不具备可靠重放条件 | `BLOCKED/SUBMISSION_UNRESOLVED`，禁止自动新建查询 |

requestId 是每个逻辑子调用的稳定 opaque ID，不超过现有 96 字符约束；attempt、HTTP 重试、Graph 推进和 plan adoption 不改变它。相同 requestId 对应不同规范正文时直接失败，不尝试新 ID 绕过冲突。当前主体变化或权限撤销时不发送重放请求。

**24 小时不是客户端可以盲目重试到最后一毫秒的保证。** 保存 `firstDispatchAt`、服务端可取得的 `expiresAt`、保留策略版本、请求截止与时钟安全余量。已知 jobId 只走读取；结果不存在时明确过期或不可用，不能 POST 重建。

**P1 选定方案：Agent→Admin→Analytics 都使用独立的 `recover-existing` 入口，入口在服务端强制 `EXISTING_ONLY`。** 首次提交继续现有 submit／CREATE_OR_FIND；两个入口共用原服务的 gate、授权和请求哈希校验，仅恢复入口绝无创建分支。恢复模式不进入业务 query 哈希，仍核对同主体／requestId／原 query hash。有效原任务可找回；缺失、过期或已清理返回 `REPLAY_UNAVAILABLE`；正文不一致保持 CONFLICT。

无 jobId 的 DISPATCHING 恢复一律调用该独立入口，不能改发默认 POST；网关／客户端也不得对首次提交进行不可见的跨窗口重发。Admin DTO、Facade、Analytics Client 和 Agent 网关全部纳入同一协议：传递冻结的原查询，不重新 resolve 当前分组；保留可判定的错误 code、jobId 和期限。入口不存在（404／405）、版本不支持或响应结构不符时返回 `RECOVERY_PROTOCOL_UNAVAILABLE` 并保持 SUBMISSION_UNRESOLVED，禁止回退创建。不得依赖可能被旧代理丢弃的可选字段完成模式切换。REPLAY_UNAVAILABLE 不能证明原请求没有执行；只能保留只读对账或显式新分析选项。

独立入口是复用原查询任务服务的窄适配，不是新任务系统；Admin 也必须在自身恢复入口只调用 Analytics 恢复入口。此合同未实现并通过跨层测试前，不承诺无重复恢复。已知 jobId 的续查仍使用现有接口；结果已受控释放时返回原身份及 `RESULT_RELEASED`，不得新建。

本地 TTL 与安全余量用于提前停止无效重试，不承担正确性保证。必须注入“请求在过期前发出，但等到清理完成后才取得 gate”的场景，证明 EXISTING_ONLY 仍只返回不可恢复、不创建新 job。无需新任务系统，也不宣称延长原始快照生命周期。

### 2.1 所有子调用都可恢复

ActionLedger 为复合 Tool／Skill 的每个逻辑子查询登记 `childCallId`、parent action、冻结请求／hash、scope／period、调用类型、dispatchState、attempt、jobId（可空）、snapshot／游标与 resultArtifactRef（可空）。同步 READY 也必须原子保存耐久 Artifact 引用和完成状态，不能只保留 jobs 数组。一个 child 可经历同步响应要求转异步的阶段；其阶段与真实 HTTP 尝试分别留痕，异步 requestId 在首次 POST 前固定。

恢复顺序：复用已发布 Artifact → 对账 DISPATCHING／未决调用 → 续查已知 job → 在有资格和容量时处理未提交项。已完成的同步 A 不随异步 B 的恢复再次 GET；已保存分页同样不重复累计。父执行进度按所有 child 归并，独立记录已有证据和失败原因：有待完成 job 就保留 WAITING 事实，即使其他 child 已失败或部分结果为 INCOMPLETE；未提交容量阻断不伪造 jobId。能否发布部分步骤输出仍由输出合同决定。

同步 HTTP 响应到达但尚未耐久保存即崩溃，没有天然 exactly-once 保证；能凭稳定快照恢复则继续原代次，否则保留缺口或显式启动新收集代次。禁止通过重查覆盖已有 READY 证据来假装无损恢复。

## 3. 计划接管与取消的并发边界（R06）

复用 Analytics 现有 job 与 worker fencing；Agent 只维护可信 job 引用、冻结请求和消费者。adopt 不改变 producer、worker lease 或远端请求身份。

在 Agent 自有数据库中，同一物理 job 的 binding 保存 `version` 与 `cancelIntent=NONE/REQUESTED/CONFIRMED`，不复制远端 job 状态机。adopt 与取消意图必须竞争同一 binding 的行锁／CAS，不能先在事务外 count 消费者再发 cancel：

1. adopt 只允许 cancelIntent=NONE；重新校验权限、请求合同与剩余生命周期，在一个本地事务中添加新 consumer、激活新 revision 并撤销旧推进资格。
2. 旧 revision 退休只取消自身消费资格，不自动取消物理 job。
3. 明确取消分析时先终止该 Run 的消费；仍有其他有效消费者则不发送远端 cancel。
4. 确认无有效消费者且允许取消物理任务后，将 cancelIntent CAS 为 REQUESTED；之后新 adopt 一律拒绝。网络 cancel 在本地事务外发送，重试沿用同一意图；对账确认后记录 CONFIRMED。
5. cancel 响应丢失先读远端状态，不自动重新开放消费。READY 与取消竞争时允许保存原事实，但已取消 Run 不能据此发布成功；关联读取还须确认载荷可用。

Agent 的事务不包住远端服务，不能称为跨库原子取消；外部直接取消 job 仍需在恢复／读取时识别。首期不必新增远端 adopt，也不能绕开现有 worker 租约。

## 4. 全量证据与报告留存（R07–R09、R14、R18）

### 4.1 冻结候选与持续收集

筛选先固定授权候选成员清单／摘要与期间，再对齐两期 LINK_METRICS。分页进度按 `(query signature, jobId/snapshotId, page/cursor)` 保存；每页幂等写入并确认校验和后再推进游标。重放相同页不重复累计指标，页内容冲突时停止拼接并标注数据不一致。

跨页始终属于同一查询快照。中途过期不能把新 snapshot 的后半页拼进旧前半页；保留原收集片段为不可完整交付的证据，按显式新收集代次从头取得一致数据，或返回部分覆盖。原生任务能提供全部页时继续复用，不以单次 10 页或 Top 50 当全集。

`selectionComplete` 必须同时满足候选枚举完成、所有请求期间／分页已处理、每个候选均有可比较结果或明确不可比原因、对齐与筛选计算完成。完整枚举不等于遥测完整；缺失／不可比仍影响 Goal 状态和结论范围。对象在分析中途新增不能悄悄扩大原候选集合；权限撤销立即阻断相关读取与发布。

范围协议明确区分 `CURRENT_GROUP` 与 `FROZEN_SET`。跨期间／依赖分析先由可信枚举生成 FrozenScope（主体、来源分组、固定 linkIds、memberHash、枚举完成证明、授权校验版本）；Graph 只持有 scopeRef。Admin 解析可信引用或经过内部认证的 manifest，并对指定成员重新授权，复用现有显式 linkIds 解析路径。恢复及新期间不得重新枚举成当前全组。新增 C 不改变既有 A／B；撤销 B 则阻断相关取数和发布，不能悄悄缩成 A。

超过现有单查询 500 成员能力时，按固定排序生成无重叠分片，保存 `parentScopeRef/shardId/shardMemberHash`；各期使用同一分片。结果给出与原范围匹配的 `scopeProof`，区分单片完整、冻结全集完整、当前全组完整；不能继续用 `groupScopeComplete` 一项判断所有模式。Agent 对片成员并集、唯一性、页覆盖和返回成员做对账，额外成员／错误 hash 拒绝。分片可合并逐链接指标和同口径可加 PV；cohort UV／UIP 必须独立去重查询，底层不支持时明确缺口，不能相加冒充。

### 4.2 期间与数据版本分别记录

periodsRef 固定日期和时区；querySnapshot 固定某个查询的签名、快照和实际可取得的数据版本。跨查询判定 `VERIFIED / UNVERIFIED / INCOMPATIBLE` 的可比性，并保存判断依据；字段缺失按 UNVERIFIED，不能填造共同版本。

已核验的同口径变化可按对应检查规则使用；UNVERIFIED 可交付“分别观测到的值”及限制，但不能通过需要共同冻结底座的 requirement。比例分母、联合维度与整窗 UV／UIP 必须来自对应的真实查询合同，禁止通过不兼容的边际分布或求和补造。

版本证明只在语义相同、窗口集合相同的范围内比较：需要检查同一期间内不同查询的底座关系，以及两期间指标口径、窗口与采集条件。前后两个期间的 manifest hash 本来可以不同，不能以要求它们字面相等来误阻断合法对比。完整 manifest 留后端，Graph 仅持有引用。

### 4.3 报告发布与清理

Artifact 的 `reuseExpiresAt`（新分析复用期限）与 `retainedUntil`（保存期限）分开；原 `expiresAt` 作为复用期限的兼容字段，语义不得悄悄改变。`STAGING` 载荷不可被报告引用，只有校验完成的 READY 载荷可发布。

发布流程：持久化本报告需展示的固定载荷及证据 → 校验 READY、checksum 和权限 → 同一 Agent 数据库事务发布 report revision、证据清单和保留引用 → 返回 reportId。清理与新增保留引用通过同一载荷记录的版本／锁串行化；已进入清理状态的载荷不能新引用。事务失败不发布新报告；仅将本次新增且没有任何有效消费／报告引用的载荷按保留策略登记待清理，不能回退或删除既有共享 READY Artifact，也不暴露半份报告。

读取用途显式区分：`HISTORY_VIEW/EXPORT` 校验固定 report revision、本地耐久 EvidenceManifest、留存期限与当前对归档对象的权限，不要求已删除的源 job／snapshot 仍有效，也不要求旧 authVersion 与当前字面相等；`ANALYSIS_REUSE` 执行范围、成员、快照／数据版本、复用有效期及当前授权的全部兼容检查。两种用途都拒绝已撤销的权限。

页面、历史、复制、导出按同一 reportId／revision 读取；源快照消失不影响已耐久保存的报告。报告载荷也已被清理时返回 `REPORT_DATA_EXPIRED`，不得以当前查询替换原版本。引用保护仅在 Agent 本地存储生效，不延长源 TTL，也不新增远端 pin。应用审计不长期保存无需展示的原始访问明细。

### 4.4 容量拒绝与受控结果释放

现有“每主体 8 个保留任务”包含 SUCCEEDED 记录。分批串行本身不会腾位，首期在现有 QueryJobService 将活动执行、保留结果数量／字节、轻量恢复身份分别计量并配置。常规容量不足返回可判定的 `QUERY_CAPACITY_EXHAUSTED`、`capacityKind` 和可信 `admitted=false`，不混用查询形状过大的 TOO_LARGE；模型不得通过重规划或换 requestId 绕过。仅收到明确未受理回执后，child 才可恢复为待派发；网络超时／回执丢失仍按 DISPATCHING 对账。Step 以 `BLOCKED/REMOTE_CAPACITY` 保存进度；有已提交任务时同时保留其 WAITING 子项。

P2 大集合能力启用前，补现有 job 的幂等 `release-result` 窄操作，仅用于新协议创建且 Agent 管理结果消费的任务；旧协议任务保持原 TTL，不自动提前释放。Agent 先将需要复用／交付的页、指标和 provenance 耐久存为 READY Artifact，并确认全部有效 consumer 可读取这些载荷；再于本地同一 binding 的锁／CAS 内登记 releaseIntent，使后续 adopt 只能走已授权的本地产物，不能重新承诺远端页仍可读。事务外调用 Admin→Analytics 的释放接口；服务端只允许同主体的终态 job，校验 jobId／requestId／查询指纹，活动任务不可释放。

释放只清理结果页并释放结果配额，保留原 requestId→queryHash→jobId、冻结请求、主体、scope／epoch／manifestHash、终态及 `resultState=RELEASED`；身份沿原期限保留，不能通过删除幂等记录腾位。服务 gate／事务令页面删除、结果配额变化和释放状态一致；重复释放幂等，丢响应先查状态。首次 submit 和 recover-existing 命中该身份都只返回原任务及 RESULT_RELEASED，不能再执行查询。Admin 不得再按 SUCCEEDED 强置 resultReady=true；page 读取明确返回结果已释放。Agent 读取已确认的本地 Artifact，否则如实报告不可用。

恢复元数据仍有独立容量和清理策略，不能宣称无限运行。优先为正常多期间／多对象规模设置经测量的较高配置；真正耗尽时返回明确类型与可恢复时间／条件，按服务退避或受控推进恢复，仅处理剩余 child。重复“继续”不得反复调用模型或无间隔重提；未知释放时间不编造 ETA。接口及计量未完成前，不开放宣称可持续覆盖大集合的 Skill。

## 5. 完成判定与客户端合同（R10–R11、R17）

### 5.1 每个目标都对应可执行条件

PlanningAssessment 把每个 required Goal 映射到具名 requirements；每项固定 kind、criterionRef／version、参数、证据端口及适用规则。模型只能提出候选，后端从登记目录解析；缺检查器的目标保留明确 gap。静态合同示例见两份 Plan JSON 的 planningAssessment 与 proposedCriteria。

报告数字及计算结论绑定事实引用；将观察、计算结果、假设、因果主张、建议分别标识。DELIVERY 检查解释及引用是否交付，不充当真实性证明。用户要求“证实原因”时保留 CAUSAL_EVIDENCE 条件；只有支持假设的维度分布，不满足该条件。

发布使用两阶段评估：先核验事实／计算形成候选状态，再组装 report draft，最后以真实 draft、EvidenceManifest 和授权读取器完成 DELIVERY 校验，得到与报告一起发布的最终 GoalAssessment。每个 ANSWERED goal 必须有可用交付物；只有 goalIds 或 Artifact 存在不算交付。公共发布约束不允许 Planner 通过省略 DELIVERY 条件绕过。

交付绑定保存 `goalId/requirementId/sectionId/blockIds/evidenceArtifactIds`，必要时带已登记的完整结果读取 action。清单目标需要同口径、有排序的结果表或可实际访问的完整结果入口；解释目标还需与数据相互引用的解释块。空集合以有证据的 NO_DECLINES 说明交付；不能把证据不足当空结果。完整结果入口必须绑定本报告 revision 的耐久数据、授权路由和留存引用，不暴露 locationRef，不只是客户端无法读取的后台 artifactId。长表可以分页，无需强加段落或铺满页面。

发布前为草稿预分配 reportId／revision，在服务端用授权读取器验证 READY 数据、读取合同和客户端能力，再原子发布报告及清单；不要求提前公开未发布草稿或自调用尚不存在的公开链接。后端验收另断言发布后同一主体可经正式读取入口取得固定结果；实际页面可用性仍保留在前端验收范围。

交付检查失败只降级相应目标／拒绝相关块，保留其他有效结果；既有第一目标可由后端确定性筛选事实生成交付，不因后续综合模型失败而消失。原生图的 END 或计算节点成功不能绕过最终发布门槛。

| 已有证据／要求 | 允许的 Goal 结果 |
| --- | --- |
| 所有必需条件 MET，或按登记规则有证据地 NOT_APPLICABLE | ANSWERED |
| 有可交付事实，但必需条件 NOT_MET／UNKNOWN | PARTIAL，列出未满足项 |
| 仍有可恢复任务，暂未形成可交付结论 | PENDING |
| 缺必要用户输入且无可交付结论 | NEEDS_INPUT |
| 已确认整个可用能力目录及合法组合均缺所需能力，且无可交付结论 | UNSUPPORTED |
| 查询／规划无法完成且无可交付结论 | UNAVAILABLE，保留原始原因 |

检查器不能证明任意自然语言正确；解释与事实冲突时拒绝该解释块，保留校验过的数据块，并按解释交付条件降级。对“全部”“唯一”“导致”等强结论需匹配相应覆盖／方法要求，不能只因文本有引用就发布完整状态。语义质量仍需脚本化反例及后续真实模型评估。

### 5.2 最小响应合同

后端响应分别提供 `executionStatus`、逐目标 `goalAssessments`、`reportRef`、`nextAction`；不能用一个 success 同时表示 HTTP 成功、工具完成和分析完整。旧 answer 字符串由同一报告／状态适配，禁止生成另一份相互矛盾的结论。

新入口要求客户端声明 `campaign-response/v2`，并通过服务端启用配置检查；协议能力不是授权。未声明能力的新请求继续既有路径，已经使用新运行器的 Run 若被旧客户端续接，返回明确 `CLIENT_UPGRADE_REQUIRED`，不得切旧运行器重新取数。最小状态标签验收通过后再开启新入口，无需等待整个仪表盘重做。

历史报告和当前等待／失败进度分别呈现；“上次已完成报告”不能被伪装成本轮结果。报告的 ANSWERED／PARTIAL 与 Run 的 WAITING 可以并存，用户应能看清哪些目标已有答案、哪些仍在继续。

## 6. 实施门槛与风险关闭（R01–R19）

| 门槛 | 后端必须证明的反例 | 对应风险 |
| --- | --- | --- |
| G0 原生执行协议 | 多调用合法拒绝配对；坏 ID 零派发；PENDING／未决超时后模型零调用；旧 callback 无权派发新子请求；首次 saver／HTTP 有界；多 Run 容量与实际 worker 许可归还 | R01–R04、R12、R16、R19 |
| G1 任务与数据协议 | 恢复穿过 Admin 且旧端点不降级创建；近过期零新建；adopt/cancel/release 竞争；同步 READY 不重查；冻结成员／分片对账；源过期不拼页；第 9 个异步查询能在结果释放后推进；恢复身份保留 | R05–R09、R13–R15、R18 |
| G2 发布协议 | 发布与清理竞争无悬空引用；因果证据缺失不 ANSWERED；每个目标的真实结果可达；旧客户端不能恢复新路径；历史／导出同版本 | R07、R10–R11、R17 |

优先复用原生 Graph／ReactAgent／Hooks／Skills／saver 与现有统计服务。P0 首个切片直接使用已存在 Tool；只有失败证据证明配置与原生扩展点无法满足边界时，才评估最小局部适配或框架版本调整。框架升级需独立回归模型、saver 与风险 Agent，不夹带通用编排平台。

风险记录保留三个独立字段：`designStatus`、`implementationStatus`、`verificationStatus`。本轮统一为 `SPECIFIED / NOT_STARTED / NOT_RUN`；实现后记录提交和测试证据，再按实际结果关闭。真实 MySQL 并发、线上模型质量和前端表现仍须各自验收，不以文档／内存替身通过替代。
