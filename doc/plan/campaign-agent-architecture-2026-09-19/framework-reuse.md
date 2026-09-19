# Spring AI Alibaba 优先复用清单

状态：**P0 原生控制点通过组件验证，生产集成待 P1–P3**。2026-09-19；项目固定版本 `1.1.2.3`。实际结果见 [首批 P0 验证](../../integration/campaign-plan-p0-2026-09-19.md)及[补充验收](../../integration/campaign-plan-p0-recovery-2026-09-19.md)。本地 JAR API／字节码用于确认可调用接口，官方文档用于理解能力方向；线上文档出现的新接口不能直接视为本地版本可用。

关联：[主方案](../campaign-agent-architecture-2026-09-19.md)、[Graph 适配](graph-adaptation.md)、[实施计划](implementation-plan.md)、[风险审查](risk-review.md)。

## 1. 技术选型原则

**外层 Plan 管目标和依赖，局部按需探索；执行机制优先复用框架，业务约束由项目补齐。** 先用原生封装证明一个已有 Tool 的完整纵向链路，再添加多步骤、Skill 组合和复杂报告。不要先实现一套通用 Agent 平台。

| 需求 | 优先复用 | 项目必须补齐 | 首期不建设 |
| --- | --- | --- | --- |
| 规划模型与结构化候选 | 现有 ChatModel，ReactAgent 的 outputType／outputSchema 等输出能力 | PlanSpec、能力目录中的业务元数据、DAG／类型／覆盖校验 | 独立模型调用 SDK、任意代码计划语言 |
| 外层计划执行 | StateGraph、CompiledGraph、节点和边、原生 saver | 冻结 PlanSpec 到节点的薄编译适配、运行版本资格检查 | 另一套通用 DAG 调度器 |
| 局部探索 | ReactAgent 的模型→工具→模型循环 | 允许能力与范围、业务 WAITING、证据与完成判定 | 自写完整 ReAct 引擎、每次工具调用新增外层节点 |
| 子图组合 | asNode、outputKey、原生子图接口 | 必要的状态投影和身份映射，P0 决定挂载方式 | 默认强制三套图包装和三份持久化系统 |
| 工具执行 | 原生 ToolCallback、项目现有 AgentToolRegistry | 可信 action 上下文、鉴权、稳定请求 ID、结果引用 | 第二套工具注册协议、另一个 ToolCallAdvisor 循环 |
| Skill 发现与读取 | SkillRegistry、ClasspathSkillRegistry、SkillsAgentHook | 审核目录、内容版本锁定、业务能力元数据的关联 | 自制 Skill loader／manifest 解析器 |
| 常用分析组合 | 普通业务代码或原生 StateGraph；方法指引用 SKILL.md | 输入输出合同、强制计算、子调用恢复与审计 | workflow.json DSL／通用 Skill Runner |
| 行动约束与上下文管理 | ModelHook、ToolInterceptor、消息 Hook／REPLACE | 运行令牌、范围、累计预算、Artifact 摘要、等待门控 | 第二套消息协议或无界日志充当上下文 |
| 检查点 | 原生 saver、RunnableConfig、已存在的恢复接口 | Run／Step／Action 业务账本，与 checkpoint 对账 | 第二套通用 checkpoint 框架、声称原生 exactly-once |
| 异步统计恢复 | 现有 analytics job API 的请求幂等、gate 与 jobId 续查 | Agent→Admin→Analytics 独立 recover-existing；所有 child 恢复；固定成员证明；结果／身份分账和受控释放 | 新任务队列或通用任务接管服务 |
| 超时与组合容量 | 原生可取消回调／CancellationToken、现有有界 executor 与许可 | 每次真实派发资格、未决阻断、实际执行退出记录、多 Run 峰值验收 | 自制线程调度框架、用 Future 超时冒充真实取消 |
| 最终输出 | 框架结构化输出和项目现有序列化／校验设施 | GoalAssessment、事实校验、逐目标真实交付、报告块协议与版本 | 按每种意图写一个前端页面 |

