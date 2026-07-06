# 短链接项目：智能投放与分析 Agent 正式版验收清单（最终版）

> 文档性质：第一阶段文档验收与未来实现验收清单  
> 适用范围：正式版架构文档、开发文档、技术清单、配置说明、接口实现、测试与交付  
> 当前任务边界：本文档不声明当前代码已经完成 Agent 实现  
> 已确认技术路线：Spring AI Alibaba Agent Framework + Graph  
> 验收原则：每项必须有可复验证据，不接受“看起来合理”作为通过依据  

---

## 0. 使用说明

### 0.1 验收类型

本清单支持两类验收：

1. 文档验收：检查最终版文档是否完整、一致、无旧技术路线残留。
2. 实现验收：未来代码按最终版文档开发后，检查功能、架构、安全、测试和运行结果。

当前阶段主要用于文档验收。不得把文档中的目标态描述当作当前代码已实现的证据。

### 0.2 状态定义

| 状态 | 含义 |
|---|---|
| PASS | 有直接、可复现证据证明满足 |
| FAIL | 有证据证明不满足 |
| PARTIAL | 只满足部分要求 |
| BLOCKED | 外部条件缺失且已明确阻塞原因 |
| N/A | 经说明后确认不适用 |

### 0.3 证据要求

每项验收至少记录：

```text
检查项编号；
状态；
证据路径或命令；
关键输出；
问题说明；
整改建议；
责任人；
复验日期。
```

以下内容不得作为通过证据：

```text
看起来合理；
文档写了；
理论上可以；
模型回答说可以；
没有报错所以通过。
```

---

## 1. 一票否决项

任一失败，第一阶段不得验收通过。

- [ ] Agent 不能直接访问业务 MySQL 或 Redis。
- [ ] Agent 不能绕过 `admin` 用户权限访问 `gid`。
- [ ] Agent 不能执行未确认的写操作。
- [ ] Agent 不能自动删除、回收、禁用短链。
- [ ] Agent 回答中的数值指标必须来自工具返回或确定性计算。
- [ ] token、API Key、完整 IP、完整 UV 不得进入模型上下文和日志。
- [ ] DeepSeek API Key 不得进入文档、代码、Git、Prompt 或测试样例。
- [ ] Agent 不得接入短链跳转热路径调用 LLM。
- [ ] Graph checkpoint 不得作为短链业务最终事实源。
- [ ] 现有短链创建、跳转、统计能力不得回归。
- [ ] 第一阶段不得混入安全风控、智能路由、转化归因作为必验目标。

---

## 2. 文档完整性验收

### 2.1 文件存在

- [ ] `plan/智能投放与分析Agent/短链接项目_智能投放与分析Agent_外部方案调研_正式版.md` 存在。
- [ ] `plan/智能投放与分析Agent/短链接项目_智能投放与分析Agent_架构设计_最终版.md` 存在。
- [ ] `plan/智能投放与分析Agent/短链接项目_智能投放与分析Agent_正式版开发文档_最终版.md` 存在。
- [ ] `plan/智能投放与分析Agent/短链接项目_智能投放与分析Agent_技术清单与配置说明_最终版.md` 存在。
- [ ] `plan/智能投放与分析Agent/短链接项目_智能投放与分析Agent_正式版验收清单_最终版.md` 存在。

### 2.2 统一定位

- [ ] 文档明确“第一阶段选 Spring AI Alibaba Graph”。
- [ ] 文档明确 Agent Runtime 是 Java/Spring 体系。
- [ ] 文档明确“不完全重构，先构建 Agent 再接入”。
- [ ] 文档明确 `admin` 是受控工具与权限边界。
- [ ] 文档明确 `project` 是短链与统计事实源。
- [ ] 文档明确安全风控 Agent 为第二阶段能力。
- [ ] 文档明确不改造跳转热路径。
- [ ] 文档明确默认模型为 DeepSeek V4 Flash。
- [ ] 文档明确 `agent-service` 是顶级 Maven module。
- [ ] 文档明确第一阶段先 API 联调，再交付 agent-service 独立 Agent Console。
- [ ] 文档明确 Graph checkpoint 第一版直接 MySQL 持久化。

### 2.3 旧路线残留检查

- [ ] 正式版技术清单不再推荐 Python/FastAPI 作为第一阶段 Agent Runtime。
- [ ] 正式版开发文档不再把 Java/Spring AI Alibaba Graph 写成待确认候选项。
- [ ] 正式版验收清单不再检查 `_讨论稿.md` 文件名。
- [ ] 正式版架构不再要求 Agent Service 直连 project。
- [ ] 正式版文档不宣称 ROI/CPA/GMV 等当前系统没有的数据能力。

---

## 3. Spring AI Alibaba Graph 专项验收

### 3.1 Graph 结构

- [ ] 存在明确的 Graph 名称，如 `campaign-analysis-graph`。
- [ ] Graph 有版本号，如 `v1`。
- [ ] Graph 节点定义清晰。
- [ ] Graph 状态字段定义清晰。
- [ ] Graph 条件路由覆盖分析、创建、批量创建、追问、错误降级。
- [ ] Graph 失败可进入 `ErrorFallbackNode`。

### 3.2 节点职责

- [ ] `IntakeNode` 负责读取输入和上下文。
- [ ] `IntentRouterNode` 负责识别意图。
- [ ] `ScopeResolveNode` 负责解析 gid、短链、时间范围。
- [ ] `PermissionGuardNode` 负责权限边界。
- [ ] `ReadToolNode` 只调用只读工具。
- [ ] `AnalysisNode` 执行确定性计算。
- [ ] `ReasoningNode` 只做解释、总结和建议。
- [ ] `ProposalNode` 生成写操作草案。
- [ ] `PendingActionNode` 生成确认动作并中断。
- [ ] `ResponseComposeNode` 组装结构化响应。

### 3.3 中断与恢复

- [ ] 写操作必须在 PendingAction 节点中断。
- [ ] 中断状态可追溯到 `traceId`。
- [ ] 用户确认后由 admin 执行动作。
- [ ] 取消后不得执行动作。
- [ ] 过期后不得恢复执行。
- [ ] 重复确认不会重复执行。

### 3.4 Checkpoint

- [ ] Graph checkpoint 不保存敏感明文。
- [ ] Checkpoint 序列化有版本。
- [ ] Checkpoint 能按 `threadId` 查询。
- [ ] Checkpoint 不作为短链业务事实源。
- [ ] 第一版 checkpoint 必须落 MySQL。
- [ ] 如果第一版未落 MySQL，Graph checkpoint 验收必须 FAIL。

---

## 4. Agent Service 验收

### 4.1 服务基础

- [ ] `agent-service` 可独立启动。
- [ ] `GET /internal/short-link-agent/v1/health` 返回 OK。
- [ ] 配置不依赖硬编码 API Key。
- [ ] 服务启动失败时错误信息明确。
- [ ] Graph 初始化失败时能暴露清晰错误。
- [ ] `agent-service` 作为顶级 Maven module 存在。

### 4.2 Chat API

- [ ] `POST /internal/short-link-agent/v1/chat` 能接收用户消息。
- [ ] 支持新建 session。
- [ ] 支持续接 session。
- [ ] 返回结构包含 `answer/cards/pendingActions/dataSources/warnings`。
- [ ] 返回结构包含 `traceId`。
- [ ] 无法识别意图时能追问或说明。

### 4.3 意图识别

- [ ] 能识别创建单条短链。
- [ ] 能识别批量创建短链。
- [ ] 能识别分组分析。
- [ ] 能识别单链分析。
- [ ] 能识别异常解释。
- [ ] 能识别复盘报告。
- [ ] 对删除/回收请求能拒绝并说明第一阶段不支持。

### 4.4 模型调用

- [ ] 模型供应商可配置。
- [ ] 默认模型为 DeepSeek V4 Flash。
- [ ] 模型名称可配置。
- [ ] DeepSeek API Key 只从 `DEEPSEEK_API_KEY` 或 `LLM_API_KEY` 读取。
- [ ] Prompt Profile 有版本。
- [ ] 模型输出结构不合法时能降级。
- [ ] 模型上下文不包含完整敏感数据。

### 4.5 Agent Console

- [ ] `agent-service` 提供独立 Agent Console。
- [ ] Console 可发送对话请求并展示回答。
- [ ] Console 可展示 `cards` 指标卡。
- [ ] Console 可展示 `pendingActions` 预览。
- [ ] Console 可展示 `dataSources`。
- [ ] Console 可展示 Graph Trace 或节点流转摘要。
- [ ] Console 生产模式不绕过 admin/Gateway 鉴权。
- [ ] Console 不直接调用 admin 内部工具执行接口。

---

## 5. Tool Facade 与 Admin API 验收

### 5.1 权限

- [ ] Agent Tool Facade 不信任前端传入 username。
- [ ] Agent Tool Facade 使用 harness 注入的可信用户上下文。
- [ ] 查询 gid 前校验该 gid 属于当前用户。
- [ ] 越权 gid 返回 403 或业务错误码。
- [ ] 未登录请求不能访问 Agent API。
- [ ] admin internal API 有内部鉴权。
- [ ] 不建设 MCP Server 或对外远程工具协议。

### 5.2 只读工具

- [ ] `list_groups` 返回当前用户分组。
- [ ] `page_short_links` 能按 gid 查询短链。
- [ ] `get_short_link_stats` 能返回单链统计。
- [ ] `get_group_stats` 能返回分组统计。
- [ ] `get_access_records` 分页受限制。
- [ ] `get_access_records` 输出已脱敏。
- [ ] 空数据场景返回空状态而不是异常。

### 5.3 写操作确认