表中框架输出、Hook 和节点接口已作静态核验，组合运行效果需通过 P0 后端测试。结构化输出约束格式，不能替代事实正确性或权限校验。官方入口：[Agents](https://java2ai.com/docs/frameworks/agent-framework/tutorials/agents/)、[Hooks](https://java2ai.com/docs/frameworks/agent-framework/tutorials/hooks/)、[子图](https://java2ai.com/docs/frameworks/graph-core/examples/subgraph/)。

## 2. Skill 的两种用途

**方法型 Skill** 直接用框架渐进式发现与读取：模型参考 SKILL.md 决定怎样解释证据、选用哪些已批准能力；它不保证必经计算已经执行。原生 SkillRegistry／SkillsAgentHook 负责此部分。[官方 Skills 文档](https://java2ai.com/docs/frameworks/agent-framework/tutorials/skills/)

**可执行业务 Skill** 是项目对成熟组合的业务登记：给出固定版本、输入输出端口和强制步骤，用已有代码或原生 Graph 组合工具。只有这种已实现且测试过的适配器，才能成为 FIXED 的 `executor.kind=SKILL`。首个纵向切片无需它；现有 Tool 可直接工作。方法型 Skill 可以与它关联，但读取文档不等于执行该组合。

首期加载策略：

- 使用明确的 classpath 路径和随发布审核的包，Run 固定内容摘要／版本；恢复沿用相同内容，缺失旧版本时明确停止或受控迁移，不自动换新版。
- 不用默认个人目录覆盖服务器方法包。若选择文件系统 Registry，目录需显式配置，输入不能决定加载路径。
- SkillsAgentHook 的 read_skill 是只读辅助能力；只读受批准目录，按当前 Run 记录读取版本。它不提交统计任务，业务 CALL 账本与方法读取事件分别记录，但二者都遵守停止策略。
- groupedTools 的发现结果仍与冻结 allowedExecutors 和当前授权求交。框架激活工具可能延续后续轮次，不能据此扩大当前步骤权限；每次实际 dispatch 再校验。
- 不因框架示例附带 Shell／Python 就给本 Agent 注册通用执行工具；投放分析优先调用现有领域接口。

这些约束不要求重写 Registry，使用其配置、按版本实例化与薄过滤适配即可。

复用现有封装不排除必要的业务接口补强：Admin／Analytics 双端恢复入口强制 EXISTING_ONLY；冻结范围与结果释放复用原授权／查询任务。Spring AI Alibaba 不承担这些业务服务的幂等、范围和存储语义。详细边界见[风险处理协议](risk-controls.md)，不新增任务引擎。

## 3. P0 必做的小型验证

使用原生 ReactAgent、脚本化 ChatModel、已有 Tool 的替身和 MemorySaver，先验证以下路径；随后才冻结 LocalExplorer 和 PlanStepNodeAdapter 的最小接口。

1. 一次合法工具调用，得到候选结构化输出，后端决定完成状态；没有第二个工具循环。
2. before／after Model Hook、ToolInterceptor 与结束跳转的实际顺序可阻止 WAITING 后继续行动；invoke 无最终文本时仍可交付状态。
3. 模型一次返回多个调用时在派发前整批拒绝；缺失／重复 toolCallId 也在副作用前失败。
4. 原生调用／响应配对可通过消息 Hook 恢复；PENDING 配对仅一次，READY 作为新观察引用进入上下文，原任务不重提。
5. 所有 saver 写入的状态均只含有界摘要和引用；大载荷在 ToolResponse 进入框架前处理，asNode 的选项不被误当成隔离保证。
6. 原生节点挂载与少量投影适配比较：选能满足身份／状态／恢复合同的最小方案，并只保留一种生产挂载方式。
7. 原生 Skill 读取、版本固定、工具交集验证通过；方法包不能修改主体、范围或工具权限。
8. callback 忽略取消且超时清空 state updates 时，领域账本仍阻止下一模型与未获资格的子请求；恢复先对账，不自动替换调用。
9. 多 Run 慢模型／大结果下进程准入有效；实际 worker 未退出时不提前归还许可，等待只存引用，验证共享执行器影响。

首批后端验证覆盖原生循环、等待停止、批次拒绝、状态投影、未决超时和独立容量组件；补充验收覆盖原生 WAITING→READY 消息续接、Skill 内容与版本固定／工具交集，以及模型 HTTP 入口边界。持久化恢复与生产共享执行器集成仍按 P1／P3 验收。原生 asNode 反例后选择显式投影薄适配；Jackson 仅在 Agent 子模块对齐 graph-core 已声明的版本，详见验证记录。

若验证失败，按“现有配置 → 原生扩展点 → 评估受支持版本 → 必要局部适配”的顺序处理。框架升级需单独核验当前模型、saver 与风险 Agent 的兼容性，不能为用新接口无条件升级整套依赖。

## 4. 保留自有合同的原因

框架负责模型、工具、Graph、消息和检查点。以下属于本产品的事实与权限语义，不能靠替换成框架名省略：

- 当前用户究竟有权分析哪些短链、哪个期间，能否复用历史 Artifact。
- 一个统计 job 是否已经提交，是否仍处于幂等保留期，重启后能否恢复原任务。
- 一份报告是否覆盖所有目标，UV 是否整窗去重，相关分布能否回答因果问题。
- 新计划版本与旧回调谁有权推进，旧结果能否成为新版本的证据。

因此项目保留 PlanSpec、业务账本、Artifact 元数据、GoalAssessment 与报告协议；它们挂接原生封装，不演变成替代框架。三种账本可共用现有 MySQL 和清晰的领域事务，不代表三个独立基础设施系统。