- [ ] 创建分组生成 PendingAction。
- [ ] 创建单条短链生成 PendingAction。
- [ ] 批量创建短链生成 PendingAction。
- [ ] 未确认 PendingAction 不执行。
- [ ] 已过期 PendingAction 不执行。
- [ ] 已执行 PendingAction 不能重复执行。
- [ ] 批量创建超过上限被拒绝。
- [ ] 执行失败有错误原因和审计记录。

---

## 6. 分析能力验收

### 6.1 分组分析

- [ ] 输入“分析最近 7 天某分组表现”能调用分组统计。
- [ ] 输出包含 PV/UV/UIP。
- [ ] 输出包含趋势或日维度解读。
- [ ] 输出包含设备/地区/浏览器等至少一个画像维度。
- [ ] 输出标明数据时间范围。
- [ ] 输出标明数据来源工具。

### 6.2 单链分析

- [ ] 输入完整短链或短链描述能定位链接。
- [ ] 输出单链 PV/UV/UIP。
- [ ] 输出访问画像。
- [ ] 无法定位时追问，不编造。

### 6.3 异常解释

- [ ] 能识别 PV 高但 UV 低的可能刷量风险。
- [ ] 能识别 Top IP 过度集中。
- [ ] 能识别设备或地区高度集中。
- [ ] 输出必须标明“可能原因”，不得给出确定性安全结论。
- [ ] 建议为低风险动作，如继续观察、拆分渠道、检查来源。

### 6.4 报告生成

- [ ] 能生成活动复盘摘要。
- [ ] 报告包含结论、关键指标、画像、异常、建议。
- [ ] 报告不包含未授权明细。
- [ ] 报告可复制或返回结构化文本。

---

## 7. 创建能力验收

### 7.1 单条创建

- [ ] 用户给出 URL 后 Agent 能生成草案。
- [ ] 缺少分组时能追问或建议创建分组。
- [ ] 能调用 `get_url_title` 辅助描述。
- [ ] 有效期缺失时使用明确默认策略并告知用户。
- [ ] 用户确认后创建成功。
- [ ] 返回完整短链。

### 7.2 批量创建

- [ ] 能根据渠道列表生成多条短链草案。
- [ ] 每条短链有清晰 `describe`。
- [ ] 超过数量上限时拒绝。
- [ ] 部分失败时返回失败项。
- [ ] 批量结果可追溯。

---

## 8. 安全验收

- [ ] 模型上下文不包含 token。
- [ ] 模型上下文不包含完整 IP。
- [ ] 模型上下文不包含完整 UV。
- [ ] URL title 被当作数据而不是系统指令。
- [ ] 用户输入中的“忽略规则”等 prompt injection 不生效。
- [ ] 内部工具 API 有鉴权。
- [ ] Agent 服务外部不可直接访问工具执行接口。
- [ ] 日志中不输出密钥。
- [ ] 工具错误堆栈不原样注入模型。
- [ ] PendingAction payload 有 hash 防篡改。

---

## 9. 回归验收

现有能力必须保持：

- [ ] 用户注册登录仍可用。
- [ ] 分组创建和列表仍可用。
- [ ] 短链创建仍可用。
- [ ] 批量短链创建仍可用。
- [ ] 短链跳转仍可用。
- [ ] 短链统计仍可用。
- [ ] 回收站仍可用。
- [ ] Gateway 鉴权仍可用。
- [ ] Nginx 前端代理不受影响。

---

## 10. 性能与稳定性验收

- [ ] 只读分析工具单次调用 P95 小于可接受阈值。
- [ ] Agent 分析请求超时时能降级。
- [ ] Agent 服务不可用时不影响短链跳转。
- [ ] Agent 服务不可用时不影响后台传统功能。
- [ ] 批量创建有数量限制。
- [ ] 访问明细查询有分页限制。
- [ ] Graph 节点失败不会导致无限循环。
- [ ] 工具调用失败不会自动重复执行写操作。

---

## 11. 文档与实现一致性验收

- [ ] 实现中的 API 路径与正式文档一致。
- [ ] 实现中的配置项与正式文档一致。
- [ ] 实现中的 Graph 节点名与正式文档一致或有映射说明。
- [ ] 实现中的错误码与正式文档一致。
- [ ] 实现中的数据表与正式文档一致或有迁移说明。
- [ ] 如果因框架版本调整依赖名，必须在技术清单中记录。

---

## 12. 第二阶段准入条件

只有满足以下条件，才建议进入安全风控 Agent 或智能路由开发：

- [ ] 第一阶段 Agent 工具调用稳定。
- [ ] PendingAction 机制可靠。
- [ ] 权限校验经过测试。
- [ ] 回答数据来源可追溯。
- [ ] Graph checkpoint 策略明确。
- [ ] 现有短链主链路无回归。
- [ ] 用户确认安全风控 Agent 的风险等级、拦截策略和外部服务选型。
